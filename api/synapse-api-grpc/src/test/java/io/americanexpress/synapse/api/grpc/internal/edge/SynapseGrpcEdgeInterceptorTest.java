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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.google.protobuf.Empty;
import io.grpc.ForwardingServerCall;
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
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.api.grpc.fixture.proto.CatalogServiceGrpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SynapseGrpcEdgeInterceptorTest {

    private static final Metadata.Key<String> CORRELATION = key("x-correlation-id");

    private static final Set<String> SYNAPSE_FIELDS = new LinkedHashSet<>(List.of("correlationId", "tenant"));

    private final List<String> seen = new CopyOnWriteArrayList<>();

    private final List<GrpcCallState> states = new CopyOnWriteArrayList<>();

    private final AtomicReference<@Nullable Metadata> innerMetadata = new AtomicReference<>();

    private final AtomicReference<@Nullable Metadata> responseHeaders = new AtomicReference<>();

    private final AtomicReference<@Nullable Metadata> responseTrailers = new AtomicReference<>();

    private @Nullable Server server;

    private @Nullable ManagedChannel channel;

    @AfterEach
    void stop() throws InterruptedException {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
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

    /** Records the state where an inner interceptor, its listener and the service see it. */
    private void record(String where) {
        GrpcCallState state = GrpcCallState.current();
        seen.add(where);
        if (state != null) {
            states.add(state);
        }
    }

    /**
     * Starts an in-process server: the edge, then an inner interceptor that records what it sees and runs
     * {@code inner} on the state, then a service whose {@code Ping} answers through {@code ping}.
     */
    private CatalogServiceGrpc.CatalogServiceBlockingStub start(boolean trusted, Consumer<GrpcCallState> inner,
            Consumer<StreamObserver<Empty>> ping) throws IOException {
        SynapseGrpcEdgeInterceptor edge = new SynapseGrpcEdgeInterceptor(
                new MetadataMasking(trusted, SYNAPSE_FIELDS, Set.of("region")), CORRELATION);
        ServerInterceptor probe = new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                    Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                record("interceptor");
                innerMetadata.set(headers);
                GrpcCallState state = GrpcCallState.current();
                if (state != null) {
                    inner.accept(state);
                }
                return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(
                        next.startCall(call, headers)) {
                    @Override
                    public void onHalfClose() {
                        record("listener");
                        super.onHalfClose();
                    }
                };
            }
        };
        CatalogServiceGrpc.CatalogServiceImplBase service = new CatalogServiceGrpc.CatalogServiceImplBase() {
            @Override
            public void ping(Empty request, StreamObserver<Empty> responseObserver) {
                record("service");
                ping.accept(responseObserver);
            }
        };
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder
                .forName(name)
                .directExecutor()
                .addService(ServerInterceptors.interceptForward(service, edge, probe))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        return CatalogServiceGrpc
                .newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newCaptureMetadataInterceptor(responseHeaders, responseTrailers));
    }

    private static void ok(StreamObserver<Empty> observer) {
        observer.onNext(Empty.getDefaultInstance());
        observer.onCompleted();
    }

    private static CatalogServiceGrpc.CatalogServiceBlockingStub sending(
            CatalogServiceGrpc.CatalogServiceBlockingStub stub, Metadata metadata) {
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    @Test
    void stateIsVisibleToInnerInterceptorsAndListeners() throws IOException {
        CatalogServiceGrpc.CatalogServiceBlockingStub stub = start(true, state -> {
        }, SynapseGrpcEdgeInterceptorTest::ok);

        sending(stub, metadata("x-correlation-id", "c-raw", "baggage", "correlationId=evil,tenant=t1,partner=p"))
                .ping(Empty.getDefaultInstance());

        assertThat(seen).containsExactly("interceptor", "listener", "service");
        assertThat(states).hasSize(3);
        GrpcCallState state = states.getFirst();
        assertThat(states).allSatisfy(each -> assertThat(each).isSameAs(state));
        // The correlation metadata wins over the baggage candidate; the other Synapse fields stay candidates.
        assertThat(state.correlationCandidate()).isEqualTo("c-raw");
        assertThat(state.baggageCandidates()).isEqualTo(Map.of("tenant", "t1"));
        assertThat(state.correlationId()).isNull();
        // Inner interceptors see the masked metadata.
        Metadata inner = Objects.requireNonNull(innerMetadata.get(), "inner");
        assertThat(inner.getAll(key("baggage"))).containsExactly("partner=p");
        assertThat(inner.get(CORRELATION)).isEqualTo("c-raw");
    }

    @Test
    void correlationCandidateFallsBackToTheBaggageCandidate() throws IOException {
        CatalogServiceGrpc.CatalogServiceBlockingStub stub = start(true, state -> {
        }, SynapseGrpcEdgeInterceptorTest::ok);

        sending(stub, metadata("baggage", "correlationId=c-bag,tenant=t1")).ping(Empty.getDefaultInstance());

        GrpcCallState state = states.getFirst();
        assertThat(state.correlationCandidate()).isEqualTo("c-bag");
        assertThat(state.baggageCandidates()).isEqualTo(Map.of("tenant", "t1"));
    }

    @Test
    void repeatedCorrelationMetadataGivesItsFirstValue() throws IOException {
        CatalogServiceGrpc.CatalogServiceBlockingStub stub = start(true, state -> {
        }, SynapseGrpcEdgeInterceptorTest::ok);

        sending(stub, metadata("x-correlation-id", "c-1", "x-correlation-id", "c-2")).ping(Empty.getDefaultInstance());

        assertThat(states.getFirst().correlationCandidate()).isEqualTo("c-1");
    }

    @Test
    void emptyCorrelationMetadataFallsBackToTheBaggageCandidate() throws IOException {
        CatalogServiceGrpc.CatalogServiceBlockingStub stub = start(true, state -> {
        }, SynapseGrpcEdgeInterceptorTest::ok);

        sending(stub, metadata("x-correlation-id", "", "baggage", "correlationId=c-bag"))
                .ping(Empty.getDefaultInstance());

        assertThat(states.getFirst().correlationCandidate()).isEqualTo("c-bag");
    }

    @Test
    void untrustedCallHasNoBaggageCandidates() throws IOException {
        CatalogServiceGrpc.CatalogServiceBlockingStub stub = start(false, state -> {
        }, SynapseGrpcEdgeInterceptorTest::ok);

        sending(stub, metadata("baggage", "correlationId=c-bag,tenant=t1", "traceparent", "00-x", "region", "eu"))
                .ping(Empty.getDefaultInstance());

        GrpcCallState state = states.getFirst();
        assertThat(state.correlationCandidate()).isNull();
        assertThat(state.baggageCandidates()).isEmpty();
        Metadata inner = Objects.requireNonNull(innerMetadata.get(), "inner");
        assertThat(inner.keys()).doesNotContain("baggage", "traceparent", "region");
    }

    @Test
    void correlationIdIsEchoedInHeaders() throws IOException {
        CatalogServiceGrpc.CatalogServiceBlockingStub stub = start(true, state -> state.correlationId("c-1"),
                SynapseGrpcEdgeInterceptorTest::ok);

        sending(stub, metadata("x-correlation-id", "c-raw")).ping(Empty.getDefaultInstance());

        Metadata headers = Objects.requireNonNull(responseHeaders.get(), "headers");
        assertThat(headers.getAll(CORRELATION)).containsExactly("c-1");
        Metadata trailers = Objects.requireNonNull(responseTrailers.get(), "trailers");
        assertThat(trailers.containsKey(CORRELATION)).isFalse();
    }

    @Test
    void correlationIdIsEchoedInTrailersWithoutHeaders() throws IOException {
        CatalogServiceGrpc.CatalogServiceBlockingStub stub = start(true, state -> state.correlationId("c-1"),
                observer -> observer.onError(Status.NOT_FOUND.asRuntimeException()));

        assertThatThrownBy(() -> stub.ping(Empty.getDefaultInstance()))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));

        assertThat(responseHeaders.get()).isNull();
        Metadata trailers = Objects.requireNonNull(responseTrailers.get(), "trailers");
        assertThat(trailers.getAll(CORRELATION)).containsExactly("c-1");
    }

    @Test
    void nothingIsEchoedBeforeTheIdIsResolved() throws IOException {
        CatalogServiceGrpc.CatalogServiceBlockingStub stub = start(true, state -> {
        }, SynapseGrpcEdgeInterceptorTest::ok);

        sending(stub, metadata("x-correlation-id", "c-raw")).ping(Empty.getDefaultInstance());

        Metadata headers = Objects.requireNonNull(responseHeaders.get(), "headers");
        assertThat(headers.containsKey(CORRELATION)).isFalse();
        Metadata trailers = Objects.requireNonNull(responseTrailers.get(), "trailers");
        assertThat(trailers.containsKey(CORRELATION)).isFalse();
    }

    @Test
    void echoReplacesACorrelationValueSetInside() {
        GrpcCallState state = new GrpcCallState(null, Map.of());
        state.correlationId("c-1");
        AtomicReference<@Nullable Metadata> sentHeaders = new AtomicReference<>();
        AtomicReference<@Nullable Metadata> sentTrailers = new AtomicReference<>();
        ServerCall<Empty, Empty> delegate = new RecordingCall(sentHeaders, sentTrailers);

        CorrelationEchoCall<Empty, Empty> echo = new CorrelationEchoCall<>(delegate, state, CORRELATION);
        echo.sendHeaders(metadata("x-correlation-id", "inner"));
        echo.close(Status.OK, metadata("x-correlation-id", "inner"));

        Metadata headers = Objects.requireNonNull(sentHeaders.get(), "headers");
        assertThat(headers.getAll(CORRELATION)).containsExactly("c-1");
        // Headers were sent, so the trailers are passed on as they are.
        Metadata trailers = Objects.requireNonNull(sentTrailers.get(), "trailers");
        assertThat(trailers.getAll(CORRELATION)).containsExactly("inner");
    }

    /** A call that records the headers and trailers it is given. */
    private static final class RecordingCall extends ForwardingServerCall<Empty, Empty> {

        private final AtomicReference<@Nullable Metadata> headers;

        private final AtomicReference<@Nullable Metadata> trailers;

        RecordingCall(AtomicReference<@Nullable Metadata> headers, AtomicReference<@Nullable Metadata> trailers) {
            this.headers = headers;
            this.trailers = trailers;
        }

        @Override
        protected ServerCall<Empty, Empty> delegate() {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public void sendHeaders(Metadata metadata) {
            headers.set(metadata);
        }

        @Override
        public void close(Status status, Metadata metadata) {
            trailers.set(metadata);
        }
    }
}
