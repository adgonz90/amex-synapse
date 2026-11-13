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
package ${package}.scenario;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.ErrorDetails;

import ${package}.scenario.Outcome.ItemView;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The items over REST, with the JDK's HTTP client, so it reaches any running service. It checks the status of every
 * answer against the outcome: 201 with {@code Location} and {@code ETag} for a create, 200 with an {@code ETag} for a
 * read, and for a failure the status of its code (400, 404, 409).
 */
public final class RestItemClient implements ItemClient {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final String CORRELATION = "X-Correlation-Id";

    private final URI base;

    private final HttpClient http;

    /**
     * Creates the client.
     *
     * @param base the base URI of the REST API
     */
    public RestItemClient(URI base) {
        this.base = base;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    @Override
    public Outcome create(String name, @Nullable String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (description != null) {
            body.put("description", description);
        }
        HttpResponse<String> response = send(request("/items")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body))));
        if (response.statusCode() >= 400) {
            return failure(response);
        }
        assertThat(response.statusCode()).as("POST /items status").isEqualTo(201);
        ItemView item = item(response.body());
        assertThat(response.headers().firstValue("Location")).as("Location").contains("/items/" + item.id());
        return Outcome.created(item, etag(response).orElseThrow(() -> new AssertionError("A 201 without an ETag")));
    }

    @Override
    public Outcome get(UUID id) {
        return read(send(request("/items/" + id).GET()));
    }

    /** The answer to a GET of one item. */
    private static Outcome read(HttpResponse<String> response) {
        if (response.statusCode() >= 400) {
            return failure(response);
        }
        assertThat(response.statusCode()).as("GET /items/{id} status").isEqualTo(200);
        return Outcome.ok(item(response.body()), etag(response).orElseThrow(() -> new AssertionError("No ETag")));
    }

    @Override
    public Echo echoedCorrelationId(UUID id, String correlationId) {
        HttpResponse<String> response = send(request("/items/" + id).header(CORRELATION, correlationId).GET());
        return new Echo(read(response), response.headers().firstValue(CORRELATION).orElse(null));
    }

    @Override
    public void close() {
        http.close();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(base.resolve(path)).timeout(TIMEOUT).header("Accept", "application/json");
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("The REST API didn't answer", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }

    /** A problem response: its code, its violations' fields, and the status its code must have. */
    private static Outcome failure(HttpResponse<String> response) {
        JsonNode problem = MAPPER.readTree(response.body());
        String code = problem.path("code").asString();
        List<String> violations = new ArrayList<>();
        for (JsonNode violation : problem.path("violations")) {
            violations.add(violation.path("field").asString());
        }
        Outcome failure = Outcome.failed(code, violations);
        assertThat(response.statusCode()).as("status of %s", code).isEqualTo(status(Outcome.details(code)));
        return failure;
    }

    /** The status REST answers an error with. */
    private static int status(ErrorDetails code) {
        ErrorCategory category = code.category();
        return switch (category) {
            case INVALID_INPUT -> 400;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            default -> throw new AssertionError("No scenario expects the category " + category);
        };
    }

    private static Optional<String> etag(HttpResponse<String> response) {
        return response.headers().firstValue("ETag");
    }

    private static ItemView item(String body) {
        JsonNode item = MAPPER.readTree(body);
        JsonNode description = item.path("description");
        return new ItemView(UUID.fromString(item.path("id").asString()), item.path("name").asString(),
                description.isString() ? description.asString() : null, item.path("version").asLong());
    }
}
