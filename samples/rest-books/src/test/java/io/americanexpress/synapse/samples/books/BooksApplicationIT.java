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
package io.americanexpress.synapse.samples.books;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.samples.books.api.grpc.proto.Book;
import io.americanexpress.synapse.samples.books.api.grpc.proto.BookServiceGrpc;
import io.americanexpress.synapse.samples.books.api.grpc.proto.GetBookRequest;
import io.americanexpress.synapse.samples.books.data.InMemoryBookStore;
import io.americanexpress.synapse.samples.books.data.StoredBook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;

/**
 * The whole service on a real server in the {@code test} environment, with {@code synapse.errors.include-detail=false}
 * pinned so it checks the safe defaults: problem responses carry no {@code detail}. (The sample's
 * {@code application-test.yaml} turns detail on for the slice tests.)
 *
 * <p>
 * The tests share the catalog (one Spring context). The searches expect the three seeded books, so every test that
 * adds a book deletes it, and no test changes a seeded book. The nested classes, which turn the OpenAPI document on,
 * turn CORS on and set a context path, run their own contexts.
 *
 * <p>
 * The gRPC server runs too, on Netty at a free port ({@code spring.grpc.server.port=0}); each nested context starts its
 * own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "synapse.errors.include-detail=false")
@TestPropertySource(properties = "spring.grpc.server.port=0")
@AutoConfigureRestTestClient
@Tag("smoke")
@ActiveProfiles("test")
class BooksApplicationIT {

    private static final String DUNE = "/books/00000000-0000-0000-0000-000000000001";

    /** The front end that application-local.yaml allows; the nested classes allow it too. */
    static final String LOCAL_FRONT_END = "http://localhost:3000";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    RestTestClient client;

    /** The real store, spied on so that one test can simulate a concurrent write. */
    @MockitoSpyBean
    InMemoryBookStore store;

    @LocalGrpcServerPort
    int grpcPort;

    /** A response: its status, headers and body. */
    record Result(int status, HttpHeaders headers, String body) {

        JsonNode json() {
            return MAPPER.readTree(body);
        }
    }

    static Result call(RestTestClient.RequestHeadersSpec<?> spec) {
        var result = spec.exchange().returnResult(String.class);
        String body = result.getResponseBody();
        return new Result(result.getStatus().value(), result.getResponseHeaders(), body == null ? "" : body);
    }

    @Test
    void servesASeededBook() {
        client
                .get()
                .uri(DUNE)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.title")
                .isEqualTo("Dune")
                .jsonPath("$.author")
                .isEqualTo("Frank Herbert")
                .jsonPath("$.version")
                .isEqualTo(1);
    }

    @Test
    void echoesTheCorrelationId() {
        client
                .get()
                .uri(DUNE)
                .header("X-Correlation-Id", "it-correlation-1")
                .exchange()
                .expectHeader()
                .valueEquals("X-Correlation-Id", "it-correlation-1");
    }

    @Test
    void createsABookAtItsLocationThenDeletesIt() {
        var created = client
                .post()
                .uri("/books")
                .header("X-Channel", "it")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"isbn\":\"isbn-it-1\",\"title\":\"The Dispossessed\",\"author\":\"Ursula K. Le Guin\"}")
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody()
                .jsonPath("$.addedVia")
                .isEqualTo("it")
                .returnResult();
        String location = Objects.requireNonNull(created.getResponseHeaders().getLocation()).toString();
        assertThat(location).matches("/books/[0-9a-f-]{36}");

        client
                .get()
                .uri(location)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.title")
                .isEqualTo("The Dispossessed");
        client
                .delete()
                .uri(location)
                .header("If-Match", "\"1\"")
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody()
                .isEmpty();
        client.get().uri(location).exchange().expectStatus().isNotFound();
    }

    @Test
    void postSendsETag() {
        Result created = create("isbn-it-post", "The Word for World Is Forest");

        assertThat(created.headers().getLocation()).isNotNull();
        assertThat(created.headers().getETag()).isEqualTo("\"1\"");
        assertThat(created.json().get("version").asLong()).isEqualTo(1);

        deleteAt(created, "\"1\"");
    }

    @Test
    void searchPagesWithCursorAndLink() {
        Result first = call(client.get().uri("/books?sort=title&size=2"));

        assertThat(first.status()).isEqualTo(200);
        assertThat(titles(first)).containsExactly("A Wizard of Earthsea", "Dune");
        assertThat(first.json().get("hasNext").asBoolean()).isTrue();
        assertThat(first.json().get("total").asLong()).isEqualTo(3);
        String token = first.json().get("nextCursor").asString();
        // The operation's cursor is the next index ("2"); the client only ever sees the encrypted token.
        assertThat(token).isNotEqualTo("2");
        String link = first.headers().getFirst(HttpHeaders.LINK);
        assertThat(link).isEqualTo("</books?sort=title&size=2&cursor=" + token + ">; rel=\"next\"");

        Result last = call(client.get().uri(linkTarget(Objects.requireNonNull(link))));

        assertThat(last.status()).isEqualTo(200);
        assertThat(titles(last)).containsExactly("The Left Hand of Darkness");
        assertThat(last.json().get("hasNext").asBoolean()).isFalse();
        assertThat(last.json().has("nextCursor")).isFalse();
        assertThat(last.headers().containsHeader(HttpHeaders.LINK)).isFalse();
    }

    @Test
    void searchSortsByYearDescending() {
        Result page = call(client.get().uri("/books?sort=-year"));

        assertThat(titles(page)).containsExactly("The Left Hand of Darkness", "A Wizard of Earthsea", "Dune");
        assertThat(page.json().get("hasNext").asBoolean()).isFalse();
    }

    @Test
    void searchRejectsUnknownSortAndTamperedCursor() {
        Result unknownSort = call(client.get().uri("/books?sort=price"));

        assertThat(unknownSort.status()).isEqualTo(400);
        assertThat(unknownSort.json().get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertViolation(unknownSort, "sort", "has a key that is not allowed (allowed: title, year)");

        String token = call(client.get().uri("/books?sort=title&size=2")).json().get("nextCursor").asString();
        int middle = token.length() / 2;
        String tampered = token.substring(0, middle) + (token.charAt(middle) == 'A' ? 'B' : 'A')
                + token.substring(middle + 1);
        Result tamperedCursor = call(client.get().uri("/books?sort=title&size=2&cursor=" + tampered));

        assertThat(tamperedCursor.status()).isEqualTo(400);
        assertViolation(tamperedCursor, "cursor", "is not valid for this request");

        // A token is bound to the sort it was issued for.
        Result otherSort = call(client.get().uri("/books?sort=-year&size=2&cursor=" + token));

        assertThat(otherSort.status()).isEqualTo(400);
        assertViolation(otherSort, "cursor", "is not valid for this request");
    }

    @Test
    void searchByOffset() {
        Result page = call(client.get().uri("/books?offset=2&size=2"));

        assertThat(page.status()).isEqualTo(200);
        assertThat(titles(page)).containsExactly("The Left Hand of Darkness");
        assertThat(page.json().get("hasNext").asBoolean()).isFalse();

        Result tooFar = call(client.get().uri("/books?offset=1001&size=2"));

        assertThat(tooFar.status()).isEqualTo(400);
        assertViolation(tooFar, "offset", "must be less than or equal to 1000");
    }

    @Test
    void getSendsETagAnd304() {
        Result read = call(client.get().uri(DUNE));

        assertThat(read.status()).isEqualTo(200);
        assertThat(read.headers().getETag()).isEqualTo("\"1\"");
        assertThat(read.headers().getCacheControl()).isEqualTo("no-cache");
        assertThat(read.headers().getVary()).containsExactly("Accept");

        Result notModified = call(client.get().uri(DUNE).header("If-None-Match", "\"1\""));

        assertThat(notModified.status()).isEqualTo(304);
        assertThat(notModified.headers().getETag()).isEqualTo("\"1\"");
        assertThat(notModified.body()).isEmpty();

        Result headNotModified = call(client.head().uri(DUNE).header("If-None-Match", "\"1\""));

        assertThat(headNotModified.status()).isEqualTo(304);
        assertThat(headNotModified.headers().getETag()).isEqualTo("\"1\"");
    }

    @Test
    void putWithIfMatchUpdatesAndStaleIs412() {
        Result created = create("isbn-it-put", "The Lathe of Heaven");
        String location = Objects.requireNonNull(created.headers().getLocation()).toString();
        String revision = "{\"isbn\":\"isbn-it-put\",\"title\":\"The Lathe of Heaven (revised)\","
                + "\"author\":\"Ursula K. Le Guin\"}";

        Result updated = call(client
                .put()
                .uri(location)
                .header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(revision));

        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.json().get("version").asLong()).isEqualTo(2);
        assertThat(updated.headers().containsHeader(HttpHeaders.ETAG)).isFalse();

        Result stale = call(client
                .put()
                .uri(location)
                .header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(revision));

        assertThat(stale.status()).isEqualTo(412);
        assertThat(stale.json().get("code").asString()).isEqualTo("PRECONDITION_FAILED");
        assertThat(stale.json().has("detail")).isFalse();

        deleteAt(created, "\"2\"");
    }

    @Test
    void getThenPutOfTheSameJsonWithIfMatchUpdates() {
        Result created = create("isbn-it-round-trip", "The Word for World Is Forest");
        String location = Objects.requireNonNull(created.headers().getLocation()).toString();
        Result read = call(client.get().uri(location));
        String etag = Objects.requireNonNull(read.headers().getETag());

        // The body read back carries "version":1, the same value as If-Match: that is no conflict.
        Result updated = call(client
                .put()
                .uri(location)
                .header("If-Match", etag)
                .contentType(MediaType.APPLICATION_JSON)
                .body(read.body()));

        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.json().get("version").asLong()).isEqualTo(2);

        // A body version that differs from If-Match is a conflict, before the operation runs.
        Result conflict = call(client
                .put()
                .uri(location)
                .header("If-Match", "\"2\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(read.body()));

        assertThat(conflict.status()).isEqualTo(400);
        assertThat(conflict.json().get("violations").get(0).get("field").asString()).isEqualTo("version");
        assertThat(conflict.json().get("violations").get(0).get("message").asString())
                .isEqualTo("conflicts with the value from the header");

        deleteAt(created, "\"2\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void concurrentUpdateWithoutIfMatchIs409() {
        // Another request writes between this request's read and its compare-and-set: the read sees version 0, so the
        // write expects version 0 and finds version 1.
        willAnswer(
                invocation -> ((Optional<StoredBook>) invocation.callRealMethod()).map(BooksApplicationIT::atVersion0))
                .given(store)
                .find(any());

        Result conflict = call(client
                .put()
                .uri(DUNE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"isbn\":\"978-0441172719\",\"title\":\"Lost update\",\"author\":\"Frank Herbert\"}"));

        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.json().get("code").asString()).isEqualTo("STALE_VERSION");
        assertThat(store.findAll())
                .filteredOn(book -> book.id().equals(InMemoryBookStore.DUNE))
                .extracting(StoredBook::title, StoredBook::version)
                .containsExactly(tuple("Dune", 1L));
    }

    @Test
    void deleteRequiresIfMatch() {
        Result created = create("isbn-it-delete", "Always Coming Home");
        String location = Objects.requireNonNull(created.headers().getLocation()).toString();

        Result required = call(client.delete().uri(location));

        assertThat(required.status()).isEqualTo(428);
        assertThat(required.json().get("code").asString()).isEqualTo("PRECONDITION_REQUIRED");
        assertViolation(required, "If-Match", "is required; send the ETag from a GET of this resource");

        Result deleted = call(client.delete().uri(location).header("If-Match", "\"1\""));

        assertThat(deleted.status()).isEqualTo(204);

        Result missing = call(client.delete().uri(location).header("If-Match", "*"));

        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.json().get("code").asString()).isEqualTo("BOOK_NOT_FOUND");
    }

    @Test
    void anInvalidBookIsValidationFailedWithViolations() {
        client
                .post()
                .uri("/books")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"isbn\":\"\",\"title\":\" \",\"author\":\"Nobody\",\"year\":-1}")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("VALIDATION_FAILED")
                .jsonPath("$.violations[*].field")
                .isEqualTo(List.of("isbn", "title", "year"))
                .jsonPath("$.detail")
                .doesNotExist();
    }

    @Test
    void anUnknownBookIsBookNotFoundWithoutDetailByDefault() {
        client
                .get()
                .uri("/books/00000000-0000-0000-0000-000000000099")
                .header("X-Correlation-Id", "it-correlation-2")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("BOOK_NOT_FOUND")
                .jsonPath("$.correlationId")
                .isEqualTo("it-correlation-2")
                .jsonPath("$.detail")
                .doesNotExist();
    }

    @Test
    void aDuplicateIsbnIsAConflict() {
        client
                .post()
                .uri("/books")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"isbn\":\"978-0441172719\",\"title\":\"Dune\",\"author\":\"Frank Herbert\"}")
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("DUPLICATE_ISBN");
    }

    @Test
    void anotherMethodIs405WithAllow() {
        var result = client
                .method(HttpMethod.PATCH)
                .uri(DUNE)
                .exchange()
                .expectStatus()
                .isEqualTo(405)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("METHOD_NOT_ALLOWED")
                .returnResult();

        assertThat(result.getResponseHeaders().getAllow())
                .containsExactly(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.PUT, HttpMethod.DELETE,
                        HttpMethod.OPTIONS);
    }

    @Test
    void theTotalIsInAHeaderAlsoOnHead() {
        client.head().uri("/books/stats").exchange().expectStatus().isOk().expectHeader().exists("X-Books-Total");
    }

    @Test
    void aBodyOverTheLimitIs413() {
        client
                .post()
                .uri("/books")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"title\":\"" + "x".repeat(20_000) + "\"}")
                .exchange()
                .expectStatus()
                .isEqualTo(413)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    void versionTwoServesTheNewShape() {
        Result created = create("isbn-it-v2", "Good Omens", "Terry Pratchett, Neil Gaiman");
        String location = Objects.requireNonNull(created.headers().getLocation()).toString();

        Result v2 = call(client.get().uri("/v2" + location));

        assertThat(v2.status()).isEqualTo(200);
        assertThat(v2.headers().getETag()).isEqualTo("\"1\"");
        assertThat(v2.headers().getVary()).containsExactly("Accept");
        assertThat(v2.json().get("authors").valueStream().map(JsonNode::asString))
                .containsExactly("Terry Pratchett", "Neil Gaiman");
        assertThat(v2.json().propertyNames()).containsExactly("id", "isbn", "title", "authors", "year", "version");

        Result v1 = call(client.get().uri(location));

        assertThat(v1.status()).isEqualTo(200);
        assertThat(v1.json().get("author").asString()).isEqualTo("Terry Pratchett, Neil Gaiman");
        assertThat(v1.json().propertyNames())
                .containsExactly("id", "isbn", "title", "author", "year", "addedVia", "version");

        Result notModified = call(client.get().uri("/v2" + location).header("If-None-Match", "\"1\""));

        assertThat(notModified.status()).isEqualTo(304);

        Result v3 = call(client.get().uri("/v3" + location));

        assertThat(v3.status()).isEqualTo(404);
        assertThat(v3.json().get("code").asString()).isEqualTo("ROUTE_NOT_FOUND");

        deleteAt(created, "\"1\"");

        Result gone = call(client.get().uri("/v2" + location));

        assertThat(gone.status()).isEqualTo(404);
        assertThat(gone.json().get("code").asString()).isEqualTo("BOOK_NOT_FOUND");
    }

    @Test
    void apiDocsAreOffInTest() {
        // Off by default in every environment, and application-test.yaml doesn't turn it on.
        Result docs = call(client.get().uri("/v3/api-docs"));

        assertThat(docs.status()).isEqualTo(404);
        assertThat(docs.json().get("code").asString()).isEqualTo("ROUTE_NOT_FOUND");
    }

    /** The OpenAPI document on, as application-local.yaml turns it on (BooksLocalEnvironmentIT loads that file). */
    @Nested
    @TestPropertySource(properties = "springdoc.api-docs.enabled=true")
    class WithApiDocs {

        @Autowired
        RestTestClient docsClient;

        @Test
        void apiDocsListEveryRoute() {
            Result docs = call(docsClient.get().uri("/v3/api-docs"));

            assertThat(docs.status()).isEqualTo(200);
            JsonNode json = docs.json();
            assertThat(json.get("openapi").asString()).isEqualTo("3.1.0");
            assertThat(json.get("paths").propertyNames())
                    .contains("/books", "/books/{id}", "/books/stats", "/v2/books/{id}");
            JsonNode v2 = json.get("paths").get("/v2/books/{id}").get("get");
            assertThat(v2.get("operationId").asString()).isEqualTo("get-book-v2");
            assertThat(v2
                    .get("responses")
                    .get("200")
                    .get("content")
                    .get("application/json")
                    .get("schema")
                    .get("$ref")
                    .asString()).isEqualTo("#/components/schemas/BookV2");
            assertThat(v2.get("responses").propertyNames()).contains("200", "304", "404");
            assertThat(json
                    .get("paths")
                    .get("/books/{id}")
                    .get("get")
                    .get("responses")
                    .get("200")
                    .get("content")
                    .get("application/json")
                    .get("schema")
                    .get("$ref")
                    .asString())
                    .isEqualTo("#/components/schemas/Book");
            JsonNode schemas = json.get("components").get("schemas");
            assertThat(schemas.propertyNames()).contains("Book", "BookV2");
            assertThat(schemas.get("BookV2").get("properties").get("authors").get("type").asString())
                    .isEqualTo("array");
            assertThat(schemas.get("Book").get("properties").get("author").get("type").asString())
                    .isEqualTo("string");
            // Actuator is not a REST binding and is not documented.
            assertThat(json.get("paths").propertyNames()).noneMatch(path -> path.startsWith("/actuator"));
            // DeleteBookRequest.version isn't @Nullable, so DELETE requires If-Match (428 without it); the PUT's is
            // @Nullable, so optional.
            JsonNode book = json.get("paths").get("/books/{id}");
            assertThat(ifMatch(book.get("delete")).get("required").asBoolean()).isTrue();
            assertThat(book.get("delete").get("responses").propertyNames()).contains("204", "412", "428");
            assertThat(ifMatch(book.get("put")).get("required").asBoolean()).isFalse();
            assertThat(book.get("put").get("responses").propertyNames()).doesNotContain("428");
        }

        private static JsonNode ifMatch(JsonNode operation) {
            for (JsonNode parameter : operation.get("parameters")) {
                if (parameter.get("name").asString().equals("If-Match")) {
                    return parameter;
                }
            }
            throw new AssertionError("no If-Match parameter in " + operation);
        }
    }

    @Test
    void corsIsOffInTest() {
        // application-test.yaml lists no origins, so no request gets CORS headers or a CORS rejection.
        Result read = call(client.get().uri(DUNE).header(HttpHeaders.ORIGIN, LOCAL_FRONT_END));

        assertThat(read.status()).isEqualTo(200);
        assertThat(read.headers().getAccessControlAllowOrigin()).isNull();

        Result other = call(client.get().uri(DUNE).header(HttpHeaders.ORIGIN, "https://evil.example"));

        assertThat(other.status()).isEqualTo(200);
        assertThat(other.headers().getAccessControlAllowOrigin()).isNull();
    }

    @Test
    void healthIsUp() {
        client
                .get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("UP");
    }

    /** Acceptance 2: one operation behind both protocols. A book added over REST is read back over gRPC. */
    @Test
    void sameOperationOverRestAndGrpc() throws InterruptedException {
        Result created = create("isbn-it-grpc", "The Telling");
        String location = Objects.requireNonNull(created.headers().getLocation()).toString();
        String id = location.substring(location.lastIndexOf('/') + 1);
        ManagedChannel channel = Grpc
                .newChannelBuilderForAddress("localhost", grpcPort, InsecureChannelCredentials.create())
                .build();
        try {
            Book book = BookServiceGrpc
                    .newBlockingStub(channel)
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .getBook(GetBookRequest.newBuilder().setId(id).build());

            assertThat(book.getId()).isEqualTo(id);
            assertThat(book.getTitle()).isEqualTo(created.json().get("title").asString()).isEqualTo("The Telling");
            assertThat(book.getVersion()).isEqualTo(created.json().get("version").asLong()).isEqualTo(1);
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }

        deleteAt(created, "\"1\"");
    }

    /** CORS on for the local front end, as application-local.yaml turns it on. */
    @Nested
    @TestPropertySource(properties = "synapse.api.rest.cors.allowed-origins=" + LOCAL_FRONT_END)
    class WithCors {

        @Autowired
        RestTestClient corsClient;

        @Test
        void theApiAnswersTheLocalFrontEnd() {
            Result read = call(corsClient.get().uri(DUNE).header(HttpHeaders.ORIGIN, LOCAL_FRONT_END));

            assertThat(read.status()).isEqualTo(200);
            assertThat(read.headers().getAccessControlAllowOrigin()).isEqualTo(LOCAL_FRONT_END);
            assertThat(read.headers().getAccessControlExposeHeaders()).contains("X-Correlation-Id", "ETag");

            Result other = call(corsClient.get().uri(DUNE).header(HttpHeaders.ORIGIN, "https://evil.example"));

            assertThat(other.status()).isEqualTo(403);
            assertThat(other.json().get("code").asString()).isEqualTo("CORS_REJECTED");
            assertThat(other.headers().getAccessControlAllowOrigin()).isNull();
        }

        @Test
        void managementPathsAreNotTouched() {
            // Actuator has its own CORS settings (management.endpoints.web.cors.*), none here: Synapse's policy
            // neither answers for it nor rejects for it, even for the origin it allows.
            Result allowed = call(corsClient.get().uri("/actuator/health").header(HttpHeaders.ORIGIN, LOCAL_FRONT_END));

            assertThat(allowed.status()).isEqualTo(200);
            assertThat(allowed.json().get("status").asString()).isEqualTo("UP");
            assertThat(allowed.headers().getAccessControlAllowOrigin()).isNull();
            assertThat(allowed.headers().getAccessControlExposeHeaders()).isEmpty();

            Result other = call(
                    corsClient.get().uri("/actuator/health").header(HttpHeaders.ORIGIN, "https://evil.example"));

            assertThat(other.status()).isEqualTo(200);
            assertThat(other.headers().getAccessControlAllowOrigin()).isNull();
        }
    }

    /**
     * The service under a context path, with CORS on for the local front end. An error dispatch, whose path is the
     * error page's, decides CORS on the request's original URI: the error response of an Actuator request gets no CORS
     * headers however the context path was spelled, and the API's error responses keep them.
     */
    @Nested
    @TestPropertySource(properties = {"server.servlet.context-path=/ctx",
            "synapse.api.rest.cors.allowed-origins=" + LOCAL_FRONT_END})
    @Import(FailingFilter.class)
    class UnderAContextPath {

        /** Tomcat's container loggers, which log every exception that escapes the filter chain. */
        private static final String TOMCAT_CONTAINER = "org.apache.catalina.core.ContainerBase";

        @LocalServerPort
        int port;

        @Autowired
        LoggingSystem logging;

        @Test
        void errorDispatchOfAnActuatorPathGetsNoCorsHeaders() throws IOException, InterruptedException {
            // The JDK client sends the path as written (RestTestClient would encode the % of /%63tx), and Tomcat
            // decodes /%63tx to the context path /ctx. FailingFilter fails these requests before Synapse's CORS filter
            // runs, so only the original URI on the error dispatch can tell an Actuator path from the API's.
            try (HttpClient http = HttpClient.newHttpClient()) {
                // Each deliberate failure is logged at ERROR with its stack trace twice: by Tomcat, and by Synapse's
                // error controller ("Unhandled error for GET ..."). Tomcat's copy is silenced while these requests run
                // (log levels are JVM-wide, so only here, and restored after). Synapse's four lines are expected and
                // kept: a 500 INTERNAL looks the same whatever failed, and they show it was FailingFilter.
                logging.setLogLevel(TOMCAT_CONTAINER, LogLevel.OFF);
                try {
                    for (String actuator : List.of("/%63tx/actuator/fail", "/ctx/actuator/fail")) {
                        HttpResponse<String> response = get(http, actuator);

                        assertThat(response.statusCode()).as(actuator).isEqualTo(500);
                        assertThat(response.headers().firstValue(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                                .as(actuator)
                                .isEmpty();
                    }

                    // The API's error response, under the same spellings, gets the policy on the error dispatch.
                    for (String api : List.of("/%63tx/books/fail", "/ctx/books/fail")) {
                        HttpResponse<String> response = get(http, api);

                        assertThat(response.statusCode()).as(api).isEqualTo(500);
                        assertThat(MAPPER.readTree(response.body()).get("code").asString())
                                .as(api)
                                .isEqualTo("INTERNAL");
                        assertThat(response.headers().firstValue(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                                .as(api)
                                .contains(LOCAL_FRONT_END);
                    }
                } finally {
                    logging.setLogLevel(TOMCAT_CONTAINER, null);
                }

                // And a normal request keeps it.
                HttpResponse<String> book = get(http, "/ctx" + DUNE);

                assertThat(book.statusCode()).isEqualTo(200);
                assertThat(book.headers().firstValue(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                        .contains(LOCAL_FRONT_END);
            }
        }

        private HttpResponse<String> get(HttpClient http, String rawPath) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest
                    .newBuilder(URI.create("http://localhost:" + port + rawPath))
                    .header(HttpHeaders.ORIGIN, LOCAL_FRONT_END)
                    .GET()
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    /**
     * A filter that fails {@code /actuator/fail} and {@code /books/fail} on the request dispatch, before Synapse's CORS
     * filter, so the error controller answers on the error dispatch. Imported by {@link UnderAContextPath} only (it is
     * not a {@code @Configuration}, which every test's context would pick up).
     */
    static class FailingFilter {

        @Bean
        FilterRegistrationBean<OncePerRequestFilter> failingFilter() {
            FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(
                    new OncePerRequestFilter() {
                        @Override
                        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                FilterChain chain) throws ServletException, IOException {
                            String path = request.getServletPath();
                            if (path.equals("/actuator/fail") || path.equals("/books/fail")) {
                                throw new IllegalStateException("failed on purpose");
                            }
                            chain.doFilter(request, response);
                        }
                    });
            registration.setDispatcherTypes(DispatcherType.REQUEST);
            // Synapse's CORS filter runs at HIGHEST_PRECEDENCE + 3.
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
            return registration;
        }
    }

    private Result create(String isbn, String title) {
        return create(isbn, title, "Ursula K. Le Guin");
    }

    private Result create(String isbn, String title, String author) {
        Result created = call(client
                .post()
                .uri("/books")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"isbn\":\"" + isbn + "\",\"title\":\"" + title + "\",\"author\":\"" + author + "\"}"));
        assertThat(created.status()).isEqualTo(201);
        return created;
    }

    private void deleteAt(Result created, String ifMatch) {
        String location = Objects.requireNonNull(created.headers().getLocation()).toString();
        assertThat(call(client.delete().uri(location).header("If-Match", ifMatch)).status()).isEqualTo(204);
    }

    private static List<String> titles(Result page) {
        return page.json().get("items").valueStream().map(item -> item.get("title").asString()).toList();
    }

    private static void assertViolation(Result problem, String field, String message) {
        JsonNode violations = problem.json().get("violations");
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).get("field").asString()).isEqualTo(field);
        assertThat(violations.get(0).get("message").asString()).isEqualTo(message);
    }

    /** The target of a {@code Link} header: the URI between the angle brackets. */
    private static String linkTarget(String link) {
        return link.substring(link.indexOf('<') + 1, link.indexOf('>'));
    }

    private static StoredBook atVersion0(StoredBook book) {
        return new StoredBook(book.id(), book.isbn(), book.title(), book.author(), book.year(), book.addedVia(), 0);
    }
}
