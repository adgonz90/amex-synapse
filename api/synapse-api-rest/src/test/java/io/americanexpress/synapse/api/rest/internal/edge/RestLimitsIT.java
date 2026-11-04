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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sends requests over the body size limit and over the concurrency limit to a real server.
 */
@SpringBootTest(classes = RestLimitsIT.TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.logging.export.otlp.enabled=false", "management.tracing.export.otlp.enabled=false",
        "management.otlp.metrics.export.enabled=false"})
@AutoConfigureRestTestClient
@ExtendWith(OutputCaptureExtension.class)
class RestLimitsIT {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private RestTestClient client;

    @Autowired
    private Gate gate;

    @LocalServerPort
    private int port;

    /** A response: its status, content type, {@code Retry-After} header and body. */
    record Result(int status, String contentType, @Nullable String retryAfter, String body) {

        JsonNode json() {
            return MAPPER.readTree(body);
        }
    }

    static Result call(RestTestClient.RequestHeadersSpec<?> spec) {
        var result = spec.exchange().returnResult(String.class);
        return new Result(result.getStatus().value(), String.valueOf(result.getResponseHeaders().getContentType()),
                result.getResponseHeaders().getFirst("Retry-After"), String.valueOf(result.getResponseBody()));
    }

    /** A JSON object whose only property holds {@code size} bytes of text, so the body is a little larger. */
    static String payload(int size) {
        return "{\"data\":\"" + "x".repeat(size) + "\"}";
    }

