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
package io.americanexpress.synapse.api.rest.internal.cors;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CORS on a real server, with {@code https://app.example.com} allowed: pre-flights answered at the edge, rejections as
 * problem responses, and the CORS headers on every response Synapse writes, its own error responses included.
 */
@SpringBootTest(classes = RestCorsIT.TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.logging.export.otlp.enabled=false", "management.tracing.export.otlp.enabled=false",
        "management.otlp.metrics.export.enabled=false"})
// In @TestPropertySource, so that a nested class can override it.
@TestPropertySource(properties = "synapse.api.rest.cors.allowed-origins=" + RestCorsIT.APP)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
class RestCorsIT {

    static final String APP = "https://app.example.com";

    static final String EVIL = "https://evil.example";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private RestTestClient client;

    @Autowired
    private Calls calls;

    /** A response: its status, headers and body. */
    record Result(int status, HttpHeaders headers, String body) {

        JsonNode json() {
            return MAPPER.readTree(body);
        }

        String allowOrigin() {
            return String.valueOf(headers.getAccessControlAllowOrigin());
        }
    }

    static Result call(RestTestClient.RequestHeadersSpec<?> spec) {
        var result = spec.exchange().returnResult(String.class);
        String body = result.getResponseBody();
        return new Result(result.getStatus().value(), result.getResponseHeaders(), body == null ? "" : body);
    }

