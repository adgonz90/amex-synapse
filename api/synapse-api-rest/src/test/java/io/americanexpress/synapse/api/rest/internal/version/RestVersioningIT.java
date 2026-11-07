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
package io.americanexpress.synapse.api.rest.internal.version;

import java.net.URI;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.util.unit.DataSize;
import org.springframework.web.accept.StandardApiVersionDeprecationHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Serves versioned routes next to unversioned routes and an escape-hatch controller, on a real server, through
 * Spring's API versioning.
 */
@SpringBootTest(classes = RestVersioningIT.TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.logging.export.otlp.enabled=false", "management.tracing.export.otlp.enabled=false",
        "management.otlp.metrics.export.enabled=false"})
@AutoConfigureRestTestClient
@ActiveProfiles("test")
class RestVersioningIT {

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

        String code() {
            return json().get("code").asString();
        }
    }

    static Result call(RestTestClient.RequestHeadersSpec<?> spec) {
        var result = spec.exchange().returnResult(String.class);
        String body = result.getResponseBody();
        return new Result(result.getStatus().value(), result.getResponseHeaders(), body == null ? "" : body);
    }

    @Test
    @Tag("smoke")
    void versionedRoutesAreServedUnderTheirVersion() {
        int v1 = calls.v1.get();
        int v2 = calls.v2.get();
        int unversioned = calls.unversioned.get();

        Result first = call(client.get().uri("/v1/books/1"));
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.json().get("title").asString()).isEqualTo("Dune");
        assertThat(first.json().has("author")).isFalse();
        assertThat(calls.v1.get()).isEqualTo(v1 + 1);

        Result second = call(client.get().uri("/v2/books/1"));
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.json().get("title").asString()).isEqualTo("Dune");
        assertThat(second.json().get("author").asString()).isEqualTo("Herbert");
        assertThat(calls.v2.get()).isEqualTo(v2 + 1);

        assertThat(calls.v1.get()).isEqualTo(v1 + 1);
        assertThat(calls.unversioned.get()).isEqualTo(unversioned);
    }

    @Test
    void unversionedRoutesAndControllersKeepWorking() {
        int unversioned = calls.unversioned.get();

        Result book = call(client.get().uri("/books/1"));
        assertThat(book.status()).isEqualTo(200);
        assertThat(book.json().get("id").asLong()).isEqualTo(1);
        assertThat(calls.unversioned.get()).isEqualTo(unversioned + 1);

        Result ping = call(client.get().uri("/legacy/ping"));
        assertThat(ping.status()).isEqualTo(200);
        assertThat(ping.body()).isEqualTo("pong");

        Result invalid = call(client.get().uri("/books/not-a-number"));
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.code()).isEqualTo("VALIDATION_FAILED");

        Result unknown = call(client.get().uri("/nothing"));
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.code()).isEqualTo("ROUTE_NOT_FOUND");

        Result tooLarge = call(
                client
                        .post()
                        .uri("/books")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"title\":\"%s\"}"
                                .formatted(
                                        "x".repeat(200))));
        assertThat(tooLarge.status()).isEqualTo(413);
        assertThat(tooLarge.code()).isEqualTo("PAYLOAD_TOO_LARGE");

        Result created = call(client
                .post()
                .uri("/books")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"title\":"
                        + "\"Dune\"}"));
        assertThat(created.status()).isEqualTo(200);

        // Boot's FormContentFilter fails on the body before Spring MVC; the edge answers it.
        Result undecodable = call(
                client.put().uri("/books/1").contentType(MediaType.APPLICATION_FORM_URLENCODED).body("a=%zz"));
        assertThat(undecodable.status()).isEqualTo(400);
        assertThat(undecodable.code()).isEqualTo("MALFORMED_REQUEST");
        assertThat(undecodable.json().get("correlationId").asString()).isNotEmpty();

        Result updated = call(
                client.put().uri("/books/1").contentType(MediaType.APPLICATION_JSON).body("{\"title\":\"Dune\"}"));
        assertThat(updated.status()).isEqualTo(200);
    }

    @Test
    void unknownVersionIs404() {
        Result result = call(client.get().uri("/v3/books/1"));

        assertThat(result.status()).isEqualTo(404);
        assertThat(result.code()).isEqualTo("ROUTE_NOT_FOUND");
    }

    @Test
    void methodNotAllowedOnAVersionedPath() {
        Result delete = call(client.delete().uri("/v2/books/1"));

        assertThat(delete.status()).isEqualTo(405);
        assertThat(delete.code()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(delete.headers().getFirst(HttpHeaders.ALLOW)).isEqualTo("GET, HEAD, OPTIONS");

        int v2 = calls.v2.get();
        Result head = call(client.head().uri("/v2/books/1"));
        assertThat(head.status()).isEqualTo(200);
        assertThat(head.body()).isEmpty();
        assertThat(calls.v2.get()).isEqualTo(v2 + 1);
    }

    @Test
    void requiredByBootAppliesToEveryRoute() {
        try (ConfigurableApplicationContext other = start("spring.mvc.apiversion.required=true")) {
            RestTestClient otherClient = clientOf(other);

            Result unversioned = call(otherClient.get().uri("/books/1"));
            assertThat(unversioned.status()).isEqualTo(400);
            assertThat(unversioned.code()).isEqualTo("MISSING_API_VERSION");

            Result controller = call(otherClient.get().uri("/legacy/ping"));
            assertThat(controller.status()).isEqualTo(400);
            assertThat(controller.code()).isEqualTo("MISSING_API_VERSION");

            assertThat(call(otherClient.get().uri("/v2/books/1")).status()).isEqualTo(200);
        }
    }

    @Test
    void headerVersionNoRouteServesIs400() {
        try (ConfigurableApplicationContext other = start("spring.mvc.apiversion.use.header=API-Version")) {
            RestTestClient otherClient = clientOf(other);

            Result unsupported = call(otherClient.get().uri("/books/1").header("API-Version", "9"));
            assertThat(unsupported.status()).isEqualTo(400);
            assertThat(unsupported.code()).isEqualTo("UNSUPPORTED_API_VERSION");
            assertThat(unsupported.body()).doesNotContain("Exception");

            assertThat(call(otherClient.get().uri("/v2/books/1")).status()).isEqualTo(200);
            assertThat(call(otherClient.get().uri("/books/1")).status()).isEqualTo(200);
        }
    }

    @Test
    void deprecatedVersionSendsSpringsHeaders() {
        Result v1 = call(client.get().uri("/v1/books/1"));
        assertThat(v1.status()).isEqualTo(200);
        // RFC 9745: the deprecation date as @<epoch seconds>; RFC 8594: the sunset date, in Spring's RFC 1123 format.
        assertThat(v1.headers().get("Deprecation")).containsExactly("@1767225600");
        assertThat(v1.headers().get("Sunset")).containsExactly("Fri, 1 Jan 2027 00:00:00 GMT");
        assertThat(v1.headers().get(HttpHeaders.LINK))
                .singleElement()
                .satisfies(link -> assertThat(link)
                        .startsWith("<https://example.com/v1>")
                        .contains("rel=\"deprecation\""));

        Result v2 = call(client.get().uri("/v2/books/1"));
        assertThat(v2.status()).isEqualTo(200);
        assertThat(v2.headers().containsHeader("Deprecation")).isFalse();
        assertThat(v2.headers().containsHeader("Sunset")).isFalse();
        assertThat(v2.headers().containsHeader(HttpHeaders.LINK)).isFalse();

        // The page's next link is kept next to Spring's deprecation link.
        Result page = call(client.get().uri("/v1/books?size=1"));
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.headers().get("Deprecation")).containsExactly("@1767225600");
        assertThat(page.headers().get(HttpHeaders.LINK))
                .hasSize(2)
                .anySatisfy(link -> assertThat(link)
                        .startsWith("<https://example.com/v1>")
                        .contains("rel=\"deprecation\""))
                .anySatisfy(link -> assertThat(link)
                        .startsWith("</v1/books?size=1&cursor=")
                        .endsWith("rel=\"next\""));
    }

    @Test
    void cursorOfOneVersionIs400AtAnother() {
        String token = call(client.get().uri("/v1/books?size=1")).json().get("nextCursor").asString();

        Result other = call(client.get().uri("/v2/books?size=1&cursor={cursor}", token));
        assertThat(other.status()).isEqualTo(400);
        assertThat(other.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(other.json().get("violations").get(0).get("field").asString()).isEqualTo("cursor");
        assertThat(other.json().get("violations").get(0).get("message").asString())
                .isEqualTo("is not valid for this request");

        Result same = call(client.get().uri("/v1/books?size=1&cursor={cursor}", token));
        assertThat(same.status()).isEqualTo(200);
        assertThat(same.json().get("items").get(0).asString()).isEqualTo("Dune Messiah");
    }

    private static ConfigurableApplicationContext start(String property) {
        return new SpringApplicationBuilder(TestApplication.class)
                .profiles("test")
                .properties("server.port=0", "management.logging.export.otlp.enabled=false",
                        "management.tracing.export.otlp.enabled=false",
                        "management.otlp.metrics.export.enabled=false", property)
                .run();
    }

    private static RestTestClient clientOf(ConfigurableApplicationContext context) {
        return RestTestClient
                .bindToServer()
                .baseUrl("http://localhost:" + context.getEnvironment().getProperty("local.server.port"))
                .build();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(LegacyController.class)
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
        GetBookV1Operation getBookV1Operation(Calls calls) {
            return new GetBookV1Operation(calls);
        }

        @Bean
        GetBookV2Operation getBookV2Operation(Calls calls) {
            return new GetBookV2Operation(calls);
        }

        @Bean
        CreateBookOperation createBookOperation() {
            return new CreateBookOperation();
        }

        @Bean
        UpdateBookOperation updateBookOperation() {
            return new UpdateBookOperation();
        }

        @Bean
        ListBooksOperation listBooksOperation() {
            return new ListBooksOperation();
        }

        @Bean
        RestBindings books(GetBookOperation getBook, GetBookV1Operation getBookV1, GetBookV2Operation getBookV2,
                CreateBookOperation createBook, UpdateBookOperation updateBook, ListBooksOperation listBooks) {
            return RestBindings
                    .builder()
                    .get("/books/{id}", getBook)
                    .get("/books/{id}", getBookV1)
                    .version("1")
                    .get("/books/{id}", getBookV2)
                    .version("2")
                    .post("/books", createBook)
                    .maxRequestSize(DataSize.ofBytes(100))
                    .put("/books/{id}", updateBook)
                    .get("/books", listBooks)
                    .version("1")
                    .get("/books", listBooks)
                    .version("2")
                    .build();
        }

        /**
         * A bean of Spring's ApiVersionDeprecationHandler, which Boot applies: version 1 is deprecated, with a sunset
         * date and a link.
         */
        @Bean
        StandardApiVersionDeprecationHandler apiVersionDeprecationHandler() {
            StandardApiVersionDeprecationHandler handler = new StandardApiVersionDeprecationHandler();
            handler
                    .configureVersion("1")
                    .setDeprecationDate(ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC))
                    .setSunsetDate(ZonedDateTime.of(2027, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC))
                    .setDeprecationLink(URI.create("https://example.com/v1"));
            return handler;
        }
    }

    /** Counts the calls of each book operation. */
    static final class Calls {

        final AtomicInteger unversioned = new AtomicInteger();

        final AtomicInteger v1 = new AtomicInteger();

        final AtomicInteger v2 = new AtomicInteger();
    }

    /** An escape-hatch controller, outside Synapse's routes. */
    @RestController
    static class LegacyController {

        @GetMapping("/legacy/ping")
        String ping() {
            return "pong";
        }
    }

    record GetBookRequest(long id) {
    }

    record Book(long id, String title) {
    }

    record BookV2(long id, String title, String author) {
    }

    record CreateBookRequest(String title) {
    }

    record UpdateBookRequest(long id, String title) {
    }

    static class GetBookOperation implements QueryOperation<GetBookRequest, Book> {

        private final Calls calls;

        GetBookOperation(Calls calls) {
            this.calls = calls;
        }

        @Override
        public Book execute(GetBookRequest input) {
            calls.unversioned.incrementAndGet();
            return new Book(input.id(), "Dune");
        }
    }

    static class GetBookV1Operation implements QueryOperation<GetBookRequest, Book> {

        private final Calls calls;

        GetBookV1Operation(Calls calls) {
            this.calls = calls;
        }

        @Override
        public Book execute(GetBookRequest input) {
            calls.v1.incrementAndGet();
            return new Book(input.id(), "Dune");
        }
    }

    static class GetBookV2Operation implements QueryOperation<GetBookRequest, BookV2> {

        private final Calls calls;

        GetBookV2Operation(Calls calls) {
            this.calls = calls;
        }

        @Override
        public BookV2 execute(GetBookRequest input) {
            calls.v2.incrementAndGet();
            return new BookV2(input.id(), "Dune", "Herbert");
        }
    }

    static class CreateBookOperation implements CommandOperation<CreateBookRequest, Book> {
        @Override
        public Book execute(CreateBookRequest input) {
            return new Book(1, input.title());
        }
    }

    static class UpdateBookOperation implements CommandOperation<UpdateBookRequest, Book> {
        @Override
        public Book execute(UpdateBookRequest input) {
            return new Book(input.id(), input.title());
        }
    }

    /** Pages the titles with raw cursors holding the next index. */
    static class ListBooksOperation implements QueryOperation<PageQuery, PageResult<String>> {

        private static final List<String> TITLES = List.of("Dune", "Dune Messiah", "Children of Dune");

        @Override
        public PageResult<String> execute(PageQuery input) {
            String cursor = input.cursor();
            int start = cursor == null ? 0 : Integer.parseInt(cursor);
            int end = Math.min(start + input.size(), TITLES.size());
            String next = end < TITLES.size() ? String.valueOf(end) : null;
            return new PageResult<>(TITLES.subList(start, end), next, next != null, null);
        }
    }
}
