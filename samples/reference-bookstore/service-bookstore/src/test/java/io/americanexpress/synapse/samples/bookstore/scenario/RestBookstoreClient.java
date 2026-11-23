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
package io.americanexpress.synapse.samples.bookstore.scenario;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.samples.bookstore.scenario.Outcome.BookView;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bookstore over REST, with the JDK's HTTP client, so it reaches any running bookstore. It checks the status of
 * every answer against the outcome: 201 with {@code Location} and {@code ETag} for a create, 200 with an
 * {@code ETag} for a read and without one for an update, 204 without a body for a delete, and for a failure the
 * status of its code (400, 404, 409, 412, 428). A page's {@code Link: rel="next"} must be there exactly when the page
 * has a next page that a token reaches.
 */
public final class RestBookstoreClient implements BookstoreClient {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final String CORRELATION = "X-Correlation-Id";

    private final URI base;

    private final HttpClient http;

    private Map<String, String> lastViolations = Map.of();

    /**
     * Creates the client.
     *
     * @param base the base URI of the REST API
     */
    public RestBookstoreClient(URI base) {
        this.base = base;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    @Override
    public Outcome create(String isbn, @Nullable String title, String author, @Nullable Integer year) {
        HttpResponse<String> response = send(
                request("/books").header("Content-Type", "application/json").POST(json(isbn, title, author, year)));
        if (response.statusCode() >= 400) {
            return failure(response);
        }
        assertThat(response.statusCode()).as("POST /books status").isEqualTo(201);
        BookView book = book(response.body());
        assertThat(response.headers().firstValue("Location")).as("Location").contains("/books/" + book.id());
        return Outcome.created(book, etag(response).orElseThrow(() -> new AssertionError("A 201 without an ETag")));
    }

    @Override
    public Outcome get(UUID id) {
        return read(send(request("/books/" + id).GET()));
    }

    /** The answer to a GET of one book. */
    private Outcome read(HttpResponse<String> response) {
        if (response.statusCode() >= 400) {
            return failure(response);
        }
        assertThat(response.statusCode()).as("GET /books/{id} status").isEqualTo(200);
        return Outcome.ok(book(response.body()), etag(response).orElseThrow(() -> new AssertionError("No ETag")));
    }

    @Override
    public Outcome update(UUID id, @Nullable String etag, String isbn, String title, String author,
            @Nullable Integer year) {
        HttpRequest.Builder request = request("/books/" + id)
                .header("Content-Type", "application/json")
                .PUT(json(isbn, title, author, year));
        if (etag != null) {
            request.header("If-Match", etag);
        }
        HttpResponse<String> response = send(request);
        if (response.statusCode() >= 400) {
            return failure(response);
        }
        assertThat(response.statusCode()).as("PUT /books/{id} status").isEqualTo(200);
        // RFC 9110 9.3.4: no ETag on the answer to a PUT; the client reads the book again for its new one.
        assertThat(etag(response)).as("ETag of a PUT").isEmpty();
        return Outcome.ok(book(response.body()), null);
    }

    @Override
    public Outcome delete(UUID id, @Nullable String etag) {
        HttpRequest.Builder request = request("/books/" + id).DELETE();
        if (etag != null) {
            request.header("If-Match", etag);
        }
        HttpResponse<String> response = send(request);
        if (response.statusCode() >= 400) {
            return failure(response);
        }
        assertThat(response.statusCode()).as("DELETE /books/{id} status").isEqualTo(204);
        assertThat(response.body()).as("body of a 204").isEmpty();
        return Outcome.noContent();
    }

    @Override
    public SearchOutcome search(@Nullable String author, int size, @Nullable String token, @Nullable Long offset,
            @Nullable String sort) {
        StringJoiner query = new StringJoiner("&", "?", "");
        if (author != null) {
            query.add("author=" + encode(author));
        }
        query.add("size=" + size);
        if (token != null) {
            query.add("cursor=" + encode(token));
        }
        if (offset != null) {
            query.add("offset=" + offset);
        }
        if (sort != null) {
            query.add("sort=" + encode(sort));
        }
        HttpResponse<String> response = send(request("/books" + query).GET());
        if (response.statusCode() >= 400) {
            Outcome failure = failure(response);
            return SearchOutcome.failed(failure.code(), failure.violations());
        }
        assertThat(response.statusCode()).as("GET /books status").isEqualTo(200);
        JsonNode page = MAPPER.readTree(response.body());
        List<BookView> books = new ArrayList<>();
        for (JsonNode item : page.path("items")) {
            books.add(book(item));
        }
        @Nullable
        String nextToken = text(page.path("nextCursor"));
        boolean hasNext = page.path("hasNext").asBoolean();
        Optional<String> link = response.headers().firstValue("Link");
        // REST's signal of a next page: a Link to it, with the token or the next offset.
        assertThat(link.isPresent()).as("Link: rel=\"next\" when hasNext is %s", hasNext).isEqualTo(hasNext);
        link.ifPresent(next -> assertThat(next).as("Link").matches("</books\\?.*(cursor|offset)=[^>]+>; rel=\"next\""));
        if (!hasNext) {
            assertThat(nextToken).as("nextCursor without a next page").isNull();
        }
        JsonNode total = page.path("total");
        return SearchOutcome.page(books, nextToken, hasNext, total.isNumber() ? total.asLong() : null);
    }

    @Override
    public Echo echoedCorrelationId(UUID id, String correlationId) {
        HttpResponse<String> response = send(request("/books/" + id).header(CORRELATION, correlationId).GET());
        return new Echo(read(response), response.headers().firstValue(CORRELATION).orElse(null));
    }

    @Override
    public Map<String, String> lastViolations() {
        return lastViolations;
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

    private static HttpRequest.BodyPublisher json(String isbn, @Nullable String title, String author,
            @Nullable Integer year) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("isbn", isbn);
        if (title != null) {
            body.put("title", title);
        }
        body.put("author", author);
        if (year != null) {
            body.put("publishedYear", year);
        }
        return HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body));
    }

    /**
     * A problem response: its code, its violations' fields (their messages kept in {@link #lastViolations()}), and the
     * status its code must have.
     */
    private Outcome failure(HttpResponse<String> response) {
        JsonNode problem = MAPPER.readTree(response.body());
        String code = problem.path("code").asString();
        List<String> violations = new ArrayList<>();
        Map<String, String> messages = new LinkedHashMap<>();
        for (JsonNode violation : problem.path("violations")) {
            String field = violation.path("field").asString();
            violations.add(field);
            messages.put(field, violation.path("message").asString());
        }
        lastViolations = messages;
        Outcome failure = Outcome.failed(code, violations);
        assertThat(response.statusCode()).as("status of %s", code).isEqualTo(status(Outcome.details(code)));
        return failure;
    }

    /** The status REST answers an error with. */
    private static int status(ErrorDetails code) {
        if (code == SynapseErrors.PRECONDITION_REQUIRED) {
            return 428;
        }
        ErrorCategory category = code.category();
        return switch (category) {
            case INVALID_INPUT -> 400;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            case PRECONDITION_FAILED -> 412;
            default -> throw new AssertionError("No scenario expects the category " + category);
        };
    }

    private static Optional<String> etag(HttpResponse<String> response) {
        return response.headers().firstValue("ETag");
    }

    private static BookView book(String body) {
        return book(MAPPER.readTree(body));
    }

    private static BookView book(JsonNode book) {
        JsonNode year = book.path("publishedYear");
        return new BookView(UUID.fromString(book.path("id").asString()), book.path("isbn").asString(),
                book.path("title").asString(), book.path("author").asString(), year.isNumber() ? year.asInt() : null,
                book.path("version").asLong());
    }

    private static @Nullable String text(JsonNode node) {
        return node.isString() ? node.asString() : null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
