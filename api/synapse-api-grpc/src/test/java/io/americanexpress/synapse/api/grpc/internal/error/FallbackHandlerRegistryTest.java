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
package io.americanexpress.synapse.api.grpc.internal.error;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.ErrorInfo;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerMethodDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import io.americanexpress.synapse.api.grpc.internal.service.RawBytesMarshaller;
import io.americanexpress.synapse.core.autoconfigure.SynapseErrorProperties;
import io.americanexpress.synapse.core.error.ErrorMessages;
import io.americanexpress.synapse.core.internal.context.CorrelationIdPolicy;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackHandlerRegistryTest {

    private static final Metadata.Key<String> CORRELATION = Metadata.Key
            .of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);

    private static final String UNKNOWN = "synapse.fixture.v1.CatalogService/Nope";

    private final FallbackHandlerRegistry registry = new FallbackHandlerRegistry(
            new GrpcStatusWriter(new GrpcErrorStatuses(Map.of(), Map.of()),
                    new ErrorMessages(new StaticMessageSource()),
                    new SynapseErrorProperties(false, null), "books"),
            new CorrelationIdPolicy(128), CORRELATION);

    private final String name = InProcessServerBuilder.generateName();

    private @Nullable Server server;

    private @Nullable ManagedChannel channel;

    /** A call's failure and the trailers the client received. */
    record Answer(StatusRuntimeException failure, @Nullable Metadata trailers) {

        ErrorInfo errorInfo() throws InvalidProtocolBufferException {
            com.google.rpc.Status status = Objects.requireNonNull(StatusProto.fromThrowable(failure), "details");
            assertThat(status.getDetailsList()).hasSize(1);
            return status.getDetails(0).unpack(ErrorInfo.class);
        }

        @Nullable
        String echoed() {
            return trailers == null ? null : trailers.get(CORRELATION);
        }
    }

    @BeforeEach
    void start() throws IOException {
        // No services: every method falls through to the registry, as an unknown method does on Boot's server.
        server = InProcessServerBuilder
                .forName(name)
                .directExecutor()
                .fallbackHandlerRegistry(registry)
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    @AfterEach
    void stop() throws InterruptedException {
        Objects.requireNonNull(channel).shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        Objects.requireNonNull(server).shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    private Answer call(String method, String... correlationIds) {
        Metadata metadata = new Metadata();
        for (String id : correlationIds) {
            metadata.put(CORRELATION, id);
        }
        AtomicReference<@Nullable Metadata> headers = new AtomicReference<>();
        AtomicReference<@Nullable Metadata> trailers = new AtomicReference<>();
        Channel intercepted = ClientInterceptors
                .intercept(Objects.requireNonNull(channel), MetadataUtils.newAttachHeadersInterceptor(metadata),
                        MetadataUtils.newCaptureMetadataInterceptor(headers, trailers));
        MethodDescriptor<byte[], byte[]> raw = MethodDescriptor
                .newBuilder(RawBytesMarshaller.INSTANCE, RawBytesMarshaller.INSTANCE)
                .setFullMethodName(method)
                .setType(MethodDescriptor.MethodType.UNARY)
                .build();
        try {
            ClientCalls
                    .blockingUnaryCall(intercepted, raw, CallOptions.DEFAULT.withDeadlineAfter(10, TimeUnit.SECONDS),
                            new byte[]{1, 2, 3});
        } catch (StatusRuntimeException e) {
            assertThat(headers.get()).as("a trailers-only response").isNull();
            return new Answer(e, trailers.get());
        }
        throw new AssertionError("the call succeeded");
    }

    @Test
    void answersUnimplementedWithErrorInfo() throws InvalidProtocolBufferException {
        Answer answer = call(UNKNOWN, "c-1");

        assertThat(answer.failure().getStatus().getCode()).isEqualTo(Status.Code.UNIMPLEMENTED);
        assertThat(answer.failure().getStatus().getDescription()).isNullOrEmpty();
        ErrorInfo info = answer.errorInfo();
        assertThat(info.getReason()).isEqualTo("METHOD_NOT_FOUND");
        assertThat(info.getDomain()).isEqualTo("books");
        assertThat(info.getMetadataMap()).isEqualTo(Map.of("correlationId", "c-1"));
        assertThat(answer.echoed()).isEqualTo("c-1");
        assertThat(Objects.requireNonNull(answer.trailers()).getAll(CORRELATION)).containsExactly("c-1");
    }

    @Test
    void takesTheFirstCorrelationIdTheCallerSent() throws InvalidProtocolBufferException {
        Answer answer = call(UNKNOWN, "c-first", "c-second");

        assertThat(answer.errorInfo().getMetadataMap()).containsEntry("correlationId", "c-first");
        assertThat(answer.echoed()).isEqualTo("c-first");
    }

    @Test
    void generatesACorrelationIdWhenTheCallerSentNone() throws InvalidProtocolBufferException {
        Answer answer = call(UNKNOWN);

        assertThat(answer.failure().getStatus().getCode()).isEqualTo(Status.Code.UNIMPLEMENTED);
        assertThat(answer.echoed()).matches(GrpcStatusWriterTest.UUID_PATTERN);
        assertThat(answer.errorInfo().getMetadataMap()).containsEntry("correlationId", answer.echoed());
    }

    @Test
    void replacesAnInvalidCorrelationId() throws InvalidProtocolBufferException {
        Answer answer = call(UNKNOWN, "evil id");

        assertThat(answer.echoed()).matches(GrpcStatusWriterTest.UUID_PATTERN);
        assertThat(answer.errorInfo().getMetadataMap()).containsEntry("correlationId", answer.echoed());
    }

    @Test
    void answersAnyMethodName() throws InvalidProtocolBufferException {
        Answer answer = call("no.such.Service/AtAll");

        assertThat(answer.failure().getStatus().getCode()).isEqualTo(Status.Code.UNIMPLEMENTED);
        assertThat(answer.errorInfo().getReason()).isEqualTo("METHOD_NOT_FOUND");
    }

    @Test
    void looksUpADefinitionNamedAfterTheCalledMethod() {
        ServerMethodDefinition<?, ?> definition = registry.lookupMethod(UNKNOWN, null);

        assertThat(definition).isNotNull();
        assertThat(definition.getMethodDescriptor().getFullMethodName()).isEqualTo(UNKNOWN);
        assertThat(definition.getMethodDescriptor().getType()).isEqualTo(MethodDescriptor.MethodType.UNKNOWN);
        assertThat(registry.getServices()).isEmpty();
    }
}
