/*
 * Copyright 2026 American Express Travel Related Services Company, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.americanexpress.synapse.api.grpc.internal.service;

import java.util.Map;
import java.util.Optional;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.api.grpc.internal.binding.MethodDeclaration;
import io.americanexpress.synapse.api.grpc.internal.conditional.GrpcPreconditions;
import io.americanexpress.synapse.api.grpc.internal.edge.GrpcCallState;
import io.americanexpress.synapse.api.grpc.internal.error.NotFoundWithoutArgumentsException;
import io.americanexpress.synapse.api.grpc.internal.mapping.MappingException;
import io.americanexpress.synapse.api.grpc.internal.mapping.MessageMapping;
import io.americanexpress.synapse.api.grpc.internal.mapping.PagingRules;
import io.americanexpress.synapse.api.grpc.internal.mapping.RecordReader;
import io.americanexpress.synapse.api.grpc.internal.mapping.RecordWriter;
import io.americanexpress.synapse.core.autoconfigure.SynapsePagingProperties;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.internal.binding.OperationTypes;
import io.americanexpress.synapse.core.internal.paging.PageCursors;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.operation.ServiceOperation;
import io.americanexpress.synapse.core.paging.PageResult;

/**
 * Handles the calls of one bound unary method. In order, it:
 * <ol>
 * <li>records the operation name on the call's {@link GrpcCallState}, so every error of the call, a malformed request
 * included, uses the operation's status overrides;</li>
 * <li>parses the request bytes with protobuf's default recursion limit (100), which the reader relies on; bytes that
 * aren't a valid message are {@code MALFORMED_REQUEST};</li>
 * <li>reads the operation's input record; the reader's violations already name proto field paths. On a method that
 * binds a {@code PageQuery}, a {@code page_token} is unwrapped into the operation's raw cursor with core's
 * {@link PageCursors}, as REST unwraps its cursor parameter: after the violations, before a precondition the input
 * doesn't need; a rejected token is {@code VALIDATION_FAILED} with the violation
 * {@code {page_token, is not valid for this request}};</li>
 * <li>records the binding's field paths on the call's state, so violations the operation pipeline reports by record
 * component (Bean Validation) are named by proto field path; the reader's are never mapped again;</li>
 * <li>calls the operation bean, a proxy that runs the operation pipeline. A {@code STALE_VERSION} it throws on a
 * request that sent an {@code etag} becomes {@code PRECONDITION_FAILED}, as over REST ({@link GrpcPreconditions});</li>
 * <li>answers an empty {@code Optional} (or {@code null} for an {@code Optional} output) with the method's
 * {@code .notFound} error, {@code NOT_FOUND} by default. The error has no message arguments, so when the declared
 * code's template has placeholders (decided once, when the handler is created) it is a
 * {@link NotFoundWithoutArgumentsException}, described with {@code NOT_FOUND}'s message;</li>
 * <li>writes the response and completes the call. A {@code PageResult} gets the token of its next page, by REST's
 * {@code Link: next} rule: its next cursor wrapped, or the next offset when it has none (none when {@code hasNext} is
 * false; past {@code synapse.paging.max-offset}, a token that fails with the offset limit when used); it is in the
 * {@code next_page_token}, bound to the operation, the sort and the input's filters with no extra entries, so a token
 * issued over an unversioned REST route for the same request pages here too, and the reverse.</li>
 * </ol>
 * Every failure is thrown, so Spring gRPC's exception handling answers it with Synapse's error details.
 */
public final class UnaryOperationHandler implements ServerCalls.UnaryMethod<byte[], DynamicMessage> {

    /** The request field that carries a page token, which a rejected token's violation names. */
    static final String PAGE_TOKEN = "page_token";

    /**
     * The extra entries of a gRPC cursor's scope: none, so a cursor issued by an unversioned REST route for the same
     * operation, filters and sort pages here too.
     */
    private static final Map<String, ?> CURSOR_EXTRAS = Map.of();

    private final MethodDeclaration method;

    private final Descriptor input;

    private final RecordReader reader;

    private final RecordWriter writer;

    private final PageCursors cursors;

    private final boolean pagedInput;

    /** {@code synapse.paging.max-offset}: the furthest offset a next-page token may carry. */
    private final long maxOffset;

    private final ErrorDetails notFound;

    private final boolean notFoundNeedsArguments;

    private UnaryOperationHandler(MethodDeclaration method, Descriptor input, RecordReader reader, RecordWriter writer,
            PageCursors cursors, long maxOffset) {
        this.method = method;
        this.input = input;
        this.reader = reader;
        this.writer = writer;
        this.cursors = cursors;
        this.pagedInput = method.pagedInput();
        this.maxOffset = maxOffset;
        ErrorDetails declared = method.notFound();
        this.notFound = declared != null ? declared : SynapseErrors.NOT_FOUND;
        this.notFoundNeedsArguments = NotFoundWithoutArgumentsException.needsArguments(notFound);
    }