    static Result post(RestTestClient client, String uri, String body) {
        return call(client.post().uri(uri).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    static void assertPayloadTooLarge(Result result) {
        assertThat(result.status()).isEqualTo(413);
        assertThat(result.contentType()).startsWith("application/problem+json");
        assertThat(result.json().get("code").asString()).isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    void bodyOverTheLimitIs413() {
        int before = gate.echoes.get();

        assertPayloadTooLarge(post(client, "/echo", payload(1_100_000)));
        assertThat(gate.echoes.get()).isEqualTo(before);

        Result small = post(client, "/echo", payload(1_000));
        assertThat(small.status()).isEqualTo(200);
        assertThat(small.json().get("length").asInt()).isEqualTo(1_000);
    }

    @Test
    void chunkedBodyOverTheLimitIs413() throws Exception {
        byte[] body = payload(1_100_000).getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + "/echo"))
                .header("Content-Type", "application/json")
                // An input stream of unknown length is sent chunked, without Content-Length.
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
                .build();
        HttpResponse<String> response;
        try (HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        }

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(MAPPER.readTree(response.body()).get("code").asString()).isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    void formBodyOverTheLimitReadBeforeSpringMvcIs413WithoutAnError(CapturedOutput output) {
        int start = output.getAll().length();

        // Boot's FormContentFilter reads PUT, PATCH and DELETE form bodies before the DispatcherServlet.
        Result result = call(client
                .put()
                .uri("/echo")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("data=" + "x".repeat(1_100_000)));

        assertPayloadTooLarge(result);
        assertNoErrorSince(output, start);
    }

    @Test
    void declaredLengthOverTheLimitIs413BeforeAnyBodyIsSent(CapturedOutput output) throws Exception {
        int start = output.getAll().length();

        String statusLine;
        String body;
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out
                    .write(("PUT /echo HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/x-www-form-urlencoded\r\n"
                            + "Content-Length: 2000000\r\nConnection: close\r\n\r\ndata=x")
                            .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            // Tomcat keeps the connection to swallow the declared body, so read the problem, not up to the end.
            String response = readProblem(socket.getInputStream());
            statusLine = response.lines().findFirst().orElse("");
            body = response.substring(response.indexOf("\r\n\r\n") + 4);
        }

        assertThat(statusLine).isEqualTo("HTTP/1.1 413 ");
        assertThat(body).contains("\"code\":\"PAYLOAD_TOO_LARGE\"");
        assertNoErrorSince(output, start);
    }

    /** Reads a response until its JSON body has closed. */
    private static String readProblem(InputStream in) throws IOException {
        StringBuilder text = new StringBuilder();
        int next;
        while ((next = in.read()) != -1) {
            text.append((char) next);
            int code = text.indexOf("\"code\"");
            if (code >= 0 && text.lastIndexOf("}") > code) {
                break;
            }
        }
        return text.toString();
    }

    private static void assertNoErrorSince(CapturedOutput output, int start) {
        assertThat(output.getAll().substring(start).lines().filter(line -> line.contains("ERROR"))).isEmpty();
    }

    @Test
    void routeMaxRequestSizeApplies() {
        assertPayloadTooLarge(post(client, "/small", payload(2_048)));

        assertThat(post(client, "/small", payload(100)).status()).isEqualTo(200);
    }

    @Test
    void requestBodyControllerOverTheLimitIs413() {
        assertPayloadTooLarge(post(client, "/legacy", payload(1_100_000)));

        assertThat(post(client, "/legacy", payload(10)).status()).isEqualTo(200);
    }

    @Nested
    @TestPropertySource(properties = "synapse.api.rest.max-concurrent-requests=1")
    class ConcurrencyLimit {

        @Autowired
        private RestTestClient limitedClient;

        @Autowired
        private Gate limitedGate;

        @Test
        void fullLimitAnswers503WithoutRunningTheOperation() throws Exception {
            limitedGate.reset();
            CompletableFuture<Result> held = CompletableFuture
                    .supplyAsync(() -> call(limitedClient.get().uri("/hold")));
            assertThat(limitedGate.entered.await(10, TimeUnit.SECONDS)).as("the first request is held").isTrue();
            Result rejected;
            try {
                rejected = call(limitedClient.get().uri("/hold"));
            } finally {
                limitedGate.release.countDown();
            }

            assertThat(rejected.status()).isEqualTo(503);
            assertThat(rejected.retryAfter()).isEqualTo("1");
            assertThat(rejected.contentType()).startsWith("application/problem+json");
            assertThat(rejected.json().get("code").asString()).isEqualTo("UNAVAILABLE");
            assertThat(held.get(10, TimeUnit.SECONDS).status()).isEqualTo(200);
            assertThat(limitedGate.holds.get()).isEqualTo(1);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(LegacyController.class)
    static class TestApplication {

        @Bean
        Gate gate() {
            return new Gate();
        }

        @Bean
        EchoOperation echoOperation(Gate gate) {
            return new EchoOperation(gate);
        }

        @Bean
        SmallOperation smallOperation() {
            return new SmallOperation();
        }

        @Bean
        HoldOperation holdOperation(Gate gate) {
            return new HoldOperation(gate);
        }

        @Bean
        RestBindings limits(EchoOperation echo, SmallOperation small, HoldOperation hold) {
            return RestBindings
                    .builder()
                    .post("/echo", echo)
                    .post("/small", small)
                    .maxRequestSize(DataSize.ofKilobytes(1))
                    .get("/hold", hold)
                    .build();
        }
    }

    /** Counts the operation calls and holds {@code /hold} until released. */
    static class Gate {

        final AtomicInteger echoes = new AtomicInteger();

        final AtomicInteger holds = new AtomicInteger();

        volatile CountDownLatch entered = new CountDownLatch(1);

        volatile CountDownLatch release = new CountDownLatch(1);

        void reset() {
            holds.set(0);
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }
    }

    record Payload(String data) {
    }

    record Length(int length) {
    }

    static class EchoOperation implements CommandOperation<Payload, Length> {

        private final Gate gate;

        EchoOperation(Gate gate) {
            this.gate = gate;
        }

        @Override
        public Length execute(Payload input) {
            gate.echoes.incrementAndGet();
            return new Length(input.data().length());
        }
    }

    static class SmallOperation implements CommandOperation<Payload, Length> {
        @Override
        public Length execute(Payload input) {
            return new Length(input.data().length());
        }
    }

    static class HoldOperation implements QueryOperation<Void, Length> {

        private final Gate gate;

        HoldOperation(Gate gate) {
            this.gate = gate;
        }

        @Override
        public Length execute(Void input) {
            gate.holds.incrementAndGet();
            gate.entered.countDown();
            try {
                if (!gate.release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the held request was not released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return new Length(0);
        }
    }

    /** An escape-hatch controller that reads the body with {@code @RequestBody}. */
    @RestController
    static class LegacyController {

        @PostMapping("/legacy")
        Length legacy(@RequestBody Payload payload) {
            return new Length(payload.data().length());
        }
    }
}
