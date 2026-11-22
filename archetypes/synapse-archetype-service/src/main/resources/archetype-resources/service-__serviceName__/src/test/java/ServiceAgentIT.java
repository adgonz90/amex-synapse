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
package ${package};

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

import io.americanexpress.synapse.test.data.SynapseContainers;

import ${package}.scenario.AppProcess;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenTelemetry Java agent recipe: the packaged jar on the JVM with {@code -javaagent:} and the agent jar that the
 * build copies to {@code target/otel} (the pom's {@code otel-agent} execution), in {@code dev}, with the agent's
 * exporters off. Under the agent, {@code OpenTelemetryAgentConfiguration} hands Boot's bridge the agent's SDK, so
 * Synapse's tracing check passes, an inbound W3C trace reaches Synapse's failure line, and the correlation id travels
 * as baggage to the operation's log line. The ${serviceName} store connects to a PostgreSQL container of its own
 * through the environment variables a platform sets. When you upgrade Synapse or the agent, this is the test that shows
 * the two still work together.
 */
class ServiceAgentIT {

    /** A database of its own: {@link ServiceJvmIT}'s is the default container. */
    private static final PostgreSQLContainer STORE = SynapseContainers.postgres("${serviceName}-agent");

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** What the operation pipeline logs when a create fails on the table's unique constraint. */
    private static final String FAILURE = "Operation create-item failed with ALREADY_EXISTS (CONFLICT): "
            + "The resource already exists";

    private static @Nullable AppProcess service;

    @BeforeAll
    static void start() {
        service = AppProcess
                .jvm(AppProcess.packagedJar())
                .jvmOption("-javaagent:" + AppProcess.agentJar())
                .systemProperty("otel.service.name", "${serviceName}")
                .systemProperty("otel.traces.exporter", "none")
                .systemProperty("otel.metrics.exporter", "none")
                .systemProperty("otel.logs.exporter", "none")
                .env("ENV", "dev")
                .env("SYNAPSE_DATA_STORES_${serviceName.toUpperCase()}_URL", STORE.getJdbcUrl())
                .env("SYNAPSE_DATA_STORES_${serviceName.toUpperCase()}_USERNAME", STORE.getUsername())
                .env("SYNAPSE_DATA_STORES_${serviceName.toUpperCase()}_PASSWORD", STORE.getPassword())
                // The beans endpoint shows which OpenTelemetry bean the bridge got; a deployed service doesn't expose it.
                .arg("--management.endpoints.web.exposure.include=health,beans")
                .output(AppProcess.logFile("agent-dev"))
                .start();
    }

    @AfterAll
    static void stop() {
        if (service != null) {
            service.close();
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

        HttpResponse<String> beans = send(request("/actuator/beans").GET());
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
     * WARN of a create whose name another item has, carries that trace id. The line has the trace id twice:
     * {@code traceId}, from Boot's tracing (the field every Synapse log has), and {@code trace_id}, which the agent's
     * Log4j instrumentation adds.
     */
    @Test
    void inboundTraceReachesSynapseLogs() throws Exception {
        String traceId = HexFormat.of().formatHex(randomBytes(16));
        String spanId = HexFormat.of().formatHex(randomBytes(8));
        String correlationId = "trace-" + UUID.randomUUID();

        HttpResponse<String> response = createDuplicate(Map.of("traceparent", "00-" + traceId + "-" + spanId + "-01",
                "X-Correlation-Id", correlationId));

        assertThat(response.statusCode()).isEqualTo(409);
        JsonNode line = failureLine(correlationId);
        assertThat(text(line, "traceId")).isEqualTo(traceId);
        assertThat(text(line, "trace_id")).isEqualTo(traceId);
    }

    /**
     * The correlation id the caller sent is echoed and reaches the operation's failure line: Boot's tracing carries it
     * as baggage into the MDC, with the agent's SDK.
     */
    @Test
    void correlationBaggageReachesTheOperation() throws Exception {
        String correlationId = "c-" + UUID.randomUUID();

        HttpResponse<String> response = createDuplicate(Map.of("X-Correlation-Id", correlationId));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.headers().firstValue("X-Correlation-Id")).hasValue(correlationId);
        JsonNode line = failureLine(correlationId);
        assertThat(text(line, "traceId")).isNotBlank();
    }

    /**
     * Adds an item, then sends {@code POST /items} again with the same name and the headers: the table's unique
     * constraint fails the second create, which the pipeline logs.
     */
    private static HttpResponse<String> createDuplicate(Map<String, String> headers)
            throws IOException, InterruptedException {
        String body = "{\"name\": \"Item " + UUID.randomUUID() + "\"}";
        assertThat(send(create(body)).statusCode()).isEqualTo(201);
        HttpRequest.Builder duplicate = create(body);
        headers.forEach(duplicate::header);
        return send(duplicate);
    }

    /**
     * The one failure line of create-item with the correlation id, read from the output; the pipeline logs it before
     * the response is written, so it is there once the response has arrived. It is a WARN with the error code.
     */
    private static JsonNode failureLine(String correlationId) {
        String output = running().output();
        List<JsonNode> found = new ArrayList<>();
        for (String line : output.lines().toList()) {
            if (line.startsWith("{") && line.contains(correlationId)) {
                JsonNode json = MAPPER.readTree(line);
                if (FAILURE.equals(text(json, "message"))) {
                    found.add(json);
                }
            }
        }
        assertThat(found).as(output).hasSize(1);
        JsonNode line = found.get(0);
        assertThat(line.at("/log/level").asString()).isEqualTo("WARN");
        assertThat(text(line, "errorCode")).isEqualTo("ALREADY_EXISTS");
        assertThat(text(line, "errorCategory")).isEqualTo("CONFLICT");
        assertThat(text(line, "correlationId")).isEqualTo(correlationId);
        return line;
    }

    private static HttpRequest.Builder create(String body) {
        return request("/items")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private static HttpRequest.Builder request(String path) {
        return HttpRequest
                .newBuilder(URI.create(running().target().rest() + path))
                .timeout(Duration.ofSeconds(10));
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static @Nullable String text(JsonNode json, String field) {
        JsonNode value = json.get(field);
        return value == null ? null : value.asString();
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        ThreadLocalRandom.current().nextBytes(bytes);
        // A trace or span id of all zeros is invalid.
        bytes[length - 1] |= 1;
        return bytes;
    }

    private static AppProcess running() {
        return Objects.requireNonNull(service, "the service didn't start");
    }
}