    /**
     * Creates the handler of a method, mapping its messages to the operation's records.
     *
     * @param method the method's binding
     * @param input the request message type
     * @param output the response message type
     * @param cursors core's page cursors, which wrap and unwrap page tokens
     * @param paging core's {@code synapse.paging} properties: the page sizes and the offset cap
     * @return the handler
     * @throws MappingException when the messages can't be mapped to the records; when the request's only problem is
     *         required scalars without presence ({@link MappingException#presenceOnly()}), only once the response
     *         mapped
     */
    public static UnaryOperationHandler create(MethodDeclaration method, Descriptor input, Descriptor output,
            PageCursors cursors, SynapsePagingProperties paging) {
        // As REST checks the preconditions of a write only (every method but GET and HEAD), gRPC does so for a method
        // bound to a CommandOperation.
        boolean write = CommandOperation.class.isAssignableFrom(OperationTypes.userClassOf(method.operation()));
        RecordReader reader;
        try {
            reader = MessageMapping
                    .reader(input, method.inputType(), method.overrides(), method.contexts(), write,
                            PagingRules.of(paging, method.sortKeys()));
        } catch (MappingException e) {
            if (!e.presenceOnly()) {
                throw e;
            }
            // The response is still checked: any other problem fails at once, before the presence problems.
            MessageMapping.writer(output, method.outputGenericType(), method.overrides(), method.etag());
            throw e;
        }
        RecordWriter writer = MessageMapping
                .writer(output, method.outputGenericType(), method.overrides(), method.etag());
        return new UnaryOperationHandler(method, input, reader, writer, cursors, paging.maxOffset());
    }

    @Override
    public void invoke(byte[] request, StreamObserver<DynamicMessage> responseObserver) {
        GrpcCallState state = GrpcCallState.current();
        if (state != null) {
            state.operationName(method.operationName());
        }
        DynamicMessage message = parse(request);
        // The digest leaves the PageQuery out, so the unwrapped input has the same cursor scope as the bound one.
        @Nullable
        Object record = pagedInput
                ? reader
                        .read(message,
                                bound -> cursors
                                        .unwrap(method.operationName(), bound, CURSOR_EXTRAS, PAGE_TOKEN, maxOffset))
                : reader.read(message);
        boolean etagSent = reader.ifMatchSent(message);
        if (state != null) {
            state.fieldPaths(reader.fieldPaths());
        }
        @Nullable
        Object result;
        try {
            result = execute(method.operation(), record);
        } catch (SynapseException e) {
            throw GrpcPreconditions.translate(etagSent, e);
        }
        @Nullable
        Object output = unwrap(result);
        DynamicMessage response;
        try {
            response = write(record, output);
        } catch (IllegalStateException e) {
            // An output the response can't hold (a value out of range, two members of a oneof) is an operation bug:
            // INTERNAL for the caller, and a log line that names the method.
            throw new IllegalStateException("gRPC method " + method.method().getFullMethodName() + " ("
                    + method.operationName() + "): " + e.getMessage(), e);
        }
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    /**
     * Writes the output; a page with the token of the next page by REST's Link: next rule ({@link PageCursors#next}):
     * its wrapped cursor, or for a page without one the next offset (past {@code synapse.paging.max-offset}, a token
     * that fails with the offset limit when used), so the operation's raw cursor never reaches the caller.
     */
    private DynamicMessage write(@Nullable Object record, @Nullable Object output) {
        if (!writer.pages()) {
            return writer.write(output);
        }
        PageResult<?> page = (PageResult<?>) output;
        String token = page == null
                ? null
                : cursors.next(method.operationName(), record, CURSOR_EXTRAS, page, maxOffset);
        return writer.writePage(page, token);
    }

    private DynamicMessage parse(byte[] request) {
        try {
            return DynamicMessage.parseFrom(input, request);
        } catch (InvalidProtocolBufferException e) {
            throw new SynapseException(SynapseErrors.MALFORMED_REQUEST, e);
        }
    }

    /** Unwraps an {@code Optional} output; empty is the method's not-found error. */
    private @Nullable Object unwrap(@Nullable Object result) {
        if (method.outputType() != Optional.class) {
            return result;
        }
        Optional<?> present = result instanceof Optional<?> optional ? optional : Optional.empty();
        if (present.isEmpty()) {
            throw notFoundNeedsArguments
                    ? new NotFoundWithoutArgumentsException(notFound)
                    : new SynapseException(notFound);
        }
        return present.get();
    }

    /**
     * Calls the operation with the input, which is {@code null} only when the input type is {@code Void}. The output
     * is {@code null} for a {@code Void} operation. NullAway can't see that {@code I} is {@code Void} when the input
     * is {@code null}, as callers of a {@code ServiceOperation<Void, O>} pass it.
     */
    @SuppressWarnings({"unchecked", "NullAway"})
    private static <I, O> @Nullable Object execute(ServiceOperation<I, O> operation, @Nullable Object input) {
        return operation.execute((I) input);
    }
}
