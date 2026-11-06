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

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.micrometer.common.KeyValue;
import io.micrometer.tracing.Baggage;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelBaggageManager;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ServerHttpObservationFilter;

import io.americanexpress.synapse.core.context.RequestContext;
import io.americanexpress.synapse.core.internal.context.ContextBinder;
import io.americanexpress.synapse.core.internal.context.CorrelationIdPolicy;
import io.americanexpress.synapse.test.time.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SynapseRestContextFilterTest {

    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final MutableClock clock = MutableClock.at("2026-01-01T00:00:00Z");

    private final Tracer tracer = otelTracer();

    /** The OpenTelemetry bridge, as in a running application, so baggage closes with its scope. */
    private static Tracer otelTracer() {
        OtelCurrentTraceContext current = new OtelCurrentTraceContext();
        OtelBaggageManager baggage = new OtelBaggageManager(current, List.of("correlationId", "tenant"), List.of());
        return new OtelTracer(SdkTracerProvider.builder().build().get("test"), current, event -> {
        }, baggage);
    }

    private final AtomicReference<@Nullable RequestContext> seen = new AtomicReference<>();

    private final AtomicReference<@Nullable String> seenTenantBaggage = new AtomicReference<>();

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private @Nullable Span span;

    private Tracer.@Nullable SpanInScope spanInScope;

    /** {@link ContextBinder#open} attaches baggage to the current span, as the server observation's span is. */
    @BeforeEach
    void openServerSpan() {
        Span server = tracer.nextSpan().name("http.server.requests").start();
        span = server;
        spanInScope = tracer.withSpan(server);
    }

    @AfterEach
    void closeServerSpan() {
        Objects.requireNonNull(spanInScope).close();
        Objects.requireNonNull(span).end();
    }

    private SynapseRestContextFilter filter(Map<String, String> attributeHeaders) {
        return filter(attributeHeaders, true, 16);
    }

    private SynapseRestContextFilter filter(Map<String, String> attributeHeaders, boolean trusted, int maxEntries) {
        ContextBinder binder = new ContextBinder(tracer, new CorrelationIdPolicy(128, clock), trusted, maxEntries,
                4096, clock);
        return new SynapseRestContextFilter(binder, "X-Correlation-Id", attributeHeaders, "Request-Timeout",
                new DeadlinePolicy(Duration.ofSeconds(10), Duration.ofSeconds(30), clock));
    }

    /** A chain whose servlet records the request context and the tenant baggage it runs with. */
    private MockFilterChain recordingChain() {
        return new MockFilterChain(new HttpServlet() {

            private static final long serialVersionUID = 1L;

            @Override
            protected void service(HttpServletRequest request, HttpServletResponse servletResponse) {
                seen.set(RequestContext.current());
                seenTenantBaggage.set(baggage("tenant"));
            }
        });
    }

    private @Nullable String baggage(String name) {
        Baggage baggage = tracer.getBaggage(name);
        return baggage == null ? null : baggage.get();
    }

    private RequestContext run(SynapseRestContextFilter filter, MockHttpServletRequest request) throws Exception {
        filter.doFilter(request, response, recordingChain());
        return Objects.requireNonNull(seen.get(), "the chain was not called");
    }

    @Test
    void opensContextWithHeaderCorrelationAndEchoesIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-Id", "c-1");

        RequestContext context = run(filter(Map.of()), request);

        assertThat(context.correlationId()).isEqualTo("c-1");
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("c-1");
        assertThat(request.getAttribute(RestRequestAttributes.CORRELATION_ID)).isEqualTo("c-1");
        assertThat(context.principal()).isEmpty();
    }

    @Test
    void headerCorrelationWinsOverBaggageCandidate() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-Id", "c-1");
        request.setAttribute(InboundBaggage.ATTRIBUTE, Map.of("correlationId", "from-baggage"));

        assertThat(run(filter(Map.of()), request).correlationId()).isEqualTo("c-1");
    }

    @Test
    void correlationFromBaggageCandidateWithoutHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(InboundBaggage.ATTRIBUTE, Map.of("correlationId", "from-baggage"));

        RequestContext context = run(filter(Map.of()), request);

        assertThat(context.correlationId()).isEqualTo("from-baggage");
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("from-baggage");
    }

    @Test
    void invalidCandidateIsReplacedWithAGeneratedId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(InboundBaggage.ATTRIBUTE, Map.of("correlationId", "evil id"));

        RequestContext context = run(filter(Map.of()), request);

        assertThat(context.correlationId()).matches(UUID);
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(context.correlationId());
        assertThat(request.getAttribute(RestRequestAttributes.CORRELATION_ID)).isEqualTo(context.correlationId());
    }

    @Test
    void generatesCorrelationIdWhenAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();

        RequestContext context = run(filter(Map.of()), request);

        assertThat(context.correlationId()).matches(UUID);
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(context.correlationId());
    }

    @Test
    void emptyCorrelationHeaderFallsBackToTheCandidate() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-Id", "");
        request.setAttribute(InboundBaggage.ATTRIBUTE, Map.of("correlationId", "from-baggage"));

        assertThat(run(filter(Map.of()), request).correlationId()).isEqualTo("from-baggage");
    }

    @Test
    void attributeFromMappedHeaderThenBaggage() throws Exception {
        MockHttpServletRequest withHeader = new MockHttpServletRequest();
        withHeader.addHeader("X-Tenant", "acme");
        withHeader.setAttribute(InboundBaggage.ATTRIBUTE, Map.of("tenant", "t1"));

        assertThat(run(filter(Map.of("tenant", "X-Tenant")), withHeader).attributes())
                .containsExactly(Map.entry("tenant", "acme"));
        assertThat(seenTenantBaggage.get()).isEqualTo("acme");

        MockHttpServletRequest withoutHeader = new MockHttpServletRequest();
        withoutHeader.setAttribute(InboundBaggage.ATTRIBUTE, Map.of("tenant", "t1"));

        assertThat(run(filter(Map.of("tenant", "X-Tenant")), withoutHeader).attributes())
                .containsExactly(Map.entry("tenant", "t1"));
        assertThat(seenTenantBaggage.get()).isEqualTo("t1");
    }

    @Test
    void emptyMappedHeaderFallsBackToTheCandidate() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant", "");
        request.setAttribute(InboundBaggage.ATTRIBUTE, Map.of("tenant", "t1"));

        assertThat(run(filter(Map.of("tenant", "X-Tenant")), request).attributes())
                .containsExactly(Map.entry("tenant", "t1"));
    }

    @Test
    void attributeAbsentWhenNeitherHeaderNorCandidate() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(run(filter(Map.of("tenant", "X-Tenant")), request).attributes()).isEmpty();
    }

    @Test
    void attributesAreBuiltInMapOrderSoTheLimitKeepsTheFirst() throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("zone", "X-Zone");
        headers.put("channel", "X-Channel");
        headers.put("app", "X-App");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-App", "a");
        request.addHeader("X-Channel", "c");
        request.addHeader("X-Zone", "z");

        assertThat(run(filter(headers, true, 2), request).attributes())
                .containsExactly(Map.entry("zone", "z"), Map.entry("channel", "c"));
    }

    @Test
    void untrustedDropsAttributes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant", "acme");

        assertThat(run(filter(Map.of("tenant", "X-Tenant"), false, 16), request).attributes()).isEmpty();
    }

    @Test
    void localeOnlyWithAcceptLanguage() throws Exception {
        MockHttpServletRequest withoutHeader = new MockHttpServletRequest();
        // The container still reports a locale (its default) when the caller sent none.
        assertThat(withoutHeader.getLocale()).isEqualTo(Locale.ENGLISH);

        assertThat(run(filter(Map.of()), withoutHeader).locale()).isEmpty();

        MockHttpServletRequest withHeader = new MockHttpServletRequest();
        withHeader.addHeader("Accept-Language", "fr-CA");

        assertThat(run(filter(Map.of()), withHeader).locale()).contains(Locale.CANADA_FRENCH);
    }

    @Test
    void deadlineFromRequestTimeoutOrDefault() throws Exception {
        MockHttpServletRequest withHeader = new MockHttpServletRequest();
        withHeader.addHeader("Request-Timeout", "500");

        assertThat(run(filter(Map.of()), withHeader).deadline())
                .contains(Instant.parse("2026-01-01T00:00:00.500Z"));

        assertThat(run(filter(Map.of()), new MockHttpServletRequest()).deadline())
                .contains(Instant.parse("2026-01-01T00:00:10Z"));
    }

    @Test
    void tagsTheServerObservationWithTheCorrelationId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-Id", "c-1");
        ServerRequestObservationContext observation = new ServerRequestObservationContext(request, response);
        request.setAttribute(ServerHttpObservationFilter.CURRENT_OBSERVATION_CONTEXT_ATTRIBUTE, observation);

        run(filter(Map.of()), request);

        assertThat(observation.getHighCardinalityKeyValues()).contains(KeyValue.of("correlation.id", "c-1"));
        assertThat(observation.getLowCardinalityKeyValues()).isEmpty();
    }

    @Test
    void worksWithoutAServerObservation() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-Id", "c-1");

        assertThat(run(filter(Map.of()), request).correlationId()).isEqualTo("c-1");
        assertThat(ServerHttpObservationFilter.findObservationContext(request)).isEmpty();
    }

    @Test
    void contextIsClearedAfterRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-Id", "c-1");
        request.addHeader("X-Tenant", "acme");

        run(filter(Map.of("tenant", "X-Tenant")), request);

        assertThat(RequestContext.find()).isEmpty();
        assertThat(baggage("tenant")).isNull();
        assertThat(baggage("correlationId")).isNull();
    }

    @Test
    void contextIsClearedAfterFailingRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-Id", "c-1");
        request.addHeader("X-Tenant", "acme");
        MockFilterChain failing = new MockFilterChain(new HttpServlet() {

            private static final long serialVersionUID = 1L;

            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException {
                seen.set(RequestContext.current());
                throw new ServletException("handler failed");
            }
        });

        assertThatThrownBy(() -> filter(Map.of("tenant", "X-Tenant")).doFilter(request, response, failing))
                .isInstanceOf(ServletException.class)
                .hasMessage("handler failed");

        assertThat(Objects.requireNonNull(seen.get()).correlationId()).isEqualTo("c-1");
        assertThat(RequestContext.find()).isEmpty();
        // This tracer publishes no MDC events, so this only proves the filter never writes the MDC itself. That the
        // MDC is clear after a failing request on a real server is RestEdgeIT.contextIsClearedAfter*.
        assertThat(MDC.get("correlationId")).isNull();
        assertThat(baggage("tenant")).isNull();
        assertThat(baggage("correlationId")).isNull();
    }

    @Test
    void contextIsClearedAfterRuntimeFailure() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockFilterChain failing = new MockFilterChain(new HttpServlet() {

            private static final long serialVersionUID = 1L;

            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                throw new IllegalStateException("boom");
            }
        });

        assertThatThrownBy(() -> filter(Map.of()).doFilter(request, response, failing))
                .isInstanceOf(IllegalStateException.class);

        assertThat(RequestContext.find()).isEmpty();
    }
}
