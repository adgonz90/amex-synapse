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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.ErrorInfo;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Deadline;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.tracing.Baggage;
import io.micrometer.tracing.Tracer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerExecutorProvider;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import io.americanexpress.synapse.api.grpc.fixture.proto.CatalogServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.GetItemRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.Item;
import io.americanexpress.synapse.core.context.RequestContext;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calls a real Netty server running on virtual threads with OpenTelemetry tracing, through the whole edge (Synapse's
 * edge, Boot's observation and exception handling, Synapse's context interceptor), and checks the request context, MDC
 * and baggage seen by an operation and by an {@code @Async} task it starts. The service is a hand-written
 * {@code *ImplBase} (the escape hatch) whose methods call operation beans, so each call also runs the pipeline.
 */
@SpringBootTest(classes = GrpcEdgeIT.TestApplication.class, properties = {"spring.grpc.server.port=0",
        "spring.threads.virtual.enabled=true", "synapse.api.grpc.context.metadata.tenant=x-tenant",
        "management.tracing.sampling.probability=1.0", "management.logging.export.otlp.enabled=false",
        "management.tracing.export.otlp.enabled=false", "management.otlp.metrics.export.enabled=false"})
@ExtendWith(OutputCaptureExtension.class)
@ActiveProfiles("test")
class GrpcEdgeIT {

    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private static final Metadata.Key<String> CORRELATION = Metadata.Key
            .of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);

    /** Metadata that makes {@link ExpiringInterceptor} give the call an expired gRPC deadline. */
    private static final String EXPIRE = "x-test-expire";

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor();

    /** Turns on {@link SingleThreadExecutorProvider}. */
    private static final String SINGLE_THREAD = "test.grpc.single-thread";

    /** The name of the one platform thread of {@link SingleThreadExecutorProvider}. */
    private static final String REUSED_THREAD = "reused-grpc";

    @LocalGrpcServerPort
    private int port;

    @Autowired
    private Recorded recorded;

    @Autowired
    private ServerObservations observations;

    @Autowired
    private Invocations invocations;

    @AfterAll
    static void stopScheduler() {
        SCHEDULER.shutdownNow();
    }

    /** A response: the item, or the failure, and the metadata the client received. */
    record Response(@Nullable Item item, @Nullable StatusRuntimeException failure, @Nullable Metadata headers,
            @Nullable Metadata trailers) {

        Item ok() {
            return Objects.requireNonNull(item, () -> "the call failed: " + failure);
        }

        StatusRuntimeException error() {
            return Objects.requireNonNull(failure, "the call succeeded");
        }

        /** The echoed correlation id: in the headers, or in the trailers of a trailers-only response. */
        @Nullable
        String echoed() {
            if (headers != null && headers.containsKey(CORRELATION)) {
                return headers.get(CORRELATION);
            }
            return trailers == null ? null : trailers.get(CORRELATION);
        }
    }

    static Response call(int port, String id, String... pairs) throws InterruptedException {
        Metadata metadata = new Metadata();
        for (int i = 0; i < pairs.length; i += 2) {
            metadata.put(Metadata.Key.of(pairs[i], Metadata.ASCII_STRING_MARSHALLER), pairs[i + 1]);
        }
        AtomicReference<@Nullable Metadata> headers = new AtomicReference<>();
        AtomicReference<@Nullable Metadata> trailers = new AtomicReference<>();
        ManagedChannel channel = Grpc
                .newChannelBuilderForAddress("localhost", port, InsecureChannelCredentials.create())
                .build();
        try {
            // No client deadline: the server applies its default.
            Item item = CatalogServiceGrpc
                    .newBlockingStub(channel)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata),
                            MetadataUtils.newCaptureMetadataInterceptor(headers, trailers))
                    .getItem(GetItemRequest.newBuilder().setId(id).build());
            return new Response(item, null, headers.get(), trailers.get());
        } catch (StatusRuntimeException e) {
            return new Response(null, e, headers.get(), trailers.get());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private Seen seen(Response response) {
        return recorded.get(response.ok().getId());
    }

    private static ErrorInfo errorInfo(StatusRuntimeException e) {
        com.google.rpc.Status status = Objects.requireNonNull(StatusProto.fromThrowable(e), "status details");
        for (Any any : status.getDetailsList()) {
            if (any.is(ErrorInfo.class)) {
                try {
                    return any.unpack(ErrorInfo.class);
                } catch (InvalidProtocolBufferException ex) {
                    throw new AssertionError(ex);
                }
            }
        }
        throw new AssertionError("no ErrorInfo in " + status);
    }

    @Test
    @Tag("smoke")
    void callThreadMdcHasCorrelationId() throws InterruptedException {
        Response response = call(port, "edge", "x-correlation-id", "c-1");

        Seen seen = seen(response);
        assertThat(seen.mdcCorrelationId()).isEqualTo("c-1");
        assertThat(seen.correlationId()).isEqualTo("c-1");
        assertThat(seen.virtual()).isTrue();
        assertThat(seen.thread()).startsWith("grpc-");
        assertThat(response.echoed()).isEqualTo("c-1");
    }

    @Test
    @Tag("smoke")
    void asyncThreadSeesMdcAndBaggage() throws InterruptedException {
        Seen seen = seen(call(port, "edge", "x-correlation-id", "c-1", "x-tenant", "acme"));

        assertThat(seen.attributes()).containsEntry("tenant", "acme");
        AsyncSeen async = seen.async();
        assertThat(async.thread()).isNotEqualTo(seen.thread()).startsWith("task-");
        assertThat(async.mdcCorrelationId()).isEqualTo("c-1");
        assertThat(async.mdcTenant()).isEqualTo("acme");
        assertThat(async.baggageTenant()).isEqualTo("acme");
        assertThat(async.correlationId()).isEqualTo("c-1");
    }

    @Test
    @Tag("smoke")
    void generatesCorrelationIdWhenAbsent() throws InterruptedException {
        Response response = call(port, "fail");

        assertThat(response.error().getStatus().getCode()).isEqualTo(Status.Code.ABORTED);
        assertThat(response.echoed()).matches(UUID);
        ErrorInfo info = errorInfo(response.error());
        assertThat(info.getReason()).isEqualTo("CONFLICT");
        assertThat(info.getMetadataMap()).containsEntry("correlationId", response.echoed());
    }

    @Test
    @Tag("smoke")
    void invalidCorrelationIdIsReplacedAndSanitized(CapturedOutput output) throws InterruptedException {
        Response response = call(port, "edge", "x-correlation-id", "a".repeat(200));

        assertThat(response.echoed()).matches(UUID);
        assertThat(seen(response).correlationId()).isEqualTo(response.echoed());
        String warn = output
                .getAll()
                .lines()
                .filter(line -> line.contains("Replaced an invalid correlation id"))
                .findFirst()
                .orElseThrow();
        assertThat(warn).contains("WARN").contains("a".repeat(128)).doesNotContain("a".repeat(129));
    }

    @Test
    @Tag("smoke")
    void defaultDeadlineWithoutGrpcDeadline() throws InterruptedException {
        Instant before = Instant.now();
        Seen seen = seen(call(port, "edge"));
        Instant after = Instant.now();

        Instant deadline = Instant.parse(Objects.requireNonNull(seen.deadline()));
        assertThat(deadline)
                .isBetween(before.plusSeconds(10).minusSeconds(1), after.plusSeconds(10).plusSeconds(1));
    }

    @Test
    @Tag("smoke")
    void expiredDeadlineFailsBeforeExecute() throws InterruptedException {
        int before = invocations.count.get();

        Response response = call(port, "count", "x-correlation-id", "c-late", EXPIRE, "1");

        assertThat(response.error().getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED);
        ErrorInfo info = errorInfo(response.error());
        assertThat(info.getReason()).isEqualTo("DEADLINE_EXCEEDED");
        assertThat(info.getMetadataMap()).containsEntry("correlationId", "c-late");
        assertThat(response.echoed()).isEqualTo("c-late");
        assertThat(invocations.count).hasValue(before);
        // Without the expired deadline the same operation runs.
        assertThat(call(port, "count").ok().getName()).isEqualTo("counted");
        assertThat(invocations.count).hasValue(before + 1);
    }

    @Test
    @Tag("smoke")
    void serverObservationIsTaggedWithCorrelationId() throws InterruptedException {
        observations.stopped.clear();

        assertThat(call(port, "edge", "x-correlation-id", "c-obs").ok()).isNotNull();

        Observation.Context server = observations.await("c-obs");
        assertThat(server.getName()).isEqualTo("grpc.server");
        assertThat(server.getHighCardinalityKeyValues()).contains(KeyValue.of("correlation.id", "c-obs"));
    }

    @Nested
    @Tag("smoke")
    @TestPropertySource(properties = "synapse.context.inbound.trusted=false")
    class Untrusted {

        @LocalGrpcServerPort
        private int untrustedPort;

        @Autowired
        private Recorded untrustedRecorded;

        @Test
        void untrustedDropsAllowListedMetadata() throws InterruptedException {
            Response response = call(untrustedPort, "edge", "x-correlation-id", "c-1", "x-tenant", "acme");

            Seen seen = untrustedRecorded.get(response.ok().getId());
            assertThat(seen.attributes()).doesNotContainKey("tenant");
            assertThat(seen.async().baggageTenant()).isNull();
            assertThat(seen.correlationId()).isEqualTo("c-1");
        }
    }

    /**
     * Review Focus 2 on a real server: gRPC reuses executor threads, and on a reused platform thread a failing call
     * must leave no request context and no MDC correlation id behind. The other tests run on virtual threads (a new
     * thread per call), so they can't show it. Not a smoke test: it starts a third context and server.
     */
    @Nested
    @TestPropertySource(properties = {"spring.threads.virtual.enabled=false", SINGLE_THREAD + "=true"})
    class ReusedThread {

        @LocalGrpcServerPort
        private int reusedPort;

        @Autowired
        private Recorded reusedRecorded;

        @Autowired
        private BeforeContextRecorder recorder;

        @Test
        void noMdcOrContextSurvivesAFailingCallOnAReusedThread() throws InterruptedException {
            Seen first = reusedRecorded.get(call(reusedPort, "edge", "x-correlation-id", "c-first").ok().getId());
            // Tracing does put the correlation id in this thread's MDC while a call runs.
            assertThat(first.thread()).isEqualTo(REUSED_THREAD);
            assertThat(first.virtual()).isFalse();
            assertThat(first.mdcCorrelationId()).isEqualTo("c-first");

            Response failed = call(reusedPort, "fail", "x-correlation-id", "c-fail");
            assertThat(failed.error().getStatus().getCode()).isEqualTo(Status.Code.ABORTED);

            Seen next = reusedRecorded.get(call(reusedPort, "edge", "x-correlation-id", "c-next").ok().getId());
            assertThat(next.thread()).isEqualTo(REUSED_THREAD);
            assertThat(next.mdcCorrelationId()).isEqualTo("c-next");
            // The same thread, not just one with the same name: the three calls ran on one reused thread.
            assertThat(next.threadId()).isEqualTo(first.threadId());
            assertThat(recorder.of("c-first"))
                    .isNotEmpty()
                    .allSatisfy(before -> assertThat(before.threadId()).isEqualTo(first.threadId()));

            for (String call : List.of("c-fail", "c-next")) {
                List<Before> seen = recorder.of(call);
                assertThat(seen)
                        .as(call)
                        .extracting(Before::where)
                        .contains("interceptCall", "onMessage", "onHalfClose");
                assertThat(seen).as(call).allSatisfy(before -> {
                    assertThat(before.thread()).as(before.where()).isEqualTo(REUSED_THREAD);
                    assertThat(before.threadId()).as(before.where()).isEqualTo(first.threadId());
                    assertThat(before.contextOpen()).as(before.where()).isFalse();
                    assertThat(before.mdcCorrelationId()).as(before.where()).isNull();
                });
            }
        }
    }

    /** What the operation saw on the call thread, and what its {@code @Async} task saw. */
    record Seen(String thread, long threadId, boolean virtual, String correlationId, @Nullable String mdcCorrelationId,
            Map<String, String> attributes, @Nullable String deadline, AsyncSeen async) {
    }

    /** What an {@code @Async} task saw on its own thread. */
    record AsyncSeen(String thread, @Nullable String correlationId, @Nullable String mdcCorrelationId,
            @Nullable String mdcTenant, @Nullable String baggageTenant) {
    }

    /** What the edge operation saw, by the call's correlation id (returned to the client as the item id). */
    static class Recorded {

        private final Map<String, Seen> seen = new ConcurrentHashMap<>();

        void put(Seen value) {
            seen.put(value.correlationId(), value);
        }

        Seen get(String correlationId) {
            return Objects.requireNonNull(seen.get(correlationId), () -> "nothing recorded for " + correlationId);
        }
    }

    /** Records the stopped server observations. */
    static class ServerObservations implements ObservationHandler<Observation.Context> {

        final BlockingQueue<Observation.Context> stopped = new LinkedBlockingQueue<>();

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        @Override
        public void onStop(Observation.Context context) {
            if ("grpc.server".equals(context.getName())) {
                stopped.add(context);
            }
        }

        /** Waits for the server observation of the call with this correlation id. */
        Observation.Context await(String correlationId) throws InterruptedException {
            long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < end) {
                Observation.Context context = stopped.poll(100, TimeUnit.MILLISECONDS);
                if (context != null) {
                    KeyValue key = context.getHighCardinalityKeyValue("correlation.id");
                    if (key != null && correlationId.equals(key.getValue())) {
                        return context;
                    }
                }
            }
            throw new AssertionError("no server observation with correlation.id=" + correlationId + " was stopped");
        }
    }

    /**
     * Gives calls that carry {@value #EXPIRE} an expired gRPC deadline. It runs after Boot's exception handling (order
     * 1) and before the context interceptor (order 100), as a caller's deadline that expired in transit would.
     */
    static class ExpiringInterceptor implements ServerInterceptor {

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
                ServerCallHandler<ReqT, RespT> next) {
            if (!headers.containsKey(Metadata.Key.of(EXPIRE, Metadata.ASCII_STRING_MARSHALLER))) {
                return next.startCall(call, headers);
            }
            Context expired = Context.current().withDeadline(Deadline.after(-1, TimeUnit.SECONDS), SCHEDULER);
            return Contexts.interceptCall(expired, call, headers, next);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableAsync
    static class TestApplication {

        @Bean
        AsyncRecorder asyncRecorder(Tracer tracer) {
            return new AsyncRecorder(tracer);
        }

        @Bean
        EdgeOperation edgeOperation(AsyncRecorder recorder) {
            return new EdgeOperation(recorder);
        }

        @Bean
        FailingOperation failingOperation() {
            return new FailingOperation();
        }

        @Bean
        Invocations invocations() {
            return new Invocations();
        }

        @Bean
        CountingOperation countingOperation(Invocations invocations) {
            return new CountingOperation(invocations);
        }

        @Bean
        Recorded recorded() {
            return new Recorded();
        }

        @Bean
        EdgeService edgeService(EdgeOperation edge, FailingOperation failing, CountingOperation counting,
                Recorded recorded) {
            return new EdgeService(edge, failing, counting, recorded);
        }

        @Bean
        ServerObservations serverObservations() {
            return new ServerObservations();
        }

        @Bean
        @Order(50)
        @GlobalServerInterceptor
        ExpiringInterceptor expiringInterceptor() {
            return new ExpiringInterceptor();
        }

        @Bean
        @Order(60)
        @GlobalServerInterceptor
        BeforeContextRecorder beforeContextRecorder() {
            return new BeforeContextRecorder();
        }

        /** One platform thread for every gRPC call, so a call runs on the thread the previous one used. */
        @Bean
        @ConditionalOnProperty(name = SINGLE_THREAD, havingValue = "true")
        SingleThreadExecutorProvider singleThreadExecutorProvider() {
            return new SingleThreadExecutorProvider();
        }
    }

    /** Runs every gRPC call on one platform thread; closed with the context. */
    static class SingleThreadExecutorProvider implements GrpcServerExecutorProvider, AutoCloseable {

        private final ExecutorService executor = Executors
                .newSingleThreadExecutor(runnable -> Thread.ofPlatform().name(REUSED_THREAD).unstarted(runnable));

        @Override
        public Executor getExecutor() {
            return executor;
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }

    /** What a callback of {@link BeforeContextRecorder} saw on its thread. */
    record Before(String where, @Nullable String candidate, String thread, long threadId, boolean contextOpen,
            @Nullable String mdcCorrelationId) {
    }

    /**
     * Sits after Boot's exception handling (order 1) and before the context interceptor (order 100): in
     * {@code interceptCall}, {@code onMessage} and {@code onHalfClose} it records, before the context interceptor's
     * scope opens, whether the thread holds a request context and an MDC correlation id.
     */
    static class BeforeContextRecorder implements ServerInterceptor {

        final List<Before> before = new CopyOnWriteArrayList<>();

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
                ServerCallHandler<ReqT, RespT> next) {
            record("interceptCall");
            return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(
                    next.startCall(call, headers)) {
                @Override
                public void onMessage(ReqT message) {
                    record("onMessage");
                    super.onMessage(message);
                }

                @Override
                public void onHalfClose() {
                    record("onHalfClose");
                    super.onHalfClose();
                }
            };
        }

        private void record(String where) {
            GrpcCallState state = GrpcCallState.current();
            before
                    .add(new Before(where, state == null ? null : state.correlationCandidate(),
                            Thread.currentThread().getName(), Thread.currentThread().threadId(),
                            RequestContext.find().isPresent(),
                            MDC.get("correlationId")));
        }

        List<Before> of(String correlationCandidate) {
            return before.stream().filter(seen -> correlationCandidate.equals(seen.candidate())).toList();
        }
    }

    /** A helper whose {@code @Async} method records what its thread sees. */
    static class AsyncRecorder {

        private final Tracer tracer;

        AsyncRecorder(Tracer tracer) {
            this.tracer = tracer;
        }

        @Async
        public CompletableFuture<AsyncSeen> record() {
            Baggage tenant = tracer.getBaggage("tenant");
            return CompletableFuture
                    .completedFuture(new AsyncSeen(Thread.currentThread().getName(),
                            RequestContext.find().map(RequestContext::correlationId).orElse(null),
                            MDC.get("correlationId"), MDC.get("tenant"), tenant == null ? null : tenant.get()));
        }
    }

    /** A query that records its request context, MDC and thread, and what an {@code @Async} task sees. */
    static class EdgeOperation implements QueryOperation<String, Seen> {

        private final AsyncRecorder recorder;

        EdgeOperation(AsyncRecorder recorder) {
            this.recorder = recorder;
        }

        @Override
        public Seen execute(String input) {
            RequestContext context = RequestContext.current();
            AsyncSeen async = recorder.record().join();
            return new Seen(Thread.currentThread().getName(), Thread.currentThread().threadId(),
                    Thread.currentThread().isVirtual(),
                    context.correlationId(), MDC.get("correlationId"), context.attributes(),
                    context.deadline().map(Instant::toString).orElse(null), async);
        }
    }

    /** A query that always fails with a conflict. */
    static class FailingOperation implements QueryOperation<String, String> {

        @Override
        public String execute(String input) {
            throw new SynapseException(SynapseErrors.CONFLICT);
        }
    }

    /** How many times the counting operation's body ran (the operation bean is a pipeline proxy). */
    static class Invocations {

        final AtomicInteger count = new AtomicInteger();
    }

    /** A query that counts the times its body runs. */
    static class CountingOperation implements QueryOperation<String, String> {

        private final Invocations invocations;

        CountingOperation(Invocations invocations) {
            this.invocations = invocations;
        }

        @Override
        public String execute(String input) {
            invocations.count.incrementAndGet();
            return "counted";
        }
    }

    /**
     * A hand-written service (the escape hatch) whose {@code GetItem} calls an operation chosen by the request id:
     * {@code edge}, {@code fail} or {@code count}.
     */
    static class EdgeService extends CatalogServiceGrpc.CatalogServiceImplBase {

        private final EdgeOperation edge;

        private final FailingOperation failing;

        private final CountingOperation counting;

        private final Recorded recorded;

        EdgeService(EdgeOperation edge, FailingOperation failing, CountingOperation counting, Recorded recorded) {
            this.edge = edge;
            this.failing = failing;
            this.counting = counting;
            this.recorded = recorded;
        }

        @Override
        public void getItem(GetItemRequest request, StreamObserver<Item> responseObserver) {
            Item item = switch (request.getId()) {
                case "edge" -> {
                    Seen seen = edge.execute(request.getId());
                    recorded.put(seen);
                    yield Item.newBuilder().setId(seen.correlationId()).build();
                }
                case "fail" -> Item.newBuilder().setName(failing.execute(request.getId())).build();
                case "count" -> Item.newBuilder().setName(counting.execute(request.getId())).build();
                default -> throw new IllegalArgumentException("unknown probe " + request.getId());
            };
            responseObserver.onNext(item);
            responseObserver.onCompleted();
        }
    }
}
