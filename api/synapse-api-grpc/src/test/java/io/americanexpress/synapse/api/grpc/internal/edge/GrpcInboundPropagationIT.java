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
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import io.micrometer.tracing.Baggage;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.TestPropertySource;

import io.americanexpress.synapse.api.grpc.GrpcBindings;
import io.americanexpress.synapse.api.grpc.fixture.proto.CatalogServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.GetItemRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.Item;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures;
import io.americanexpress.synapse.core.context.RequestContext;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sends propagation metadata to a real Netty server with OpenTelemetry tracing and checks what tracing extracted, as
 * seen by a hand-written service and by the operation it calls, and by an operation bound with {@code GrpcBindings}.
 */
@SpringBootTest(classes = GrpcInboundPropagationIT.TestApplication.class, properties = {"spring.grpc.server.port=0",
        "management.tracing.sampling.probability=1.0", "management.tracing.baggage.remote-fields=region",
        "management.logging.export.otlp.enabled=false", "management.tracing.export.otlp.enabled=false",
        "management.otlp.metrics.export.enabled=false"})
@ExtendWith(OutputCaptureExtension.class)
@ActiveProfiles("test")
class GrpcInboundPropagationIT {

    private static final String CALLER_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private static final String TRACEPARENT = "00-" + CALLER_TRACE_ID + "-00f067aa0ba902b7-01";

    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private static final Metadata.Key<String> CORRELATION = Metadata.Key
            .of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);

    @LocalGrpcServerPort
    private int port;

    /** Calls the probe on the server at {@code port} with the given metadata. */
    static Seen probe(int port, String... pairs) throws InterruptedException {
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
            Item item = CatalogServiceGrpc
                    .newBlockingStub(channel)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata),
                            MetadataUtils.newCaptureMetadataInterceptor(headers, trailers))
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .getItem(GetItemRequest.newBuilder().setId("probe").build());
            Metadata received = Objects.requireNonNull(headers.get(), "response headers");
            return Seen.of(item, received.get(CORRELATION));
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @Tag("smoke")
    void trustedCallJoinsTheCallersTrace() throws InterruptedException {
        Seen seen = probe(port, "traceparent", TRACEPARENT);

        assertThat(seen.traceId()).isEqualTo(CALLER_TRACE_ID);
        assertThat(seen.operationTraceId()).isEqualTo(CALLER_TRACE_ID);
    }

    @Test
    void trustedCallKeepsOtherBaggageAndRemoteFields() throws InterruptedException {
        Seen seen = probe(port, "baggage", "partner=p,correlationId=evil", "region", "eu");

        assertThat(seen.partner()).isEqualTo("p");
        assertThat(seen.region()).isEqualTo("eu");
        // A valid trusted candidate becomes the correlation id, through the context interceptor.
        assertThat(seen.correlationId()).isEqualTo("evil");
        assertThat(seen.echoed()).isEqualTo("evil");
    }

    @Test
    void rawBaggageCorrelationIdNeverReachesMdc(CapturedOutput output) throws InterruptedException {
        // Review Focus 3: no correlation metadata, and a baggage candidate that is not a valid id.
        Seen seen = probe(port, "baggage", "correlationId=evil%20id");

        assertThat(seen.mdcCorrelationId()).matches(UUID).isNotEqualTo("evil id");
        assertThat(seen.correlationId()).isEqualTo(seen.mdcCorrelationId());
        assertThat(seen.echoed()).isEqualTo(seen.mdcCorrelationId());
        // The only trace of the raw value is the sanitized WARN text, "evil_id" (rate-limited, so maybe not here).
        assertThat(output.getAll()).doesNotContain("evil id").doesNotContain("evil%20id");
        assertThat(output.getAll().replace("evil_id", "")).doesNotContain("evil");
    }

    @Nested
    @TestPropertySource(properties = "synapse.context.inbound.trusted=false")
    class Untrusted {

        @LocalGrpcServerPort
        private int untrustedPort;

        @Test
        void untrustedCallStartsANewTrace() throws InterruptedException {
            Seen seen = probe(untrustedPort, "traceparent", TRACEPARENT, "baggage", "partner=p", "region", "eu");

            assertThat(seen.traceId()).isNotEqualTo(CALLER_TRACE_ID).matches("[0-9a-f]{32}");
            assertThat(seen.operationTraceId()).isEqualTo(seen.traceId());
            assertThat(seen.partner()).isNull();
            assertThat(seen.region()).isNull();
        }
    }

    /** The same probe, served by {@link GrpcBindings} instead of a hand-written service. */
    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(classes = BindingsApplication.class, properties = {"spring.grpc.server.port=0",
            "management.tracing.sampling.probability=1.0", "management.tracing.baggage.remote-fields=region",
            "management.logging.export.otlp.enabled=false", "management.tracing.export.otlp.enabled=false",
            "management.otlp.metrics.export.enabled=false"})
    @ActiveProfiles("test")
    class ThroughBindings {

        @LocalGrpcServerPort
        private int bindingsPort;

        @Test
        void trustedCallJoinsTheCallersTrace() throws InterruptedException {
            Seen seen = probe(bindingsPort, "traceparent", TRACEPARENT, "baggage", "partner=p", "region", "eu",
                    "x-correlation-id", "c-bound");

            assertThat(seen.traceId()).isEqualTo(CALLER_TRACE_ID);
            assertThat(seen.operationTraceId()).isEqualTo(CALLER_TRACE_ID);
            assertThat(seen.partner()).isEqualTo("p");
            assertThat(seen.region()).isEqualTo("eu");
            assertThat(seen.mdcCorrelationId()).isEqualTo("c-bound");
            assertThat(seen.correlationId()).isEqualTo("c-bound");
            assertThat(seen.echoed()).isEqualTo("c-bound");
        }
    }

    /**
     * What tracing held in the service and in the operation it called, carried back in an {@link Item}: the service's
     * trace id as the id, the operation's as the name, and the operation's baggage, MDC correlation id and request
     * context correlation id as {@code name=value} tags; plus the correlation id the edge echoed.
     */
    record Seen(String traceId, String operationTraceId, @Nullable String partner, @Nullable String region,
            @Nullable String mdcCorrelationId, @Nullable String correlationId, @Nullable String echoed) {

        static Seen of(Item item, @Nullable String echoed) {
            return new Seen(item.getId(), item.getName(), tag(item, "partner"), tag(item, "region"), tag(item, "mdc"),
                    tag(item, "context"), echoed);
        }

        private static @Nullable String tag(Item item, String name) {
            for (String tag : item.getTagsList()) {
                if (tag.startsWith(name + "=")) {
                    return tag.substring(name.length() + 1);
                }
            }
            return null;
        }
    }

    /** What tracing, the MDC and the request context held inside the operation. */
    record InOperation(String traceId, @Nullable String partner, @Nullable String region,
            @Nullable String mdcCorrelationId, @Nullable String correlationId) {
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {

        @Bean
        ProbeOperation probeOperation(Tracer tracer) {
            return new ProbeOperation(tracer);
        }

        @Bean
        ProbeService probeService(Tracer tracer, ProbeOperation operation) {
            return new ProbeService(tracer, operation);
        }
    }

    static @Nullable String baggage(Tracer tracer, String name) {
        Baggage baggage = tracer.getBaggage(name);
        return baggage == null ? null : baggage.get();
    }

    static String traceId(Tracer tracer) {
        Span span = Objects.requireNonNull(tracer.currentSpan(), "no current span");
        return span.context().traceId();
    }

    /** A query named {@code probe} that records the trace id, baggage, MDC and request context it runs with. */
    static class ProbeOperation implements QueryOperation<String, InOperation> {

        private final Tracer tracer;

        ProbeOperation(Tracer tracer) {
            this.tracer = tracer;
        }

        @Override
        public InOperation execute(String input) {
            return new InOperation(traceId(tracer), baggage(tracer, "partner"), baggage(tracer, "region"),
                    MDC.get("correlationId"), RequestContext.find().map(RequestContext::correlationId).orElse(null));
        }
    }

    /** A hand-written service (the escape hatch) whose {@code GetItem} calls the probe operation. */
    static class ProbeService extends CatalogServiceGrpc.CatalogServiceImplBase {

        private final Tracer tracer;

        private final ProbeOperation operation;

        ProbeService(Tracer tracer, ProbeOperation operation) {
            this.tracer = tracer;
            this.operation = operation;
        }

        @Override
        public void getItem(GetItemRequest request, StreamObserver<Item> responseObserver) {
            String traceId = traceId(tracer);
            InOperation inOperation = operation.execute(request.getId());
            Item.Builder item = Item.newBuilder().setId(traceId).setName(inOperation.traceId());
            tag(item, "partner", inOperation.partner());
            tag(item, "region", inOperation.region());
            tag(item, "mdc", inOperation.mdcCorrelationId());
            tag(item, "context", inOperation.correlationId());
            responseObserver.onNext(item.build());
            responseObserver.onCompleted();
        }

        private static void tag(Item.Builder item, String name, @Nullable String value) {
            if (value != null) {
                item.addTags(name + "=" + value);
            }
        }
    }

    /** The application of {@link ThroughBindings}: the probe bound to {@code GetItem}, the other methods by name. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class BindingsApplication {

        @Bean
        ProbeItemOperation probeItemOperation(Tracer tracer) {
            return new ProbeItemOperation(tracer);
        }

        @Bean
        BindingFixtures.CreateItemOperation createItemOperation() {
            return new BindingFixtures.CreateItemOperation();
        }

        @Bean
        BindingFixtures.DeleteItemOperation deleteItemOperation() {
            return new BindingFixtures.DeleteItemOperation();
        }

        @Bean
        BindingFixtures.PingOperation pingOperation() {
            return new BindingFixtures.PingOperation();
        }

        @Bean
        BindingFixtures.ListTagsOperation listTagsOperation() {
            return new BindingFixtures.ListTagsOperation();
        }

        @Bean
        BindingFixtures.FailOperation failOperation() {
            return new BindingFixtures.FailOperation();
        }

        @Bean
        GrpcBindings catalogBindings(ProbeItemOperation probe, BindingFixtures.CreateItemOperation createItem) {
            return GrpcBindings
                    .service(CatalogServiceGrpc.getServiceDescriptor())
                    .unary(CatalogServiceGrpc.getGetItemMethod(), probe)
                    .unary(CatalogServiceGrpc.getCreateItemMethod(), createItem)
                    .context(BindingFixtures.CreateItem::channel, context -> "grpc")
                    .bindAllByName()
                    .build();
        }
    }

    /** The input of {@link ProbeItemOperation}. */
    record ProbeKey(String id) {
    }

    /** What {@link ProbeItemOperation} saw, written to {@code Item}'s id, name and tags as {@link Seen} reads them. */
    record ProbeItem(String id, String name, List<String> tags) {
    }

    /** The probe as a bound operation: it records the trace id, baggage, MDC and request context it runs with. */
    static class ProbeItemOperation implements QueryOperation<ProbeKey, ProbeItem> {

        private final Tracer tracer;

        ProbeItemOperation(Tracer tracer) {
            this.tracer = tracer;
        }

        @Override
        public ProbeItem execute(ProbeKey input) {
            String traceId = traceId(tracer);
            List<String> tags = new ArrayList<>();
            tag(tags, "partner", baggage(tracer, "partner"));
            tag(tags, "region", baggage(tracer, "region"));
            tag(tags, "mdc", MDC.get("correlationId"));
            tag(tags, "context", RequestContext.find().map(RequestContext::correlationId).orElse(null));
            return new ProbeItem(traceId, traceId, tags);
        }

        private static void tag(List<String> tags, String name, @Nullable String value) {
            if (value != null) {
                tags.add(name + "=" + value);
            }
        }
    }
}
