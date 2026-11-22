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
package io.americanexpress.synapse.api.rest.openapi;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /v3/api-docs} on a running service: Synapse's routes documented by Synapse, an escape-hatch controller by
 * springdoc, in one OpenAPI 3.1 document. The document is off by default, so these tests turn it on, as an
 * environment's file would.
 */
@SpringBootTest(classes = RestOpenApiIT.TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        RestOpenApiIT.NO_OTLP_LOGS, RestOpenApiIT.NO_OTLP_TRACES, RestOpenApiIT.NO_OTLP_METRICS,
        "springdoc.api-docs.enabled=true"})
@AutoConfigureRestTestClient
@ActiveProfiles("test")
class RestOpenApiIT {

    static final String NO_OTLP_LOGS = "management.logging.export.otlp.enabled=false";

    static final String NO_OTLP_TRACES = "management.tracing.export.otlp.enabled=false";

    static final String NO_OTLP_METRICS = "management.otlp.metrics.export.enabled=false";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String SCHEMAS = "#/components/schemas/";

    @Autowired
    private RestTestClient client;

    @Autowired
    private Environment environment;

    /** A response: its status, content type and body. */
    record Result(int status, String contentType, String body) {

        JsonNode json() {
            return MAPPER.readTree(body);
        }
    }

    static Result call(RestTestClient.RequestHeadersSpec<?> spec) {
        var result = spec.exchange().returnResult(String.class);
        String body = result.getResponseBody();
        return new Result(result.getStatus().value(), String.valueOf(result.getResponseHeaders().getContentType()),
                body == null ? "" : body);
    }

    static JsonNode apiDocs(RestTestClient client) {
        Result docs = call(client.get().uri("/v3/api-docs"));
        assertThat(docs.status()).as(docs.body()).isEqualTo(200);
        return docs.json();
    }

    /** The names of an object's members, in order. */
    static Set<String> names(JsonNode object) {
        assertThat(object.isObject()).as(String.valueOf(object)).isTrue();
        return new LinkedHashSet<>(object.propertyNames());
    }

    @Test
    @Tag("smoke")
    void apiDocsDescribeEveryRoute() {
        JsonNode docs = apiDocs(client);

        assertThat(docs.get("openapi").asString()).startsWith("3.1");
        JsonNode paths = docs.get("paths");
        // Every declared route: its success status and body, and problem responses.
        assertRoute(paths, "/books/{id}", "get", "200", SCHEMAS + "Book", "404");
        assertRoute(paths, "/books", "get", "200", SCHEMAS + "BookPage", "400");
        assertRoute(paths, "/books", "post", "201", SCHEMAS + "Book", "415");
        assertRoute(paths, "/books/{id}", "put", "200", SCHEMAS + "Book", "412");
        assertRoute(paths, "/books/{id}", "delete", "204", null, "412");
        assertRoute(paths, "/v2/books/{id}", "get", "200", SCHEMAS + "Book", "404");
        // Request schemas: the whole input record, or an inline object of the body's components.
        assertThat(paths.at("/~1books/post/requestBody/content/application~1json/schema/$ref").asString())
                .isEqualTo(SCHEMAS + "CreateBookRequest");
        assertThat(names(paths.at("/~1books~1{id}/put/requestBody/content/application~1json/schema/properties")))
                .containsExactly("title", "authorName", "publishedYear");
        assertThat(paths.at("/~1books~1{id}/get/parameters/0/name").asString()).isEqualTo("id");
        assertThat(names(docs.at("/components/schemas"))).contains("Book", "BookPage", "SynapseProblem");
        assertThat(names(docs.at("/components/schemas/Book/properties")))
                .containsExactly("id", "title", "authorName", "publishedYear", "version");
    }

    private static void assertRoute(JsonNode paths, String path, String method, String success,
            @Nullable String successBody, String problemStatus) {
        JsonNode operation = paths.path(path).path(method);
        assertThat(operation.isObject()).as(method + " " + path).isTrue();
        JsonNode responses = operation.get("responses");
        assertThat(responses.has(success)).as(method + " " + path + " " + success).isTrue();
        JsonNode content = responses.get(success).path("content");
        if (successBody == null) {
            assertThat(content.isMissingNode()).as(method + " " + path).isTrue();
        } else {
            assertThat(content.at("/application~1json/schema/$ref").asString()).isEqualTo(successBody);
        }
        for (String status : List.of(problemStatus, "default")) {
            assertThat(responses.at("/" + status + "/content/application~1problem+json/schema/$ref").asString())
                    .as(method + " " + path + " " + status)
                    .isEqualTo(SCHEMAS + "SynapseProblem");
        }
    }

    @Test
    void swaggerUiDefaultsOff() {
        assertThat(environment.getProperty("springdoc.swagger-ui.enabled")).isEqualTo("false");
    }

