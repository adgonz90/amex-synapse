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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.tracing.Baggage;
import io.micrometer.tracing.Tracer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.autoconfigure.SynapseRestProperties;
import io.americanexpress.synapse.core.context.RequestContext;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sends requests to a real server running on virtual threads with OpenTelemetry tracing, and checks the request
 * context, MDC and baggage seen by an operation and by an {@code @Async} task it starts.
 */
@Tag("smoke")
@SpringBootTest(classes = RestEdgeIT.TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.threads.virtual.enabled=true", "synapse.api.rest.context.headers.tenant=X-Tenant",
        "management.tracing.sampling.probability=1.0", "management.logging.export.otlp.enabled=false",
        "management.tracing.export.otlp.enabled=false", "management.otlp.metrics.export.enabled=false"})
@AutoConfigureRestTestClient
@ExtendWith(OutputCaptureExtension.class)
@ActiveProfiles("test")
class RestEdgeIT {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Autowired
    private RestTestClient client;

    @Autowired
    private ServerObservations observations;

    @Autowired
    private RequestEnds requestEnds;

    /** A response: its status, correlation header and JSON body. */
    record Response(int status, @Nullable String correlationHeader, JsonNode body) {

        Seen seen() {
            return MAPPER.treeToValue(body, Seen.class);
        }
    }

    static Response get(RestTestClient client, String uri, String... headers) {
        RestTestClient.RequestHeadersSpec<?> spec = client.get().uri(uri);
        for (int i = 0; i < headers.length; i += 2) {
            spec = spec.header(headers[i], headers[i + 1]);
        }
        var result = spec.exchange().returnResult(String.class);
        return new Response(result.getStatus().value(), result.getResponseHeaders().getFirst("X-Correlation-Id"),
                MAPPER.readTree(Objects.requireNonNull(result.getResponseBody())));
    }

