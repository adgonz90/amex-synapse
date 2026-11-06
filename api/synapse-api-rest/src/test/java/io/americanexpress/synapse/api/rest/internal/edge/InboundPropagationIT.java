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
package io.americanexpress.synapse.api.rest.internal.edge;

import java.util.Objects;

import io.micrometer.tracing.Baggage;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.core.operation.QueryOperation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sends propagation headers to a real server with OpenTelemetry tracing and checks what tracing extracted, as seen by
 * an escape-hatch controller and by the operation it calls.
 */
@SpringBootTest(classes = InboundPropagationIT.TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.tracing.sampling.probability=1.0", "management.tracing.baggage.remote-fields=region",
        "synapse.api.rest.context.headers.tenant=X-Tenant", "management.logging.export.otlp.enabled=false",
        "management.tracing.export.otlp.enabled=false", "management.otlp.metrics.export.enabled=false"})
@AutoConfigureRestTestClient
@ExtendWith(OutputCaptureExtension.class)
@ActiveProfiles("test")
class InboundPropagationIT {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String CALLER_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private static final String TRACEPARENT = "00-" + CALLER_TRACE_ID + "-00f067aa0ba902b7-01";

    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Autowired
    private RestTestClient client;

    static Seen probe(RestTestClient client, String... headers) {
        return exchange(client, headers).seen();
    }

    static Probed exchange(RestTestClient client, String... headers) {
        RestTestClient.RequestHeadersSpec<?> spec = client.get().uri("/probe");
        for (int i = 0; i < headers.length; i += 2) {
            spec = spec.header(headers[i], headers[i + 1]);
        }
        var result = spec.exchange().returnResult(String.class);
        assertThat(result.getStatus().value()).isEqualTo(200);
        return new Probed(MAPPER.readValue(Objects.requireNonNull(result.getResponseBody()), Seen.class),
                result.getResponseHeaders().getFirst("X-Correlation-Id"));
    }

    @Test
    void trustedRequestJoinsTheCallersTrace() {
        Seen seen = probe(client, "traceparent", TRACEPARENT);

        assertThat(seen.traceId()).isEqualTo(CALLER_TRACE_ID);
        assertThat(seen.operationTraceId()).isEqualTo(CALLER_TRACE_ID);
    }

    @Test
    void rawBaggageCorrelationIdNeverReachesTracing() {
        Seen seen = probe(client, "baggage", "correlationId=evil%20id");

        assertThat(seen.correlationId()).matches(UUID).isNotEqualTo("evil id");
        // The edge replaced the invalid candidate; the operation runs in the edge's scope, so it sees that same id.
        assertThat(seen.operationCorrelationId()).isEqualTo(seen.correlationId()).isNotEqualTo("evil id");
    }

    @Test
    void rawBaggageCorrelationIdNeverReachesMdc(CapturedOutput output) {
        Probed probed = exchange(client, "baggage", "correlationId=evil%20id");

        assertThat(probed.seen().operationMdcCorrelationId()).matches(UUID).isNotEqualTo("evil id");
        assertThat(probed.correlationHeader()).matches(UUID).isEqualTo(probed.seen().operationMdcCorrelationId());
        // The only trace of the raw value is the sanitized WARN text, "evil_id" (rate-limited, so maybe not here).
        assertThat(output.getAll()).doesNotContain("evil id").doesNotContain("evil%20id");
        assertThat(output.getAll().replace("evil_id", "")).doesNotContain("evil");
    }

    @Test
    void synapseFieldHeaderNeverReachesTracing() {
        // The tenant header wins over the baggage entry, but its value is over the context size limit: it reaches
        // tracing only through the context filter, which drops it, so neither value is seen.
        Seen seen = probe(client, "tenant", "t".repeat(5000), "baggage", "tenant=t1");

        assertThat(seen.tenant()).isNull();
    }

    @Test
    void validSynapseFieldReachesTracingThroughTheContext() {
        Seen seen = probe(client, "tenant", "t9", "baggage", "tenant=t1");

        assertThat(seen.tenant()).isEqualTo("t9");
    }

    @Test
    void trustedRequestKeepsOtherBaggageAndRemoteFields() {
        Seen seen = probe(client, "baggage", "partner=p,correlationId=evil", "region", "eu");

        assertThat(seen.partner()).isEqualTo("p");
        assertThat(seen.region()).isEqualTo("eu");
        // A valid trusted candidate becomes the correlation id, through the context filter.
        assertThat(seen.correlationId()).isEqualTo("evil");
    }

    @Nested
    @TestPropertySource(properties = "synapse.context.inbound.trusted=false")
    class Untrusted {

        @Autowired
        private RestTestClient untrustedClient;

        @Test
        void untrustedRequestStartsANewTrace() {
            Seen seen = probe(untrustedClient, "traceparent", TRACEPARENT, "baggage", "partner=p", "region", "eu");

            assertThat(seen.traceId()).isNotEqualTo(CALLER_TRACE_ID).matches("[0-9a-f]{32}");
            assertThat(seen.operationTraceId()).isEqualTo(seen.traceId());
            assertThat(seen.partner()).isNull();
            assertThat(seen.region()).isNull();
        }
    }

    /** What tracing held in the controller, and in the operation it called. */
    record Seen(String traceId, @Nullable String correlationId, @Nullable String tenant, @Nullable String partner,
            @Nullable String region, String operationTraceId, @Nullable String operationCorrelationId,
            @Nullable String operationMdcCorrelationId) {
    }

    /** What the probe returned, and the correlation header of its response. */
    record Probed(Seen seen, @Nullable String correlationHeader) {
    }

    /** What tracing and the MDC held inside the operation. */
    record InOperation(String traceId, @Nullable String correlationId, @Nullable String mdcCorrelationId) {
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(ProbeController.class)
    static class TestApplication {

        @Bean
        ProbeOperation probeOperation(Tracer tracer) {
            return new ProbeOperation(tracer);
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

    /** A query named {@code probe} that records the trace id and correlation baggage it runs with. */
    static class ProbeOperation implements QueryOperation<String, InOperation> {

        private final Tracer tracer;

        ProbeOperation(Tracer tracer) {
            this.tracer = tracer;
        }

        @Override
        public InOperation execute(String input) {
            return new InOperation(traceId(tracer), baggage(tracer, "correlationId"), MDC.get("correlationId"));
        }
    }

    @RestController
    static class ProbeController {

        private final Tracer tracer;

        private final ProbeOperation operation;

        ProbeController(Tracer tracer, ProbeOperation operation) {
            this.tracer = tracer;
            this.operation = operation;
        }

        @GetMapping("/probe")
        Seen probe() {
            String traceId = traceId(tracer);
            String correlationId = baggage(tracer, "correlationId");
            String tenant = baggage(tracer, "tenant");
            String partner = baggage(tracer, "partner");
            String region = baggage(tracer, "region");
            InOperation inOperation = operation.execute("probe");
            return new Seen(traceId, correlationId, tenant, partner, region, inOperation.traceId(),
                    inOperation.correlationId(), inOperation.mdcCorrelationId());
        }
    }
}
