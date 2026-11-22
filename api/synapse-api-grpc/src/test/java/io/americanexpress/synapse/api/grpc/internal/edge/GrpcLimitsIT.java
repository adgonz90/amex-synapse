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
package io.americanexpress.synapse.api.grpc.internal.edge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.ErrorInfo;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.test.context.ActiveProfiles;

import io.americanexpress.synapse.api.grpc.fixture.proto.CatalogServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.GetItemRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.Item;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The failures a Netty server answers below or beside the operations: an unknown method, metadata over the inbound
 * limit and a message over the inbound limit. The message limit is lowered to 1 KB and the concurrency limit to one call
 * here, so a permit a call fails to release rejects the next call; the metadata limit is Synapse's default.
 */
@SpringBootTest(classes = GrpcLimitsIT.TestApplication.class, properties = {"spring.grpc.server.port=0",
        "spring.grpc.server.inbound.message.max-size=1KB", "synapse.api.grpc.max-concurrent-calls=1",
        "management.logging.export.otlp.enabled=false",
        "management.tracing.export.otlp.enabled=false", "management.otlp.metrics.export.enabled=false"})
@ExtendWith(OutputCaptureExtension.class)
@ActiveProfiles("test")
class GrpcLimitsIT {

    private static final Metadata.Key<String> CORRELATION = Metadata.Key
            .of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);

    /** Marks the calls whose arrival at the interceptors {@link Arrivals} records. */
    private static final Metadata.Key<String> PROBE = Metadata.Key.of("x-probe", Metadata.ASCII_STRING_MARSHALLER);

    private static final Metadata.Key<String> BIG = Metadata.Key.of("x-big", Metadata.ASCII_STRING_MARSHALLER);

    @LocalGrpcServerPort
    private int port;

    @Autowired
    private Invocations invocations;

    @Autowired
    private Arrivals arrivals;

    /** A call's outcome and the trailers the client received. */
    record Result<T>(@Nullable T value, @Nullable StatusRuntimeException failure, @Nullable Metadata trailers) {

        T ok() {
            return Objects.requireNonNull(value, () -> "the call failed: " + failure);
        }

        StatusRuntimeException error() {
            return Objects.requireNonNull(failure, "the call succeeded");
        }

        /** The {@code ErrorInfo} detail, or {@code null} when the status carries no details. */
        @Nullable
        ErrorInfo errorInfo() throws InvalidProtocolBufferException {
            com.google.rpc.Status status = StatusProto.fromThrowable(error());
            if (status == null) {
                return null;
            }
            for (Any any : status.getDetailsList()) {
                if (any.is(ErrorInfo.class)) {
                    return any.unpack(ErrorInfo.class);
                }
            }
            return null;
        }
    }

    private <T> Result<T> call(MethodDescriptor<GetItemRequest, T> method, GetItemRequest request, Metadata metadata)
            throws InterruptedException {
        AtomicReference<@Nullable Metadata> headers = new AtomicReference<>();
        AtomicReference<@Nullable Metadata> trailers = new AtomicReference<>();
        ManagedChannel channel = Grpc
                .newChannelBuilderForAddress("localhost", port, InsecureChannelCredentials.create())
                .build();
        try {
            Channel intercepted = ClientInterceptors
                    .intercept(channel, MetadataUtils.newAttachHeadersInterceptor(metadata),
                            MetadataUtils.newCaptureMetadataInterceptor(headers, trailers));
            T value = ClientCalls
                    .blockingUnaryCall(intercepted, method,
                            CallOptions.DEFAULT.withDeadlineAfter(10, TimeUnit.SECONDS), request);
            return new Result<>(value, null, trailers.get());
        } catch (StatusRuntimeException e) {
            return new Result<>(null, e, trailers.get());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private Result<Item> getItem(GetItemRequest request, Metadata metadata) throws InterruptedException {
        return call(CatalogServiceGrpc.getGetItemMethod(), request, metadata);
    }

    private static GetItemRequest request(int idLength) {
        return GetItemRequest.newBuilder().setId("i".repeat(idLength)).build();
    }

    @Test
    @Tag("smoke")
    void unknownMethodIsUnimplemented() throws InterruptedException, InvalidProtocolBufferException {
        MethodDescriptor<GetItemRequest, Item> nope = CatalogServiceGrpc
                .getGetItemMethod()
                .toBuilder()
                .setFullMethodName(MethodDescriptor.generateFullMethodName(CatalogServiceGrpc.SERVICE_NAME, "Nope"))
                .build();
        Metadata metadata = new Metadata();
        metadata.put(CORRELATION, "c-nope");

        Result<Item> result = call(nope, request(1), metadata);

        assertThat(result.error().getStatus().getCode()).isEqualTo(Status.Code.UNIMPLEMENTED);
        ErrorInfo info = result.errorInfo();
        assertThat(info).isNotNull();
        assertThat(Objects.requireNonNull(info).getReason()).isEqualTo("METHOD_NOT_FOUND");
        assertThat(info.getMetadataMap()).containsEntry("correlationId", "c-nope");
        assertThat(Objects.requireNonNull(result.trailers()).get(CORRELATION)).isEqualTo("c-nope");
    }

    @Test
    void metadataLimitDefaultsTo16Kb() throws InterruptedException {
        int before = invocations.count.get();
        Metadata twelve = new Metadata();
        twelve.put(BIG, "a".repeat(12 * 1024));
        Metadata twenty = new Metadata();
        twenty.put(BIG, "a".repeat(20 * 1024));

        // Over Boot's own 8 KB default, under Synapse's 16 KB.
        assertThat(getItem(request(1), twelve).ok().getId()).isEqualTo("i");
        Result<Item> over = getItem(request(1), twenty);

        // The client's Netty refuses to send it (INTERNAL, HeaderListSizeException) because the server advertises
        // SETTINGS_MAX_HEADER_LIST_SIZE 16384: the refusal proves the server's limit is Synapse's 16 KB.
        assertThat(over.error().getStatus().getCode()).isNotEqualTo(Status.Code.OK);
        assertThat(causeChain(over.error()))
                .as("the refusal names the advertised limit")
                .anyMatch(text -> text
                        .contains("16384"));
        assertThat(invocations.count).hasValue(before + 1);
    }

    /** The {@code toString()} of a failure and of each of its causes. */
    private static List<String> causeChain(Throwable failure) {
        List<String> chain = new ArrayList<>();
        Throwable cause = failure;
        while (cause != null) {
            chain.add(cause.toString());
            cause = cause.getCause();
        }
        return chain;
    }

    @Test
    void messageOverTheLimitIsResourceExhausted(CapturedOutput output)
            throws InterruptedException, InvalidProtocolBufferException {
        int before = invocations.count.get();
        int logStart = output.getAll().length();
        Metadata metadata = new Metadata();
        metadata.put(PROBE, "oversized");

        Result<Item> result = getItem(request(2 * 1024), metadata);

        StatusRuntimeException e = result.error();
        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
        assertThat(result.errorInfo()).as("the transport answers; no interceptor can add ErrorInfo").isNull();
        assertThat(invocations.count).hasValue(before);
        // The call does pass the edge: grpc-java checks the size only once the call has requested a message, after
        // interceptCall, and then cancels the call. The cancellation reaches the listeners on the call executor,
        // possibly after the client has its status; Arrivals records it once the inner listeners have handled it.
        assertThat(arrivals.awaitEnd("oversized", 5, TimeUnit.SECONDS)).as("the call ended at the edge").isTrue();
        assertThat(arrivals.seen).containsExactly("oversized: interceptCall", "oversized: onCancel");
        // No Synapse failure line: the transport rejected the message, no operation or exception handler ran.
        assertThat(output.getAll().substring(logStart).lines())
                .filteredOn(line -> line.contains(" WARN ") || line.contains(" ERROR "))
                .noneMatch(line -> line.contains("io.americanexpress.synapse") || line.contains("i.a.s."));
        // Under the limit, the same call reaches the operation: with one permit, it shows the cancel released it.
        assertThat(getItem(request(512), new Metadata()).ok().getId()).hasSize(512);
        assertThat(invocations.count).hasValue(before + 1);
    }

    /** How many times the operation's body ran. */
    static class Invocations {

        final AtomicInteger count = new AtomicInteger();
    }

    /**
     * Records each probed call that reaches the interceptors, and what happens to it there. It sits right inside
     * Synapse's edge interceptor, the outermost one.
     */
    static class Arrivals implements ServerInterceptor {

        final List<String> seen = new CopyOnWriteArrayList<>();

        /** Released for each probe once its call has completed or been cancelled, after the inner listeners. */
        private final Map<String, CountDownLatch> ended = new ConcurrentHashMap<>();

        /**
         * Waits until the probed call has completed or been cancelled at the interceptors.
         *
         * @return whether it ended within the timeout
         */
        boolean awaitEnd(String probe, long timeout, TimeUnit unit) throws InterruptedException {
            return ended(probe).await(timeout, unit);
        }

        private CountDownLatch ended(String probe) {
            return ended.computeIfAbsent(probe, key -> new CountDownLatch(1));
        }

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
                ServerCallHandler<ReqT, RespT> next) {
            String probe = headers.get(PROBE);
            if (probe == null) {
                return next.startCall(call, headers);
            }
            seen.add(probe + ": interceptCall");
            return new io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener<>(
                    next.startCall(call, headers)) {

                @Override
                public void onMessage(ReqT message) {
                    seen.add(probe + ": onMessage");
                    super.onMessage(message);
                }

                @Override
                public void onCancel() {
                    super.onCancel();
                    // After the inner listeners, so the context interceptor has released its permit.
                    seen.add(probe + ": onCancel");
                    ended(probe).countDown();
                }

                @Override
                public void onComplete() {
                    seen.add(probe + ": onComplete");
                    super.onComplete();
                    ended(probe).countDown();
                }
            };
        }
    }

    /** Echoes the id after counting the call. */
    static class EchoOperation implements QueryOperation<String, String> {

        private final Invocations invocations;

        EchoOperation(Invocations invocations) {
            this.invocations = invocations;
        }

        @Override
        public String execute(String input) {
            invocations.count.incrementAndGet();
            return input;
        }
    }

    /** A hand-written service whose {@code GetItem} calls the echo operation. */
    static class EchoService extends CatalogServiceGrpc.CatalogServiceImplBase {

        private final EchoOperation echo;

        EchoService(EchoOperation echo) {
            this.echo = echo;
        }

        @Override
        public void getItem(GetItemRequest request, StreamObserver<Item> responseObserver) {
            responseObserver.onNext(Item.newBuilder().setId(echo.execute(request.getId())).build());
            responseObserver.onCompleted();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {

        @Bean
        Invocations invocations() {
            return new Invocations();
        }

        @Bean
        EchoOperation echoOperation(Invocations invocations) {
            return new EchoOperation(invocations);
        }

        @Bean
        EchoService echoService(EchoOperation echo) {
            return new EchoService(echo);
        }

        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE + 1)
        @GlobalServerInterceptor
        Arrivals arrivals() {
            return new Arrivals();
        }
    }
}
