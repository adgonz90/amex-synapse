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

import java.util.Optional;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.api.grpc.internal.binding.MethodDeclaration;
import io.americanexpress.synapse.api.grpc.internal.edge.GrpcCallState;
import io.americanexpress.synapse.api.grpc.internal.error.NotFoundWithoutArgumentsException;
import io.americanexpress.synapse.api.grpc.internal.mapping.MappingException;
import io.americanexpress.synapse.api.grpc.internal.mapping.MessageMapping;
import io.americanexpress.synapse.api.grpc.internal.mapping.RecordReader;
import io.americanexpress.synapse.api.grpc.internal.mapping.RecordWriter;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.operation.ServiceOperation;

/**
 * Handles the calls of one bound unary method. In order, it:
 * <ol>
 * <li>records the operation name on the call's {@link GrpcCallState}, so every error of the call, a malformed request
 * included, uses the operation's status overrides;</li>
 * <li>parses the request bytes with protobuf's default recursion limit (100), which the reader relies on; bytes that
 * aren't a valid message are {@code MALFORMED_REQUEST};</li>
 * <li>reads the operation's input record; the reader's violations already name proto field paths;</li>
 * <li>records the binding's field paths on the call's state, so violations the operation pipeline reports by record
 * component (Bean Validation) are named by proto field path; the reader's are never mapped again;</li>
 * <li>calls the operation bean, a proxy that runs the operation pipeline;</li>
 * <li>answers an empty {@code Optional} (or {@code null} for an {@code Optional} output) with the method's
 * {@code .notFound} error, {@code NOT_FOUND} by default. The error has no message arguments, so when the declared
 * code's template has placeholders (decided once, when the handler is created) it is a
 * {@link NotFoundWithoutArgumentsException}, described with {@code NOT_FOUND}'s message;</li>
 * <li>writes the response and completes the call.</li>
 * </ol>
 * Every failure is thrown, so Spring gRPC's exception handling answers it with Synapse's error details.
 */
public final class UnaryOperationHandler implements ServerCalls.UnaryMethod<byte[], DynamicMessage> {

    private final MethodDeclaration method;

    private final Descriptor input;

    private final RecordReader reader;

    private final RecordWriter writer;

    private final ErrorDetails notFound;

    private final boolean notFoundNeedsArguments;

    private UnaryOperationHandler(MethodDeclaration method, Descriptor input, RecordReader reader,
            RecordWriter writer) {
        this.method = method;
        this.input = input;
        this.reader = reader;
        this.writer = writer;
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
     * @return the handler
     * @throws MappingException when the messages can't be mapped to the records
     */
    public static UnaryOperationHandler create(MethodDeclaration method, Descriptor input, Descriptor output) {
        RecordReader reader = MessageMapping.reader(input, method.inputType(), method.overrides(), method.contexts());
        RecordWriter writer = MessageMapping.writer(output, method.outputGenericType(), method.overrides());
        return new UnaryOperationHandler(method, input, reader, writer);
    }

    @Override
    public void invoke(byte[] request, StreamObserver<DynamicMessage> responseObserver) {
        GrpcCallState state = GrpcCallState.current();
        if (state != null) {
            state.operationName(method.operationName());
        }
        @Nullable
        Object record = reader.read(parse(request));
        if (state != null) {
            state.fieldPaths(reader.fieldPaths());
        }
        @Nullable
        Object output = unwrap(execute(method.operation(), record));
        responseObserver.onNext(writer.write(output));
        responseObserver.onCompleted();
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