    private Result preflight(String path, String method) {
        return call(client
                .options()
                .uri(path)
                .header(HttpHeaders.ORIGIN, APP)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method));
    }

    private static void assertProblem(Result result, int status, String code) {
        assertThat(result.status()).as(code).isEqualTo(status);
        assertThat(String.valueOf(result.headers().getContentType()))
                .as(code)
                .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(result.json().get("code").asString()).as(code).isEqualTo(code);
        assertThat(result.allowOrigin()).as(code).isEqualTo(APP);
        assertThat(result.headers().getAccessControlExposeHeaders()).as(code).contains("X-Correlation-Id");
    }

    @Test
    @Tag("smoke")
    void preflightIsAllowed() {
        int reads = calls.reads.get();
        int updates = calls.updates.get();

        Result result = call(client
                .options()
                .uri("/books/1")
                .header(HttpHeaders.ORIGIN, APP)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "If-Match"));

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.allowOrigin()).isEqualTo(APP);
        assertThat(result.headers().getAccessControlAllowMethods()).extracting(Object::toString).contains("PUT");
        assertThat(result.headers().getAccessControlAllowHeaders()).containsExactly("If-Match");
        assertThat(result.headers().getAccessControlMaxAge()).isEqualTo(1800);
        assertThat(result.headers().getVary())
                .contains(HttpHeaders.ORIGIN, HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
                        HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);
        assertThat(result.body()).isEmpty();
        assertThat(calls.reads.get()).isEqualTo(reads);
        assertThat(calls.updates.get()).isEqualTo(updates);
    }

    @Test
    void preflightOnAFallbackPath() {
        // GET /books is the only route on the path; the allowed methods are global, so the pre-flight is allowed.
        Result preflight = preflight("/books", "DELETE");

        assertThat(preflight.status()).isEqualTo(200);
        assertThat(preflight.allowOrigin()).isEqualTo(APP);
        assertThat(preflight.headers().getAccessControlAllowMethods()).extracting(Object::toString).contains("DELETE");
        assertThat(preflight.headers().getAllow()).isEmpty();

        // The actual request then gets the fallback's 405, which the page can read.
        Result actual = call(client.delete().uri("/books").header(HttpHeaders.ORIGIN, APP));

        assertProblem(actual, 405, "METHOD_NOT_ALLOWED");
        assertThat(actual.headers().getFirst(HttpHeaders.ALLOW)).contains("GET");
    }

    @Test
    void disallowedOriginIs403Problem() {
        int reads = calls.reads.get();

        Result result = call(client.get().uri("/books/1").header(HttpHeaders.ORIGIN, EVIL));

        assertThat(result.status()).isEqualTo(403);
        assertThat(String.valueOf(result.headers().getContentType()))
                .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(result.json().get("code").asString()).isEqualTo("CORS_REJECTED");
        assertThat(result.json().get("correlationId").asString()).isNotEmpty();
        assertThat(result.headers().getFirst("X-Correlation-Id"))
                .isEqualTo(result.json().get("correlationId").asString());
        assertThat(result.headers().getAccessControlAllowOrigin()).isNull();
        assertThat(result.headers().getVary()).contains(HttpHeaders.ORIGIN);
        assertThat(result.body()).doesNotContain("Invalid CORS request").doesNotContain("Exception");
        assertThat(calls.reads.get()).isEqualTo(reads);

        // A pre-flight from that origin too.
        Result preflight = call(client
                .options()
                .uri("/books/1")
                .header(HttpHeaders.ORIGIN, EVIL)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT"));
        assertThat(preflight.status()).isEqualTo(403);
        assertThat(preflight.json().get("code").asString()).isEqualTo("CORS_REJECTED");
        assertThat(preflight.headers().getAccessControlAllowOrigin()).isNull();
        assertThat(preflight.headers().getVary()).contains(HttpHeaders.ORIGIN);
    }

    @Test
    void actualRequestExposesSynapseHeaders() {
        int reads = calls.reads.get();

        Result result = call(client.get().uri("/books/1").header(HttpHeaders.ORIGIN, APP));

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.json().get("title").asString()).isEqualTo("Dune");
        assertThat(result.allowOrigin()).isEqualTo(APP);
        assertThat(result.headers().getAccessControlExposeHeaders())
                .contains("X-Correlation-Id", "ETag", "Link", "Location");
        assertThat(result.headers().getAccessControlAllowCredentials()).isFalse();
        assertThat(calls.reads.get()).isEqualTo(reads + 1);
    }

    @Test
    void problemResponsesCarryCorsHeaders() {
        // No route.
        assertProblem(call(client.get().uri("/nope").header(HttpHeaders.ORIGIN, APP)), 404, "ROUTE_NOT_FOUND");
        // The 405 fallback.
        assertProblem(call(client.delete().uri("/books").header(HttpHeaders.ORIGIN, APP)), 405,
                "METHOD_NOT_ALLOWED");
        // Binding.
        assertProblem(call(client
                .put()
                .uri("/books/1")
                .header(HttpHeaders.ORIGIN, APP)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{bad")), 400, "MALFORMED_REQUEST");
        // The size filter.
        assertProblem(call(client
                .post()
                .uri("/echo")
                .header(HttpHeaders.ORIGIN, APP)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"data\":\"" + "x".repeat(2_048) + "\"}")), 413, "PAYLOAD_TOO_LARGE");
        // A form body that Boot's FormContentFilter can't decode, answered by the client error filter.
        assertProblem(call(client
                .put()
                .uri("/books/1")
                .header(HttpHeaders.ORIGIN, APP)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("a=%zz")), 400, "MALFORMED_REQUEST");
        // A failure that escapes the filter chain and reaches the error controller on the error dispatch.
        assertProblem(call(client.get().uri("/filter-boom").header(HttpHeaders.ORIGIN, APP)), 500, "INTERNAL");
        // The same after a filter reset the response, headers included: the error dispatch sets them again.
        assertProblem(call(client.get().uri("/reset-boom").header(HttpHeaders.ORIGIN, APP)), 500, "INTERNAL");
    }

    @Test
    void etagRouteVaryKeepsOriginAndAccept() {
        Result read = call(client.get().uri("/books/1").header(HttpHeaders.ORIGIN, APP));

        assertThat(read.headers().getETag()).isEqualTo("\"1\"");
        assertThat(read.headers().getVary())
                .contains(HttpHeaders.ACCEPT, HttpHeaders.ORIGIN, HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
                        HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);

        Result notModified = call(client
                .get()
                .uri("/books/1")
                .header(HttpHeaders.ORIGIN, APP)
                .header(HttpHeaders.IF_NONE_MATCH, "\"1\""));

        assertThat(notModified.status()).isEqualTo(304);
        assertThat(notModified.allowOrigin()).isEqualTo(APP);
        assertThat(notModified.headers().getVary()).contains(HttpHeaders.ACCEPT, HttpHeaders.ORIGIN);
    }

    @Nested
    @TestPropertySource(properties = "synapse.api.rest.cors.allowed-origins=")
    class WithoutOrigins {

        @Autowired
        private RestTestClient offClient;

        @Test
        void corsIsOffWithoutOrigins() {
            Result read = call(offClient.get().uri("/books/1").header(HttpHeaders.ORIGIN, EVIL));

            assertThat(read.status()).isEqualTo(200);
            assertThat(read.headers().getAccessControlAllowOrigin()).isNull();

            Result preflight = call(offClient
                    .options()
                    .uri("/books/1")
                    .header(HttpHeaders.ORIGIN, EVIL)
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT"));

            // Without a configuration, Spring MVC answers it as before CORS existed: its pre-flight handler writes
            // nothing, and the servlet's default OPTIONS answer lists every method it serves.
            assertThat(preflight.status()).isEqualTo(200);
            assertThat(preflight.headers().getAllow())
                    .extracting(Object::toString)
                    .containsExactlyInAnyOrder("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS", "TRACE", "PATCH");
            assertThat(preflight.body()).isEmpty();
            assertThat(preflight.headers().getAccessControlAllowOrigin()).isNull();
            assertThat(preflight.headers().getAccessControlAllowMethods()).isEmpty();
        }
    }

    @Nested
    @TestPropertySource(properties = "synapse.api.rest.cors.allow-credentials=true")
    class WithCredentials {

        @Autowired
        private RestTestClient credentialsClient;

        @Test
        void credentialsEchoTheOrigin() {
            Result read = call(credentialsClient.get().uri("/books/1").header(HttpHeaders.ORIGIN, APP));

            assertThat(read.status()).isEqualTo(200);
            assertThat(read.headers().getAccessControlAllowCredentials()).isTrue();
            assertThat(read.allowOrigin()).isEqualTo(APP);

            Result preflight = call(credentialsClient
                    .options()
                    .uri("/books/1")
                    .header(HttpHeaders.ORIGIN, APP)
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT"));

            assertThat(preflight.status()).isEqualTo(200);
            assertThat(preflight.headers().getAccessControlAllowCredentials()).isTrue();
            assertThat(preflight.allowOrigin()).isEqualTo(APP);
        }
    }

    @Nested
    @TestPropertySource(properties = "synapse.api.rest.max-concurrent-requests=1")
    class ConcurrencyLimit {

        @Autowired
        private RestTestClient limitedClient;

        @Autowired
        private Calls limitedCalls;

        @Test
        void limitResponseCarriesCorsHeaders() throws Exception {
            limitedCalls.resetGate();
            CompletableFuture<Result> held = CompletableFuture
                    .supplyAsync(() -> call(limitedClient.get().uri("/hold")));
            assertThat(limitedCalls.entered.await(10, TimeUnit.SECONDS)).as("the first request is held").isTrue();
            Result rejected;
            try {
                rejected = call(limitedClient.get().uri("/hold").header(HttpHeaders.ORIGIN, APP));
            } finally {
                limitedCalls.release.countDown();
            }

            assertProblem(rejected, 503, "UNAVAILABLE");
            assertThat(rejected.headers().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
            assertThat(held.get(10, TimeUnit.SECONDS).status()).isEqualTo(200);
            assertThat(limitedCalls.holds.get()).isEqualTo(1);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {

        @Bean
        Calls calls() {
            return new Calls();
        }

        @Bean
        GetBookOperation getBookOperation(Calls calls) {
            return new GetBookOperation(calls);
        }

        @Bean
        ListBooksOperation listBooksOperation() {
            return new ListBooksOperation();
        }

        @Bean
        UpdateBookOperation updateBookOperation(Calls calls) {
            return new UpdateBookOperation(calls);
        }

        @Bean
        EchoOperation echoOperation() {
            return new EchoOperation();
        }

        @Bean
        HoldOperation holdOperation(Calls calls) {
            return new HoldOperation(calls);
        }

        @Bean
        RestBindings books(GetBookOperation getBook, ListBooksOperation listBooks, UpdateBookOperation updateBook,
                EchoOperation echo, HoldOperation hold) {
            return RestBindings
                    .builder()
                    .get("/books/{id}", getBook)
                    .etag(Book::version)
                    .put("/books/{id}", updateBook)
                    .get("/books", listBooks)
                    .post("/echo", echo)
                    .maxRequestSize(DataSize.ofKilobytes(1))
                    .get("/hold", hold)
                    .build();
        }

        /**
         * A filter of the application that fails on {@code /filter-boom}, and on {@code /reset-boom} after resetting
         * the response, so the error controller answers.
         */
        @Bean
        FilterRegistrationBean<OncePerRequestFilter> failingFilter() {
            FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(
                    new OncePerRequestFilter() {
                        @Override
                        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                FilterChain chain) throws ServletException, IOException {
                            if (request.getRequestURI().equals("/reset-boom")) {
                                response.reset();
                                throw new IllegalStateException("secret filter internals");
                            }
                            if (request.getRequestURI().equals("/filter-boom")) {
                                throw new IllegalStateException("secret filter internals");
                            }
                            chain.doFilter(request, response);
                        }
                    });
            registration.setDispatcherTypes(DispatcherType.REQUEST);
            registration.setOrder(0);
            return registration;
        }
    }

    /** Counts the operation calls, and holds {@code /hold} until released. */
    static class Calls {

        final AtomicInteger reads = new AtomicInteger();

        final AtomicInteger updates = new AtomicInteger();

        final AtomicInteger holds = new AtomicInteger();

        volatile CountDownLatch entered = new CountDownLatch(1);

        volatile CountDownLatch release = new CountDownLatch(1);

        void resetGate() {
            holds.set(0);
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }
    }

    record Book(String id, String title, long version) {
    }

    record BookId(String id) {
    }

    record UpdateBookRequest(String id, String title) {
    }

    record Payload(String data) {
    }

    static class GetBookOperation implements QueryOperation<BookId, Book> {

        private final Calls calls;

        GetBookOperation(Calls calls) {
            this.calls = calls;
        }

        @Override
        public Book execute(BookId input) {
            calls.reads.incrementAndGet();
            return new Book(input.id(), "Dune", 1);
        }
    }

    static class ListBooksOperation implements QueryOperation<Void, List<Book>> {

        @Override
        public List<Book> execute(Void input) {
            return List.of(new Book("1", "Dune", 1));
        }
    }

    static class UpdateBookOperation implements CommandOperation<UpdateBookRequest, Book> {

        private final Calls calls;

        UpdateBookOperation(Calls calls) {
            this.calls = calls;
        }

        @Override
        public Book execute(UpdateBookRequest input) {
            calls.updates.incrementAndGet();
            return new Book(input.id(), input.title(), 2);
        }
    }

    static class EchoOperation implements CommandOperation<Payload, Payload> {

        @Override
        public Payload execute(Payload input) {
            return input;
        }
    }

    static class HoldOperation implements QueryOperation<Void, Payload> {

        private final Calls calls;

        HoldOperation(Calls calls) {
            this.calls = calls;
        }

        @Override
        public Payload execute(Void input) {
            calls.holds.incrementAndGet();
            calls.entered.countDown();
            try {
                if (!calls.release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the held request was not released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return new Payload("held");
        }
    }
}
