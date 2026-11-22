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
package io.americanexpress.synapse.samples.bookstore;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.samples.bookstore.scenario.AppProcess;
import io.americanexpress.synapse.test.data.SynapseContainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenTelemetry Java agent recipe: the packaged jar on the JVM with {@code -javaagent:} and the agent jar that the
 * build copies to {@code target/otel} (the pom's {@code otel-agent} execution), in {@code dev}, with the agent's
 * exporters off. Under the agent, {@code OpenTelemetryAgentConfiguration} hands Boot's bridge the agent's SDK, so
 * Synapse's tracing check passes, an inbound W3C trace reaches Synapse's failure line, and the correlation id travels
 * as baggage to the operation's log line. The books store connects to a PostgreSQL container of its own through the
 * environment variables a platform sets.
 */
class BookstoreAgentIT {

    /** A database of its own: {@link BookstoreJvmIT}'s is the default container. */
    private static final PostgreSQLContainer BOOKS = SynapseContainers.postgres("bookstore-agent");

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private static @Nullable AppProcess bookstore;

    @BeforeAll
    static void start() {
        bookstore = AppProcess
                .jvm(AppProcess.packagedJar())
                .jvmOption("-javaagent:" + AppProcess.agentJar())
                .systemProperty("otel.service.name", "bookstore")
                .systemProperty("otel.traces.exporter", "none")
                .systemProperty("otel.metrics.exporter", "none")
                .systemProperty("otel.logs.exporter", "none")
                .env("ENV", "dev")
                .env("SYNAPSE_DATA_STORES_BOOKS_URL", BOOKS.getJdbcUrl())
                .env("SYNAPSE_DATA_STORES_BOOKS_USERNAME", BOOKS.getUsername())
                .env("SYNAPSE_DATA_STORES_BOOKS_PASSWORD", BOOKS.getPassword())
                // The beans endpoint shows which OpenTelemetry bean the bridge got; a deployed service doesn't expose it.
                .arg("--management.endpoints.web.exposure.include=health,beans")
                .output(AppProcess.logFile("agent-dev"))
                .start();
    }

    @AfterAll
    static void stop() {
        if (bookstore != null) {
            bookstore.close();
        }
    }

    /**
     * The agent ran, the bridge got the agent's SDK ({@code openTelemetryAgent}, so Boot's own SDK backed off), and
     * Synapse's tracing check passed: {@link AppProcess.Builder#start()} has already seen {@code /health} UP.
     */
    @Test
    void startsWithTheTracingRequirementMet() throws Exception {
        assertThat(running().output())
                .contains("opentelemetry-javaagent - version: ")
                .contains("The following 1 profile is active: \\\"dev\\\"")
                .doesNotContain("Synapse service operations need a tracer", "APPLICATION FAILED TO START");

        HttpResponse<String> beans = get("/actuator/beans");
        assertThat(beans.statusCode()).isEqualTo(200);
        List<String> names = new ArrayList<>();
        List<String> sdks = new ArrayList<>();
        for (JsonNode context : MAPPER.readTree(beans.body()).get("contexts")) {
            for (Map.Entry<String, JsonNode> bean : context.get("beans").properties()) {
                names.add(bean.getKey());
                if (bean.getValue().get("type").asString().equals("io.opentelemetry.sdk.OpenTelemetrySdk")) {
                    sdks.add(bean.getKey());
                }
            }
        }
        assertThat(names).as(beans.body()).contains("openTelemetryAgent");
        assertThat(sdks).as("Boot's own SDK backs off").isEmpty();
    }

    /**
     * A W3C {@code traceparent} from the caller is the trace of the request, and Synapse's failure line for it, the
     * WARN of an unknown book, carries that trace id and a correlation id. The request is a {@code DELETE}: its
     * operation fails with {@code BOOK_NOT_FOUND}, which the pipeline logs, while a {@code GET} of an unknown book is
     * an empty result that the REST binding turns into the 404, with no failure line. The line has the trace id
     * twice: {@code traceId}, from Boot's tracing (the field every Synapse log has), and {@code trace_id}, which the
     * agent's Log4j instrumentation adds.
     */
    @Test
    void inboundTraceReachesSynapseLogs() throws Exception {
        String traceId = HexFormat.of().formatHex(randomBytes(16));
        String spanId = HexFormat.of().formatHex(randomBytes(8));
        UUID book = UUID.randomUUID();

        HttpResponse<String> response = deleteUnknownBook(book,
                Map.of("traceparent", "00-" + traceId + "-" + spanId + "-01"));

        assertThat(response.statusCode()).isEqualTo(404);
        JsonNode line = failureLine(book);
        assertThat(text(line, "traceId")).isEqualTo(traceId);
        assertThat(text(line, "trace_id")).isEqualTo(traceId);
        assertThat(text(line, "correlationId")).isNotBlank();
    }

    /**
     * The correlation id the caller sent reaches the operation's failure line: Boot's tracing carries it as baggage
     * into the MDC, with the agent's SDK.
     */
    @Test
    void correlationBaggageReachesTheOperation() throws Exception {
        UUID book = UUID.randomUUID();

        HttpResponse<String> response = deleteUnknownBook(book, Map.of("X-Correlation-Id", "c-1"));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("X-Correlation-Id")).hasValue("c-1");
        JsonNode line = failureLine(book);
        assertThat(text(line, "correlationId")).isEqualTo("c-1");
        assertThat(text(line, "traceId")).isNotBlank();
    }

    /**
     * The one failure line of delete-book for the book, read from the output; the pipeline logs it before the
     * response is written, so it is there once the response has arrived. It is a WARN with the error code.
     */
    private static JsonNode failureLine(UUID book) {
        String output = running().output();
        String message = "Operation delete-book failed with BOOK_NOT_FOUND (NOT_FOUND): Book " + book
                + " was not found";
        List<JsonNode> found = new ArrayList<>();
        for (String line : output.lines().toList()) {
            if (line.startsWith("{") && line.contains(book.toString())) {
                JsonNode json = MAPPER.readTree(line);
                if (message.equals(text(json, "message"))) {
                    found.add(json);
                }
            }
        }
        assertThat(found).as(output).hasSize(1);
        JsonNode line = found.get(0);
        assertThat(line.at("/log/level").asString()).isEqualTo("WARN");
        assertThat(text(line, "errorCode")).isEqualTo("BOOK_NOT_FOUND");
        assertThat(text(line, "errorCategory")).isEqualTo("NOT_FOUND");
        return line;
    }

    /** {@code DELETE /books/<book>} with {@code If-Match: "0"}: the book doesn't exist, so the operation fails. */
    private static HttpResponse<String> deleteUnknownBook(UUID book, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest
                .newBuilder(URI.create(running().target().rest() + "/books/" + book))
                .timeout(Duration.ofSeconds(10))
                .header("If-Match", "\"0\"")
                .DELETE();
        headers.forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static @Nullable String text(JsonNode json, String field) {
        JsonNode value = json.get(field);
        return value == null ? null : value.asString();
    }

    private static HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest
                .newBuilder(URI.create(running().target().rest() + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        ThreadLocalRandom.current().nextBytes(bytes);
        // A trace or span id of all zeros is invalid.
        bytes[length - 1] |= 1;
        return bytes;
    }

    private static AppProcess running() {
        return Objects.requireNonNull(bookstore, "the bookstore didn't start");
    }
}
