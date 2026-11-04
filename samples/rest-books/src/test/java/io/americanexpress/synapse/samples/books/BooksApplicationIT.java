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

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.samples.books.data.InMemoryBookStore;
import io.americanexpress.synapse.samples.books.data.StoredBook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;

/**
 * The whole service on a real server, as it runs in production: no profile is active, so problem responses carry no
 * {@code detail}.
 *
 * <p>
 * The tests share the catalog (one Spring context). The searches expect the three seeded books, so every test that
 * adds a book deletes it, and no test changes a seeded book.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Tag("smoke")
class BooksApplicationIT {

    private static final String DUNE = "/books/00000000-0000-0000-0000-000000000001";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    RestTestClient client;

    /** The real store, spied on so that one test can simulate a concurrent write. */
    @MockitoSpyBean
    InMemoryBookStore store;

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
    void anUnknownBookIsBookNotFoundWithoutDetailInProduction() {
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

    private Result create(String isbn, String title) {
        Result created = call(client
                .post()
                .uri("/books")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"isbn\":\"" + isbn + "\",\"title\":\"" + title + "\",\"author\":\"Ursula K. Le Guin\"}"));
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