    @Test
    void escapeHatchControllerStaysDocumented() {
        JsonNode paths = apiDocs(client).get("paths");

        // springdoc's own operation for the controller's path.
        assertThat(paths.at("/~1books~1{bookId}~1reviews/get/responses/200").isObject()).isTrue();
        // The controller's PATCH /books/{bookId} is on Synapse's /books/{id}, with its variable renamed: OpenAPI sees one
        // path, so there is one entry.
        assertThat(names(paths)).doesNotContain("/books/{bookId}");
        JsonNode patch = paths.at("/~1books~1{id}/patch");
        assertThat(patch.isObject()).isTrue();
        assertThat(patch.at("/parameters/0/name").asString()).isEqualTo("id");
        // Synapse's GET, with the 304 of its .etag. It is declared before the /v2 route, which reuses its operation, so
        // it keeps the plain operationId although the more specific /v2 route comes first in route order.
        assertThat(paths.at("/~1books~1{id}/get/operationId").asString()).isEqualTo("get-book");
        assertThat(paths.at("/~1v2~1books~1{id}/get/operationId").asString()).isEqualTo("get-book-2");
        assertThat(paths.at("/~1books~1{id}/get/responses/304").isObject()).isTrue();
        assertThat(call(client.get().uri("/books/{id}/reviews", BooksApi.DUNE)).status()).isEqualTo(200);
    }

    /**
     * The same service with Synapse's defaults alone: it doesn't inherit the enclosing class's
     * {@code springdoc.api-docs.enabled=true}, so the document is off.
     */
    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(classes = RestOpenApiIT.TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            NO_OTLP_LOGS, NO_OTLP_TRACES, NO_OTLP_METRICS})
    @AutoConfigureRestTestClient
    @ActiveProfiles("test")
    class ByDefault {

        @Autowired
        private RestTestClient defaultClient;

        @Test
        @Tag("smoke")
        void apiDocsAreOffByDefault() {
            Result docs = call(defaultClient.get().uri("/v3/api-docs"));

            assertThat(docs.status()).isEqualTo(404);
            assertThat(docs.contentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            assertThat(docs.json().get("code").asString()).isEqualTo("ROUTE_NOT_FOUND");
            // The routes still answer.
            assertThat(call(defaultClient.get().uri("/books/{id}", BooksApi.DUNE)).status()).isEqualTo(200);
        }
    }

    @Nested
    @TestPropertySource(properties = "springdoc.paths-to-exclude=/v2/**")
    class PathsToExclude {

        @Test
        void excludedRoutesAreLeftOut() {
            JsonNode paths = apiDocs(client).get("paths");

            // springdoc's filter applies to Synapse's routes as to its own.
            assertThat(names(paths))
                    .doesNotContain("/v2/books/{id}")
                    .contains("/books/{id}", "/books",
                            "/books/{bookId}/reviews");
            assertThat(paths.at("/~1books~1{id}/get/operationId").asString()).isEqualTo("get-book");
            assertThat(names(paths.get("/books/{id}"))).contains("get", "put", "delete", "patch");
            // Left out of the document only: the route still answers.
            assertThat(call(client.get().uri("/v2/books/{id}", BooksApi.DUNE)).status()).isEqualTo(200);
        }
    }

    @Nested
    @TestPropertySource(properties = "spring.jackson.property-naming-strategy=SNAKE_CASE")
    class SnakeCase {

        @Test
        void documentedNamesMatchTheWireUnderSnakeCase() {
            JsonNode docs = apiDocs(client);
            JsonNode schemas = docs.at("/components/schemas");

            Result book = call(client.get().uri("/books/{id}", BooksApi.DUNE));
            assertThat(book.status()).isEqualTo(200);
            assertThat(names(schemas.at("/Book/properties"))).isEqualTo(names(book.json())).contains("author_name");

            Result page = call(client.get().uri("/books"));
            assertThat(page.status()).isEqualTo(200);
            assertThat(names(schemas.at("/BookPage/properties"))).isEqualTo(names(page.json())).contains("has_next");

            // A PUT body built from the documented names is read: every value reaches the operation.
            JsonNode put = docs.at("/paths/~1books~1{id}/put/requestBody/content/application~1json/schema/properties");
            ObjectNode body = MAPPER.createObjectNode();
            for (String name : names(put)) {
                if (put.get(name).toString().contains("integer")) {
                    body.put(name, 1969);
                } else {
                    body.put(name, "Dune Messiah " + name);
                }
            }
            assertThat(names(body)).containsExactly("title", "author_name", "published_year");
            Result updated = call(client
                    .put()
                    .uri("/books/{id}", BooksApi.DUNE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString()));
            assertThat(updated.status()).as(updated.body()).isEqualTo(200);
            for (String name : names(body)) {
                assertThat(updated.json().get(name)).as(name).isEqualTo(body.get(name));
            }
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(BooksApi.Routes.class)
    static class TestApplication {
    }
}