    @Test
    void requestThreadMdcHasCorrelationId() {
        Response response = get(client, "/edge", "X-Correlation-Id", "c-1");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.seen().mdcCorrelationId()).isEqualTo("c-1");
        assertThat(response.seen().correlationId()).isEqualTo("c-1");
        assertThat(response.seen().virtual()).isTrue();
        assertThat(response.correlationHeader()).isEqualTo("c-1");
    }

    @Test
    void asyncThreadSeesMdcAndBaggage() {
        Seen seen = get(client, "/edge", "X-Correlation-Id", "c-1", "X-Tenant", "acme").seen();

        AsyncSeen async = seen.async();
        assertThat(async.thread()).isNotEqualTo(seen.thread()).startsWith("task-");
        assertThat(async.mdcCorrelationId()).isEqualTo("c-1");
        assertThat(async.mdcTenant()).isEqualTo("acme");
        assertThat(async.baggageTenant()).isEqualTo("acme");
        assertThat(async.correlationId()).isEqualTo("c-1");
    }

    @Test
    void generatesCorrelationIdWhenAbsent() {
        Response response = get(client, "/edge/fail");

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.correlationHeader()).matches(UUID);
        assertThat(response.body().get("correlationId").asString()).isEqualTo(response.correlationHeader());
    }

    @Test
    void invalidCorrelationIdIsReplacedAndSanitized(CapturedOutput output) {
        Response response = get(client, "/edge", "X-Correlation-Id", "a".repeat(200));

        assertThat(response.correlationHeader()).matches(UUID);
        assertThat(response.seen().correlationId()).isEqualTo(response.correlationHeader());
        String warn = output
                .getAll()
                .lines()
                .filter(line -> line.contains("Replaced an invalid correlation id"))
                .findFirst()
                .orElseThrow();
        assertThat(warn).contains("WARN").contains("a".repeat(128)).doesNotContain("a".repeat(129));
    }

    @Test
    void defaultDeadlineWithoutRequestTimeout() {
        Instant before = Instant.now();
        Seen seen = get(client, "/edge").seen();
        Instant after = Instant.now();

        Instant deadline = Instant.parse(Objects.requireNonNull(seen.deadline()));
        assertThat(deadline)
                .isBetween(before.plusSeconds(10).minusSeconds(1), after.plusSeconds(10).plusSeconds(1));
    }

    @Test
    void requestTimeoutSetsTheDeadline() {
        Instant before = Instant.now();
        Seen seen = get(client, "/edge", "Request-Timeout", "2000").seen();
        Instant after = Instant.now();

        Instant deadline = Instant.parse(Objects.requireNonNull(seen.deadline()));
        assertThat(deadline).isBetween(before.plusSeconds(2).minusSeconds(1), after.plusSeconds(2).plusSeconds(1));
    }

    @Test
    void problemCarriesTheEdgeCorrelationId() {
        Response response = get(client, "/edge/fail", "X-Correlation-Id", "c-9");

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body().get("correlationId").asString()).isEqualTo("c-9");
        assertThat(response.correlationHeader()).isEqualTo("c-9");
    }

    @Test
    void localeComesFromAcceptLanguageOnly() {
        assertThat(get(client, "/edge").seen().locale()).isNull();
        assertThat(get(client, "/edge", "Accept-Language", "fr-CA").seen().locale()).isEqualTo("fr-CA");
    }

    @Test
    void serverObservationIsTaggedWithCorrelationId() throws InterruptedException {
        observations.stopped.clear();

        assertThat(get(client, "/edge", "X-Correlation-Id", "c-obs").status()).isEqualTo(200);

        Observation.Context server = observations.await("c-obs");
        assertThat(server.getName()).isEqualTo("http.server.requests");
        assertThat(server.getHighCardinalityKeyValues()).contains(KeyValue.of("correlation.id", "c-obs"));
    }

    @Test
    void contextIsClearedAfterFailingRequest() throws InterruptedException {
        Response response = get(client, "/edge/fail", "X-Correlation-Id", "c-end-fail");

        assertThat(response.status()).isEqualTo(409);
        RequestEnd end = requestEnds.await("c-end-fail");
        assertThat(end.mdcCorrelationId()).isNull();
        assertThat(end.contextOpen()).isFalse();
    }

    @Test
    void contextIsClearedAfterContainerErrorDispatch() throws InterruptedException {
        Logger restLogger = (Logger) LoggerFactory.getLogger("io.americanexpress.synapse.api.rest");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        restLogger.addAppender(appender);
        Response response;
        try {
            response = get(client, "/edge/filter-fail", "X-Correlation-Id", "c-end-dispatch");
        } finally {
            restLogger.detachAppender(appender);
        }

        assertThat(response.status()).isEqualTo(500);
        assertThat(response.body().get("code").asString()).isEqualTo("INTERNAL");
        assertThat(response.body().get("correlationId").asString()).isEqualTo("c-end-dispatch");
        RequestEnd end = requestEnds.await("c-end-dispatch");
        assertThat(end.mdcCorrelationId()).isNull();
        assertThat(end.contextOpen()).isFalse();
        // The error dispatch runs after the context closed; its ERROR line still carries the edge's id.
        List<ILoggingEvent> events;
        synchronized (appender) {
            events = List.copyOf(appender.list);
        }
        assertThat(events)
                .filteredOn(event -> event.getFormattedMessage().startsWith("Unhandled error for GET"))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(event.getMDCPropertyMap()).containsEntry("correlationId", "c-end-dispatch");
                });
    }

    @Nested
    @TestPropertySource(properties = "synapse.context.inbound.trusted=false")
    class Untrusted {

        @Autowired
        private RestTestClient untrustedClient;

        @Test
        void untrustedDropsAllowListedHeader() {
            Seen seen = get(untrustedClient, "/edge", "X-Correlation-Id", "c-1", "X-Tenant", "acme").seen();

            assertThat(seen.attributes()).doesNotContainKey("tenant");
            assertThat(seen.async().baggageTenant()).isNull();
            assertThat(seen.correlationId()).isEqualTo("c-1");
        }
    }

    @Nested
    @TestPropertySource(properties = {"synapse.context.inbound.max-entries=1",
            "synapse.api.rest.context.headers.channel=X-Channel"})
    class OverTheLimit {

        @Autowired
        private RestTestClient limitedClient;

        @Autowired
        private SynapseRestProperties properties;

        @Test
        void attributesOverTheLimitAreDroppedAndLoggedOnce(CapturedOutput output) {
            String first = properties.context().headers().keySet().iterator().next();
            for (int i = 0; i < 2; i++) {
                Seen seen = get(limitedClient, "/edge", "X-Tenant", "acme", "X-Channel", "web").seen();

                assertThat(seen.attributes()).hasSize(1).containsKey(first);
            }
            assertThat(output.getAll().lines().filter(line -> line.contains("Dropped"))).hasSize(1);
        }
    }

    /** What the operation saw on the request thread, and what its {@code @Async} task saw. */
    record Seen(String thread, boolean virtual, String correlationId, @Nullable String mdcCorrelationId,
            Map<String, String> attributes, @Nullable String locale, @Nullable String deadline, AsyncSeen async) {
    }

    /** What an {@code @Async} task saw on its own thread. */
    record AsyncSeen(String thread, @Nullable String correlationId, @Nullable String mdcCorrelationId,
            @Nullable String mdcTenant, @Nullable String baggageTenant) {
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
            if ("http.server.requests".equals(context.getName())) {
                stopped.add(context);
            }
        }

        /** Waits for the server observation of the request with this correlation id. */
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

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableAsync
    @Import(EdgeController.class)
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
        ServerObservations serverObservations() {
            return new ServerObservations();
        }

        @Bean
        RequestEnds requestEnds() {
            return new RequestEnds();
        }

        /** Runs after the context filter and fails, so the failure reaches the container's error dispatch. */
        @Bean
        FilterRegistrationBean<Filter> failingFilter() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) -> {
                throw new IllegalStateException("filter failed");
            });
            registration.addUrlPatterns("/edge/filter-fail");
            registration.setDispatcherTypes(DispatcherType.REQUEST);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 3);
            return registration;
        }
    }

    /** What the request thread held when the container finished the request. */
    record RequestEnd(@Nullable String mdcCorrelationId, boolean contextOpen) {
    }

    /**
     * Records, when the container destroys each request (on its thread, after the filter chain and any error
     * dispatch), the MDC correlation id and whether a request context is still open, keyed by the edge's id.
     */
    static class RequestEnds implements ServletRequestListener {

        private final Map<String, RequestEnd> ends = new ConcurrentHashMap<>();

        @Override
        public void requestDestroyed(ServletRequestEvent event) {
            Object id = event.getServletRequest().getAttribute(RestRequestAttributes.CORRELATION_ID);
            if (id instanceof String correlationId) {
                ends.put(correlationId, new RequestEnd(MDC.get("correlationId"), RequestContext.find().isPresent()));
            }
        }

        /** Waits for the end of the request with this correlation id; it may come after the response was sent. */
        RequestEnd await(String correlationId) throws InterruptedException {
            long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < end) {
                RequestEnd seen = ends.get(correlationId);
                if (seen != null) {
                    return seen;
                }
                TimeUnit.MILLISECONDS.sleep(20);
            }
            throw new AssertionError("the request with correlation id " + correlationId + " never ended");
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
            return new Seen(Thread.currentThread().getName(), Thread.currentThread().isVirtual(),
                    context.correlationId(), MDC.get("correlationId"), context.attributes(),
                    context.locale().map(Locale::toLanguageTag).orElse(null),
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

    @RestController
    static class EdgeController {

        private final EdgeOperation edge;

        private final FailingOperation failing;

        EdgeController(EdgeOperation edge, FailingOperation failing) {
            this.edge = edge;
            this.failing = failing;
        }

        @GetMapping("/edge")
        Seen edge() {
            return edge.execute("edge");
        }

        @GetMapping("/edge/fail")
        String fail() {
            return failing.execute("fail");
        }
    }
}
