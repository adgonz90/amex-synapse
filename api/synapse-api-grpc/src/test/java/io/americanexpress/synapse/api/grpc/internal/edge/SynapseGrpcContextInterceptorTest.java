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

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.rpc.ErrorInfo;
import com.google.rpc.RetryInfo;
import io.grpc.BindableService;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Deadline;
import io.grpc.ForwardingServerCallListener;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.protobuf.StatusProto;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.binder.grpc.ObservationGrpcServerInterceptor;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.support.StaticMessageSource;

import io.americanexpress.synapse.api.grpc.fixture.proto.CatalogServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.GetItemRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.Item;
import io.americanexpress.synapse.api.grpc.internal.error.GrpcErrorStatuses;
import io.americanexpress.synapse.api.grpc.internal.error.GrpcStatusWriter;
import io.americanexpress.synapse.core.autoconfigure.SynapseErrorProperties;
import io.americanexpress.synapse.core.context.RequestContext;
import io.americanexpress.synapse.core.error.ErrorMessages;
import io.americanexpress.synapse.core.internal.context.ContextBinder;
import io.americanexpress.synapse.core.internal.context.CorrelationIdPolicy;
import io.americanexpress.synapse.test.time.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SynapseGrpcContextInterceptorTest {

    private static final Metadata.Key<String> CORRELATION = key("x-correlation-id");

    private static final Set<String> SYNAPSE_FIELDS = new LinkedHashSet<>(List.of("correlationId", "tenant"));

    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final MutableClock clock = MutableClock.at("2026-01-01T00:00:00Z");

    private final SimpleTracer tracer = new SimpleTracer();

    /** Opens a span for each call's server observation, as Boot's tracing does; records the stopped observations. */
    private final ObservationRegistry observations = ObservationRegistry.create();

    private final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();

    private final ContextBinder binder = new ContextBinder(tracer, new CorrelationIdPolicy(128), true, 16, 4096);

    SynapseGrpcContextInterceptorTest() {
        observations
                .observationConfig()
                .observationHandler(new DefaultTracingObservationHandler(tracer))
                .observationHandler(new ObservationHandler<>() {
                    @Override
                    public boolean supportsContext(Observation.Context context) {
                        return true;
                    }

                    @Override
                    public void onStop(Observation.Context context) {
                        stopped.add(context);
                    }
                });
    }

    private final GrpcStatusWriter writer = new GrpcStatusWriter(new GrpcErrorStatuses(Map.of(), Map.of()),
            new ErrorMessages(new StaticMessageSource()), new SynapseErrorProperties(false, null), "synapse");

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final ProbeService service = new ProbeService();

    private final Recorder recorder = new Recorder();

    private final AtomicReference<@Nullable Metadata> responseHeaders = new AtomicReference<>();

    private final AtomicReference<@Nullable Metadata> responseTrailers = new AtomicReference<>();

    private final List<ExecutorService> executors = new ArrayList<>();

    private @Nullable Server server;

    private @Nullable ManagedChannel channel;

    @AfterEach
    void stop() throws InterruptedException {
        service.releaseAll();
        if (channel != null) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        executors.forEach(ExecutorService::shutdownNow);
        scheduler.shutdownNow();
    }

    private static Metadata.Key<String> key(String name) {
        return Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER);
    }

    private static Metadata metadata(String... pairs) {
        Metadata metadata = new Metadata();
        for (int i = 0; i < pairs.length; i += 2) {
            metadata.put(key(pairs[i]), pairs[i + 1]);
        }
        return metadata;
    }

    private static GetItemRequest id(String id) {
        return GetItemRequest.newBuilder().setId(id).build();
    }

    private SynapseGrpcContextInterceptor interceptor(int maxConcurrentCalls) {
        return interceptor(binder, maxConcurrentCalls);
    }

    private SynapseGrpcContextInterceptor interceptor(ContextBinder contextBinder, int maxConcurrentCalls) {
        return new SynapseGrpcContextInterceptor(contextBinder, Map.of("tenant", key("x-tenant")),
                new GrpcDeadlinePolicy(Duration.ofSeconds(10), Duration.ofSeconds(30), clock),
                new GrpcConcurrencyLimit(maxConcurrentCalls), writer, observations);
    }

    /**
     * Starts an in-process server whose services sit behind the edge, Micrometer's server observation interceptor
     * (Boot's, order 0), the recorder, the {@code between} interceptors, the context interceptor and the {@code inner}
     * interceptors, in that order. Boot's exception handling interceptor would sit where the recorder is.
     */
    private void start(SynapseGrpcContextInterceptor context, @Nullable ExecutorService executor,
            List<ServerInterceptor> between, List<ServerInterceptor> inner) throws IOException {
        SynapseGrpcEdgeInterceptor edge = new SynapseGrpcEdgeInterceptor(
                new MetadataMasking(true, SYNAPSE_FIELDS, Set.of()), CORRELATION);
        List<ServerInterceptor> chain = new ArrayList<>();
        chain.add(edge);
        chain.add(new ObservationGrpcServerInterceptor(observations));
        chain.add(recorder);
        chain.addAll(between);
        chain.add(context);
        chain.addAll(inner);
        String name = InProcessServerBuilder.generateName();
        InProcessServerBuilder builder = InProcessServerBuilder.forName(name);
        if (executor != null) {
            executors.add(executor);
            builder.executor(executor);
        }
        for (BindableService bindable : List.of(service, new HealthStatusManager().getHealthService())) {
            builder.addService(ServerInterceptors.interceptForward(bindable.bindService(), chain));
        }
        server = builder.build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }

    private void start(SynapseGrpcContextInterceptor context) throws IOException {
        start(context, null, List.of(), List.of());
    }

    private ManagedChannel channel() {
        return Objects.requireNonNull(channel, "channel");
    }

    private CatalogServiceGrpc.CatalogServiceBlockingStub stub(String... pairs) {
        return CatalogServiceGrpc
                .newBlockingStub(channel())
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata(pairs)),
                        MetadataUtils.newCaptureMetadataInterceptor(responseHeaders, responseTrailers));
    }

    private Seen only() {
        assertThat(service.seen).hasSize(1);
        return service.seen.getFirst();
    }

    private static <T extends Message> T detail(StatusRuntimeException e, Class<T> type) {
        com.google.rpc.Status status = Objects.requireNonNull(StatusProto.fromThrowable(e), "status details");
        for (Any any : status.getDetailsList()) {
            if (any.is(type)) {
                try {
                    return any.unpack(type);
                } catch (InvalidProtocolBufferException ex) {
                    throw new AssertionError(ex);
                }
            }
        }
        throw new AssertionError("no " + type.getSimpleName() + " in " + status);
    }

    @Test
    void opensContextWithMetadataCorrelationId() throws IOException {
        start(interceptor(10));

        stub("x-correlation-id", "c-1").getItem(id("ok"));

        Seen seen = only();
        assertThat(Objects.requireNonNull(seen.context(), "context").correlationId()).isEqualTo("c-1");
        assertThat(Objects.requireNonNull(seen.state(), "state").correlationId()).isEqualTo("c-1");
        assertThat(Objects.requireNonNull(responseHeaders.get(), "headers").get(CORRELATION)).isEqualTo("c-1");
    }

    @Test
    void generatesTheIdWhenAbsentOrInvalid() throws IOException {
        start(interceptor(10));

        stub().getItem(id("ok"));
        stub("x-correlation-id", "a".repeat(200)).getItem(id("ok"));

        assertThat(service.seen).hasSize(2).allSatisfy(seen -> {
            String id = Objects.requireNonNull(seen.context(), "context").correlationId();
            assertThat(id).matches(UUID);
            assertThat(Objects.requireNonNull(seen.state(), "state").correlationId()).isEqualTo(id);
        });
        assertThat(Objects.requireNonNull(responseHeaders.get(), "headers").get(CORRELATION))
                .isEqualTo(Objects.requireNonNull(service.seen.get(1).context()).correlationId());
    }

    @Test
    void attributeFromMappedKeyThenBaggage() throws IOException {
        start(interceptor(10));

        stub("x-tenant", "acme", "baggage", "tenant=t1").getItem(id("ok"));
        stub("baggage", "tenant=t1").getItem(id("ok"));
        stub("x-tenant", "", "baggage", "tenant=t2").getItem(id("ok"));

        assertThat(service.seen)
                .extracting(seen -> Objects.requireNonNull(seen.context(), "context").attributes())
                .containsExactly(Map.of("tenant", "acme"), Map.of("tenant", "t1"), Map.of("tenant", "t2"));
    }

    @Test
    void attributesAreDroppedWhenInboundContextIsNotTrusted() throws IOException {
        ContextBinder untrusted = new ContextBinder(tracer, new CorrelationIdPolicy(128), false, 16, 4096);
        start(interceptor(untrusted, 10));

        stub("x-correlation-id", "c-1", "x-tenant", "acme").getItem(id("ok"));

        RequestContext context = Objects.requireNonNull(only().context(), "context");
        assertThat(context.attributes()).isEmpty();
        assertThat(context.correlationId()).isEqualTo("c-1");
    }

    @Test
    void localeOnlyWithAcceptLanguage() throws IOException {
        start(interceptor(10));

        stub().getItem(id("ok"));
        stub("accept-language", "fr-CA,en;q=0.5").getItem(id("ok"));
        stub("accept-language", "en;q=0.5, de-CH").getItem(id("ok"));
        stub("accept-language", "not a language range;;").getItem(id("ok"));

        assertThat(service.seen)
                .extracting(seen -> Objects.requireNonNull(seen.context(), "context").locale().orElse(null))
                .containsExactly(null, Locale.CANADA_FRENCH, Locale.forLanguageTag("de-CH"), null);
        assertThat(service.seen)
                .extracting(seen -> Objects.requireNonNull(seen.state(), "state").locale())
                .containsExactly(null, Locale.CANADA_FRENCH, Locale.forLanguageTag("de-CH"), null);
    }

    @Test
    void deadlineReachesTheRequestContext() throws IOException {
        start(interceptor(10));
        GrpcDeadlinePolicyTest.FakeTicker ticker = new GrpcDeadlinePolicyTest.FakeTicker();

        stub().getItem(id("ok"));
        recorder.deadline.set(Deadline.after(2, TimeUnit.SECONDS, ticker));
        stub().getItem(id("ok"));
        // Expired: the pipeline, not this interceptor, fails the call (GrpcEdgeIT.expiredDeadlineFailsBeforeExecute).
        recorder.deadline.set(Deadline.after(-1, TimeUnit.MILLISECONDS, ticker));
        stub().getItem(id("ok"));

        assertThat(service.seen)
                .extracting(seen -> Objects.requireNonNull(seen.context(), "context").deadline().orElseThrow())
                .containsExactly(NOW.plusSeconds(10), NOW.plusSeconds(2), NOW.minusMillis(1));
    }

    @Test
    void contextIsClearedAfterFailingCall() throws Exception {
        ExecutorService single = Executors.newSingleThreadExecutor();
        start(interceptor(10), single, List.of(), List.of());

        assertThatThrownBy(() -> stub("x-correlation-id", "c-fail").getItem(id("fail")))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNKNOWN));
        assertThat(recorder.ended.tryAcquire(10, TimeUnit.SECONDS)).isTrue();
        // The thread that ran the failing call holds no context once the call is over.
        assertThat(single.submit(() -> RequestContext.find().isPresent()).get(10, TimeUnit.SECONDS)).isFalse();
        assertThat(single.submit(() -> MDC.get("correlationId")).get(10, TimeUnit.SECONDS)).isNull();

        stub("x-correlation-id", "c-next").getItem(id("ok"));

        assertThat(service.seen).hasSize(2);
        Seen failed = service.seen.get(0);
        Seen next = service.seen.get(1);
        assertThat(Objects.requireNonNull(failed.context(), "failed context").correlationId()).isEqualTo("c-fail");
        assertThat(next.thread()).isEqualTo(failed.thread());
        assertThat(Objects.requireNonNull(next.context(), "next context").correlationId()).isEqualTo("c-next");
        // Before its own scope opens, the next call on the same thread sees nothing of the failed call.
        assertThat(recorder.before)
                .filteredOn(before -> "c-next".equals(before.candidate()))
                .extracting(Before::where)
                .contains("interceptCall", "onMessage", "onHalfClose");
        assertThat(recorder.before).filteredOn(before -> "c-next".equals(before.candidate())).allSatisfy(before -> {
            assertThat(before.contextOpen()).as(before.where()).isFalse();
            assertThat(before.mdcCorrelationId()).as(before.where()).isNull();
        });
    }

    @Test
    void fullLimitAnswersUnavailableWithRetryInfo() throws Exception {
        start(interceptor(1));
        CompletableFuture<Item> first = CompletableFuture
                .supplyAsync(() -> stub("x-correlation-id", "c-1").getItem(id("block")));
        assertThat(service.entered.tryAcquire(10, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> stub("x-correlation-id", "c-2").getItem(id("ok")))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
                    ErrorInfo info = detail(e, ErrorInfo.class);
                    assertThat(info.getReason()).isEqualTo("UNAVAILABLE");
                    assertThat(info.getMetadataMap()).containsEntry("correlationId", "c-2");
                    RetryInfo retry = detail(e, RetryInfo.class);
                    assertThat(retry.getRetryDelay().getSeconds()).isEqualTo(1);
                    assertThat(retry.getRetryDelay().getNanos()).isZero();
                    Metadata trailers = Objects.requireNonNull(e.getTrailers(), "trailers");
                    assertThat(trailers.getAll(CORRELATION)).containsExactly("c-2");
                });
        assertThat(service.invocations).hasValue(1);

        service.releaseBlocked();
        assertThat(first.get(10, TimeUnit.SECONDS).getId()).isEqualTo("block");
    }

    @Test
    void rejectionsNeverReleaseAPermit() throws Exception {
        start(interceptor(1));
        CompletableFuture<Item> first = CompletableFuture.supplyAsync(() -> stub().getItem(id("block")));
        assertThat(service.entered.tryAcquire(10, TimeUnit.SECONDS)).isTrue();
        int rejections = 20;
        for (int i = 0; i < rejections; i++) {
            assertThatThrownBy(() -> stub().getItem(id("ok")))
                    .isInstanceOfSatisfying(StatusRuntimeException.class,
                            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
        }

        service.releaseBlocked();
        assertThat(first.get(10, TimeUnit.SECONDS).getId()).isEqualTo("block");
        // Every call has ended, the rejected ones included (their no-op listeners still complete).
        assertThat(recorder.ended.tryAcquire(rejections + 1, 10, TimeUnit.SECONDS)).isTrue();

        // The one freed permit admits exactly one call: the rejections gave none back.
        assertThatCall51IsAdmittedAndHoldsTheOnlyPermit();
        assertThat(service.invocations).hasValue(2);
    }

    @Test
    void uncountedServicesAreAnsweredAtTheLimit() throws Exception {
        start(interceptor(1));
        CompletableFuture<Item> first = CompletableFuture.supplyAsync(() -> stub().getItem(id("block")));
        assertThat(service.entered.tryAcquire(10, TimeUnit.SECONDS)).isTrue();

        HealthCheckResponse health = HealthGrpc
                .newBlockingStub(channel())
                .check(HealthCheckRequest.getDefaultInstance());

        assertThat(health.getStatus()).isEqualTo(HealthCheckResponse.ServingStatus.SERVING);
        service.releaseAll();
        assertThat(first.get(10, TimeUnit.SECONDS).getId()).isEqualTo("block");
    }

    @Test
    void permitIsReleasedWhenTheClientCancels() throws Exception {
        start(interceptor(1));
        CatalogServiceGrpc.CatalogServiceStub async = CatalogServiceGrpc.newStub(channel());

        for (int i = 0; i < 50; i++) {
            Context.CancellableContext cancellable = Context.current().withCancellation();
            cancellable.run(() -> async.getItem(id("block"), new IgnoringObserver<>()));
            assertThat(service.entered.tryAcquire(10, TimeUnit.SECONDS)).as("call %d started", i).isTrue();
            cancellable.cancel(null);
            // The blocked operation returns once the call's gRPC context is cancelled; onCancel follows it.
            assertThat(recorder.ended.tryAcquire(10, TimeUnit.SECONDS)).as("call %d ended", i).isTrue();
        }

        assertThat(recorder.endings).hasSize(50).containsOnly("onCancel");
        assertThat(stub().getItem(id("ok")).getId()).isEqualTo("ok");
        assertThat(service.invocations).hasValue(51);
    }

    @Test
    void permitIsReleasedWhenTheHandlerThrows() throws Exception {
        start(interceptor(1));

        for (int i = 0; i < 50; i++) {
            assertThatThrownBy(() -> stub().getItem(id("fail")))
                    .isInstanceOfSatisfying(StatusRuntimeException.class,
                            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNKNOWN));
            assertThat(recorder.ended.tryAcquire(10, TimeUnit.SECONDS)).as("call %d ended", i).isTrue();
        }

        assertThatCall51IsAdmittedAndHoldsTheOnlyPermit();
    }

    @Test
    void permitIsReleasedWhenStartCallThrows() throws Exception {
        ServerInterceptor failingStart = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                    Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                if (headers.containsKey(key("x-fail-start"))) {
                    throw new IllegalStateException("start failed");
                }
                return next.startCall(call, headers);
            }
        };
        start(interceptor(1), null, List.of(), List.of(failingStart));

        for (int i = 0; i < 50; i++) {
            assertThatThrownBy(() -> stub("x-fail-start", "1").getItem(id("ok")))
                    .isInstanceOf(StatusRuntimeException.class);
        }

        assertThatCall51IsAdmittedAndHoldsTheOnlyPermit();
    }

    /** A blocked call is admitted and holds the one permit: a second call is rejected, so none was released twice. */
    private void assertThatCall51IsAdmittedAndHoldsTheOnlyPermit() throws Exception {
        CompletableFuture<Item> admitted = CompletableFuture.supplyAsync(() -> stub().getItem(id("block")));
        assertThat(service.entered.tryAcquire(10, TimeUnit.SECONDS)).as("call 51 started").isTrue();
        assertThatThrownBy(() -> stub().getItem(id("ok")))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE));
        service.releaseBlocked();
        assertThat(admitted.get(10, TimeUnit.SECONDS).getId()).isEqualTo("block");
    }

    @Test
    void handWrittenStatusAtCloseKeepsOnlyItsCode() throws Exception {
        start(interceptor(10));

        assertThatThrownBy(() -> stub("x-correlation-id", "c-own").getItem(id("status")))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
                    assertThat(e.getStatus().getDescription()).isNullOrEmpty();
                    ErrorInfo info = detail(e, ErrorInfo.class);
                    assertThat(info.getReason()).isEqualTo("FORBIDDEN");
                    assertThat(info.getDomain()).isEqualTo("synapse");
                    assertThat(info.getMetadataMap()).containsEntry("correlationId", "c-own");
                    Metadata trailers = Objects.requireNonNull(e.getTrailers(), "trailers");
                    assertThat(trailers.containsKey(key("x-internal"))).isFalse();
                    assertThat(trailers.getAll(CORRELATION)).containsExactly("c-own");
                });
    }

    @Test
    void anInnerInterceptorsStatusKeepsOnlyItsCodeAndAnOuterOneKeepsItsDescription() throws Exception {
        // Pin (R-11, review I1): an interceptor inside Synapse's closes through the same call as the service.
        start(interceptor(10), null, List.of(closing("x-outer-check")), List.of(closing("x-inner-check")));

        assertThatThrownBy(() -> stub("x-inner-check", "1").getItem(id("ok")))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
                    assertThat(e.getStatus().getDescription()).isNullOrEmpty();
                    assertThat(detail(e, ErrorInfo.class).getReason()).isEqualTo("UNAUTHENTICATED");
                });
        assertThatThrownBy(() -> stub("x-outer-check", "1").getItem(id("ok")))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
                    assertThat(e.getStatus().getDescription()).isEqualTo("missing API key");
                });
    }

    /** An interceptor that closes the call with {@code UNAUTHENTICATED} when the request has the given metadata. */
    private static ServerInterceptor closing(String trigger) {
        return new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                    Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                if (!headers.containsKey(key(trigger))) {
                    return next.startCall(call, headers);
                }
                call.close(Status.UNAUTHENTICATED.withDescription("missing API key"), new Metadata());
                return new ServerCall.Listener<>() {
                };
            }
        };
    }

    @Test
    void unknownWithAPlainCauseIsLeftForTheExceptionHandlers() throws Exception {
        start(interceptor(10));

        // Pin (R-11): Spring gRPC's exception handling call, which sits outside, hands the cause to the handlers (here
        // none runs), so the rewrite leaves this status as it is.
        assertThatThrownBy(() -> stub().getItem(id("plain")))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNKNOWN);
                    assertThat(Objects.requireNonNull(StatusProto.fromThrowable(e)).getDetailsList()).isEmpty();
                });
    }

    @Test
    void unknownWithAPlainCauseLeftForTheHandlersDropsTheServicesTrailers() throws Exception {
        start(interceptor(10));

        // Spring gRPC's exception handling call merges the handler's trailers into these, keeping them.
        assertThatThrownBy(() -> stub().getItem(id("plain-trailers")))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNKNOWN);
                    Metadata trailers = Objects.requireNonNull(e.getTrailers(), "trailers");
                    assertThat(trailers.containsKey(key("x-internal"))).isFalse();
                });
    }

    @Test
    void unknownCausedByAStatusIsRewritten() throws Exception {
        start(interceptor(10));

        // Spring gRPC's handlers would get only the cause, a status that Synapse didn't write.
        assertThatThrownBy(() -> stub().getItem(id("status-cause")))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNKNOWN);
                    assertThat(e.getStatus().getDescription()).isNullOrEmpty();
                    assertThat(detail(e, ErrorInfo.class).getReason()).isEqualTo("INTERNAL");
                });
    }

    @Test
    void unknownCausedByACheckedStatusIsRewrittenToo() throws Exception {
        // Pin (R-11): the checked StatusException branch of isForTheHandlers.
        start(interceptor(10));

        assertThatThrownBy(() -> stub().getItem(id("checked-status-cause")))
                .isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
                    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNKNOWN);
                    assertThat(e.getStatus().getDescription()).isNullOrEmpty();
                    assertThat(detail(e, ErrorInfo.class).getReason()).isEqualTo("INTERNAL");
                });
    }

    @Test
    void onlyTheServerObservationIsTagged() throws Exception {
        // An interceptor between Boot's observation interceptor and this one opens its own observation.
        ServerInterceptor ownObservation = new ServerInterceptor() {
            @Override
            @SuppressWarnings("try") // The scope only has to be open while the call starts.
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                    Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                Observation observation = Observation.start("custom", observations);
                try (Observation.Scope ignored = observation.openScope()) {
                    return next.startCall(call, headers);
                } finally {
                    observation.stop();
                }
            }
        };
        start(interceptor(10), null, List.of(ownObservation), List.of());

        stub("x-correlation-id", "c-own").getItem(id("ok"));

        assertThat(recorder.ended.tryAcquire(10, TimeUnit.SECONDS)).isTrue();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (stopped.size() < 2 && System.nanoTime() < end) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat(stopped)
                .filteredOn(context -> "custom".equals(context.getName()))
                .singleElement()
                .satisfies(
                        context -> assertThat(context.getHighCardinalityKeyValue("correlation.id")).isNull());
        assertThat(Objects.requireNonNull(only().context(), "context").correlationId()).isEqualTo("c-own");
    }

    @Test
    void serverObservationIsTaggedWithTheResolvedId() throws Exception {
        start(interceptor(10));

        stub("x-correlation-id", "c-obs").getItem(id("ok"));

        assertThat(recorder.ended.tryAcquire(10, TimeUnit.SECONDS)).isTrue();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (stopped.isEmpty() && System.nanoTime() < end) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat(stopped).singleElement().satisfies(context -> {
            assertThat(context.getName()).isEqualTo("grpc.server");
            assertThat(context.getHighCardinalityKeyValues()).contains(KeyValue.of("correlation.id", "c-obs"));
        });
    }

    /** What the service saw. */
    record Seen(@Nullable RequestContext context, @Nullable GrpcCallState state, String thread) {
    }

    /** What a callback of the recorder saw before the context interceptor's own scope opened. */
    record Before(String where, @Nullable String candidate, boolean contextOpen, @Nullable String mdcCorrelationId) {
    }

    /**
     * A {@code CatalogService} whose {@code GetItem} records the request context, answers {@code ok}, throws for
     * {@code fail}, and for {@code block} waits until it is released or the call's gRPC context is cancelled. It
     * completes with {@code onError} for {@code status} (a status with a description and a trailer of its own),
     * {@code plain} (an exception that isn't a status), {@code plain-trailers} ({@code UNKNOWN} caused by such an
     * exception, with a trailer of its own), {@code status-cause} ({@code UNKNOWN} caused by a status runtime
     * exception) and {@code checked-status-cause} ({@code UNKNOWN} caused by a checked status exception).
     */
    static final class ProbeService extends CatalogServiceGrpc.CatalogServiceImplBase {

        final List<Seen> seen = new CopyOnWriteArrayList<>();

        final AtomicInteger invocations = new AtomicInteger();

        final Semaphore entered = new Semaphore(0);

        private final List<CountDownLatch> blocked = new CopyOnWriteArrayList<>();

        private volatile boolean released;

        /** Releases the calls blocked now; later calls block again. */
        void releaseBlocked() {
            blocked.forEach(CountDownLatch::countDown);
            blocked.clear();
        }

        /** Releases the blocked calls, and every later one. */
        void releaseAll() {
            released = true;
            blocked.forEach(CountDownLatch::countDown);
        }

        @Override
        public void getItem(GetItemRequest request, StreamObserver<Item> responseObserver) {
            invocations.incrementAndGet();
            seen
                    .add(new Seen(RequestContext.find().orElse(null), GrpcCallState.current(),
                            Thread.currentThread().getName()));
            if ("fail".equals(request.getId())) {
                throw new IllegalStateException("operation failed");
            }
            @Nullable
            Throwable failure = switch (request.getId()) {
                case "status" -> Status.PERMISSION_DENIED
                        .withDescription("secret")
                        .asRuntimeException(metadata("x-internal", "1"));
                case "plain" -> new IllegalStateException("plain");
                case "plain-trailers" -> Status.UNKNOWN
                        .withCause(new IllegalStateException("plain"))
                        .asRuntimeException(metadata("x-internal", "1"));
                case "checked-status-cause" -> Status.UNKNOWN
                        .withCause(Status.NOT_FOUND.withDescription("row 7").asException())
                        .asRuntimeException();
                case "status-cause" -> Status.UNKNOWN
                        .withCause(Status.NOT_FOUND.withDescription("row 7").asRuntimeException())
                        .asRuntimeException();
                default -> null;
            };
            if (failure != null) {
                responseObserver.onError(failure);
                return;
            }
            if ("block".equals(request.getId())) {
                Context context = Context.current();
                CountDownLatch done = new CountDownLatch(1);
                blocked.add(done);
                // grpc-java delivers onCancel only after this method returns, so it also waits for cancellation.
                context.addListener(ignored -> done.countDown(), Runnable::run);
                if (released) {
                    done.countDown();
                }
                entered.release();
                await(done);
                if (context.isCancelled()) {
                    return;
                }
            }
            responseObserver.onNext(Item.newBuilder().setId(request.getId()).build());
            responseObserver.onCompleted();
        }

        private static void await(CountDownLatch done) {
            try {
                if (!done.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the blocked call was never released");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", ex);
            }
        }
    }

    /**
     * Sits just outside the context interceptor. Each callback records what its thread holds before the context
     * interceptor's scope opens; the end of each call ({@code onComplete} or {@code onCancel}) is signalled after the
     * context interceptor's listener has handled it. It can also set a gRPC deadline on the call's context.
     */
    final class Recorder implements ServerInterceptor {

        final List<Before> before = new CopyOnWriteArrayList<>();

        final List<String> endings = new CopyOnWriteArrayList<>();

        final Semaphore ended = new Semaphore(0);

        final AtomicReference<@Nullable Deadline> deadline = new AtomicReference<>();

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
                ServerCallHandler<ReqT, RespT> next) {
            record("interceptCall");
            Deadline callDeadline = deadline.get();
            Context context = callDeadline == null
                    ? Context.current()
                    : Context.current().withDeadline(callDeadline, scheduler);
            return Contexts.interceptCall(context, call, headers, (inner, innerHeaders) -> {
                ServerCall.Listener<ReqT> delegate = next.startCall(inner, innerHeaders);
                return new ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(delegate) {
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

                    @Override
                    public void onCancel() {
                        try {
                            super.onCancel();
                        } finally {
                            end("onCancel");
                        }
                    }

                    @Override
                    public void onComplete() {
                        try {
                            super.onComplete();
                        } finally {
                            end("onComplete");
                        }
                    }
                };
            });
        }

        private void record(String where) {
            GrpcCallState state = GrpcCallState.current();
            before
                    .add(new Before(where, state == null ? null : state.correlationCandidate(),
                            RequestContext.find().isPresent(), MDC.get("correlationId")));
        }

        private void end(String how) {
            endings.add(how);
            ended.release();
        }
    }

    /** Ignores the response of a call that the test cancels. */
    static final class IgnoringObserver<T> implements StreamObserver<T> {

        @Override
        public void onNext(T value) {
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
        }
    }
}
