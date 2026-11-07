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
package io.americanexpress.synapse.api.rest.internal.conditional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.internal.error.BookErrors;
import io.americanexpress.synapse.core.conditional.VersionCondition;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conditional requests on a real server, on a versioned in-memory store: {@code ETag}s on reads and creates, 304 for a
 * matching {@code If-None-Match}, and the write preconditions (412, 428 and {@code STALE_VERSION}).
 *
 * <p>
 * The {@code DELETE} route requires {@code If-Match}, so every successful {@code DELETE} these tests send carries the
 * current version.
 */
@SpringBootTest(classes = RestConditionalIT.TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.logging.export.otlp.enabled=false", "management.tracing.export.otlp.enabled=false",
        "management.otlp.metrics.export.enabled=false"})
@AutoConfigureRestTestClient
@ActiveProfiles("test")
class RestConditionalIT {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private RestTestClient client;

    @Autowired
    private BookStore store;

    @Autowired
    private GetBookOperation getBook;

    @Autowired
    private CreateBookOperation createBook;

    @Autowired
    private UpdateBookOperation updateBook;

    @Autowired
    private DeleteBookOperation deleteBook;

    @Autowired
    private UpdateNoteOperation updateNote;

    @Autowired
    private PutDraftOperation putDraft;

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

    @BeforeEach
    void reset() {
        store.reset();
        getBook.calls().set(0);
        createBook.calls().set(0);
        updateBook.calls().set(0);
        updateBook.inputs().clear();
        deleteBook.inputs().clear();
        updateNote.calls().set(0);
        putDraft.drafts().clear();
        putDraft.inputs().clear();
    }

    @Test
    @Tag("smoke")
    void ifNoneMatchYields304WithETag() {
        Result read = call(client.get().uri("/books/1"));

        assertThat(read.status()).isEqualTo(200);
        assertThat(read.headers().getETag()).isEqualTo("\"1\"");
        assertThat(read.headers().getCacheControl()).isEqualTo("no-cache");
        assertThat(read.headers().getVary()).containsExactly("Accept");
        assertThat(read.json().get("version").asLong()).isEqualTo(1);

        Result notModified = call(client.get().uri("/books/1").header("If-None-Match", "\"1\""));

        assertThat(notModified.status()).isEqualTo(304);
        assertThat(notModified.headers().getETag()).isEqualTo("\"1\"");
        assertThat(notModified.headers().getCacheControl()).isEqualTo("no-cache");
        assertThat(notModified.headers().getVary()).containsExactly("Accept");
        assertThat(notModified.headers().getFirst("X-Correlation-Id")).isNotBlank();
        assertThat(notModified.headers().getContentType()).isNull();
        assertThat(notModified.body()).isEmpty();
        // The operation runs: the 304 is decided from its output.
        assertThat(getBook.calls()).hasValue(2);
    }

    @Test
    void headWithMatchingIfNoneMatchIs304WithValidators() {
        long getLength = call(client.get().uri("/books/1")).headers().getContentLength();
        assertThat(getLength).isPositive();

        Result notModified = call(client.head().uri("/books/1").header("If-None-Match", "\"1\""));

        assertThat(notModified.status()).isEqualTo(304);
        assertThat(notModified.headers().getETag()).isEqualTo("\"1\"");
        assertThat(notModified.headers().getCacheControl()).isEqualTo("no-cache");
        assertThat(notModified.headers().getVary()).containsExactly("Accept");
        assertThat(notModified.headers().containsHeader(HttpHeaders.CONTENT_LENGTH)).isFalse();
        assertThat(notModified.headers().getContentType()).isNull();
        assertThat(notModified.body()).isEmpty();

        Result modified = call(client.head().uri("/books/1").header("If-None-Match", "\"9\""));

        assertThat(modified.status()).isEqualTo(200);
        assertThat(modified.headers().getETag()).isEqualTo("\"1\"");
        assertThat(modified.headers().getCacheControl()).isEqualTo("no-cache");
        assertThat(modified.headers().getVary()).containsExactly("Accept");
        assertThat(modified.headers().getContentLength()).isEqualTo(getLength);
        assertThat(modified.body()).isEmpty();
    }

    @Test
    void malformedIfNoneMatchIsIgnored() {
        // Spring's own parser would read "1"x as "1" and answer 304.
        for (String malformed : List.of("\"1\"x", "1", "*, \"1\"")) {
            Result read = call(client.get().uri("/books/1").header("If-None-Match", malformed));

            assertThat(read.status()).as(malformed).isEqualTo(200);
            assertThat(read.headers().getETag()).as(malformed).isEqualTo("\"1\"");
            assertThat(read.json().get("title").asString()).as(malformed).isEqualTo("Dune");
        }
    }

    @Test
    void notFoundBeatsIfNoneMatch() {
        Result missing = call(client.get().uri("/books/missing").header("If-None-Match", "*"));

        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.json().get("code").asString()).isEqualTo("BOOK_NOT_FOUND");
        assertThat(missing.headers().getETag()).isNull();
    }

    @Test
    void postReturnsETag() {
        Result created = call(client
                .post()
                .uri("/books")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"title\":\"Solaris\"}"));

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.headers().getLocation()).hasToString("/books/2");
        assertThat(created.headers().getETag()).isEqualTo("\"1\"");
        assertThat(created.headers().getCacheControl()).isNull();
        assertThat(created.headers().getVary()).isEmpty();
        assertThat(created.json().get("title").asString()).isEqualTo("Solaris");
        assertThat(createBook.calls()).hasValue(1);

        // A POST never answers 304. This route doesn't bind If-None-Match, so the precondition can't be evaluated:
        // 412, and nothing is created.
        Result again = call(client
                .post()
                .uri("/books")
                .header("If-None-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"title\":\"Ubik\"}"));

        assertThat(again.status()).isEqualTo(412);
        assertThat(again.json().get("code").asString()).isEqualTo("PRECONDITION_FAILED");
        assertThat(again.headers().getLocation()).isNull();
        assertThat(again.headers().getETag()).isNull();
        assertThat(createBook.calls()).hasValue(1);
    }

    @Test
    void putAndDeleteNeverSendETag() {
        Result updated = call(client
                .put()
                .uri("/books/1")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"title\":\"Dune Messiah\"}"));

        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.json().get("version").asLong()).isEqualTo(2);
        assertThat(updated.headers().containsHeader(HttpHeaders.ETAG)).isFalse();
        assertThat(updated.headers().getCacheControl()).isNull();
        assertThat(updateBook.calls()).hasValue(1);

        Result note = call(client
                .put()
                .uri("/notes/1")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"text\":\"read it\"}"));

        assertThat(note.status()).isEqualTo(200);
        assertThat(note.headers().containsHeader(HttpHeaders.ETAG)).isFalse();
        assertThat(updateNote.calls()).hasValue(1);

        Result deleted = call(client.delete().uri("/books/1").header("If-Match", "\"2\""));

        assertThat(deleted.status()).isEqualTo(204);
        assertThat(deleted.headers().containsHeader(HttpHeaders.ETAG)).isFalse();
        assertThat(deleted.body()).isEmpty();
        assertThat(deleteBook.inputs()).hasSize(1);
        assertThat(call(client.get().uri("/books/1")).status()).isEqualTo(404);
    }

    @Test
    @Tag("smoke")
    void versionComesFromIfMatch() {
        Result updated = call(client
                .put()
                .uri("/books/1")
                .header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"title\":\"Dune Messiah\"}"));

        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.json().get("version").asLong()).isEqualTo(2);
        assertThat(updated.json().get("title").asString()).isEqualTo("Dune Messiah");
        assertThat(updateBook.inputs())
                .containsExactly(new UpdateBookRequest("1", VersionCondition.of(1L), "Dune Messiah"));
    }

    @Test
    void staleIfMatchIs412AndNothingChanges() {
        Result stale = call(client
                .put()
                .uri("/books/1")
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"title\":\"Dune Messiah\"}"));

        assertThat(stale.status()).isEqualTo(412);
        assertThat(stale.json().get("code").asString()).isEqualTo("PRECONDITION_FAILED");
        assertThat(stale.headers().containsHeader(HttpHeaders.ETAG)).isFalse();
        assertThat(updateBook.calls()).hasValue(1);

        Result read = call(client.get().uri("/books/1"));
        assertThat(read.json().get("version").asLong()).isEqualTo(1);
        assertThat(read.json().get("title").asString()).isEqualTo("Dune");
    }

    @Test
    void raceLostAfterTheReadIs412WithIfMatchAnd409Without() {
        // The operation reads version 1, then a concurrent write wins before its compare-and-set.
        store.competeOnNextWrite("Concurrent");
        Result withIfMatch = call(client
                .put()
                .uri("/books/1")
                .header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"title\":\"Lost\"}"));

        assertThat(withIfMatch.status()).isEqualTo(412);
        assertThat(withIfMatch.json().get("code").asString()).isEqualTo("PRECONDITION_FAILED");
        assertThat(store.find("1")).contains(new Book("1", "Concurrent", 2));

        // Without If-Match the write was unconditional, so losing the race is a conflict, not a failed precondition.
        store.competeOnNextWrite("Concurrent again");
        Result withoutIfMatch = call(client
                .put()
                .uri("/books/1")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"title\":\"Lost\"}"));

        assertThat(withoutIfMatch.status()).isEqualTo(409);
        assertThat(withoutIfMatch.json().get("code").asString()).isEqualTo("STALE_VERSION");
        assertThat(store.find("1")).contains(new Book("1", "Concurrent again", 3));
        assertThat(updateBook.calls()).hasValue(2);
    }

    @Test
    void weakIfMatchIs412WithoutCallingTheOperation() {
        Result weak = call(client
                .put()
                .uri("/books/1")
                .header("If-Match", "W/\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"title\":\"Dune Messiah\"}"));

        assertThat(weak.status()).isEqualTo(412);
        assertThat(weak.json().get("code").asString()).isEqualTo("PRECONDITION_FAILED");
        assertThat(updateBook.calls()).hasValue(0);
        assertThat(store.find("1")).contains(new Book("1", "Dune", 1));
    }

    @Test
    void ifMatchListOrUnquotedIs400() {
        for (String bad : List.of("\"1\", \"2\"", "1")) {
            Result invalid = call(client
                    .put()
                    .uri("/books/1")
                    .header("If-Match", bad)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"title\":\"Dune Messiah\"}"));

            assertThat(invalid.status()).as(bad).isEqualTo(400);
            assertThat(invalid.json().get("code").asString()).as(bad).isEqualTo("VALIDATION_FAILED");
            assertThat(invalid.json().get("violations")).as(bad).hasSize(1);
            assertThat(invalid.json().get("violations").get(0).get("field").asString()).as(bad).isEqualTo("version");
        }
        assertThat(updateBook.calls()).hasValue(0);
    }

    @Test
    void wildcardOnMissingResourceIs404() {
        Result missing = call(client
                .put()
                .uri("/books/missing")
                .header("If-Match", "*")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"title\":\"Dune Messiah\"}"));

        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.json().get("code").asString()).isEqualTo("BOOK_NOT_FOUND");
        assertThat(updateBook.inputs())
                .containsExactly(new UpdateBookRequest("missing", VersionCondition.any(), "Dune Messiah"));
    }

    @Test
    @Tag("smoke")
    void aRequiredIfMatchIs428WhenAbsent() {
        // DeleteBookRequest.version isn't @Nullable, so the route requires If-Match.
        Result required = call(client.delete().uri("/books/1"));

        assertThat(required.status()).isEqualTo(428);
        assertThat(required.json().get("code").asString()).isEqualTo("PRECONDITION_REQUIRED");
        assertThat(required.json().get("violations")).hasSize(1);
        assertThat(required.json().get("violations").get(0).get("field").asString()).isEqualTo("If-Match");
        assertThat(required.json().get("violations").get(0).get("message").asString())
                .isEqualTo("is required; send the ETag from a GET of this resource");
        // Detail is hidden by default; the violation still says how to retry.
        assertThat(required.json().has("detail")).isFalse();
        assertThat(deleteBook.inputs()).isEmpty();

        Result stale = call(client.delete().uri("/books/1").header("If-Match", "\"9\""));

        assertThat(stale.status()).isEqualTo(412);
        assertThat(stale.json().get("code").asString()).isEqualTo("PRECONDITION_FAILED");
        assertThat(store.find("1")).isPresent();

        Result deleted = call(client.delete().uri("/books/1").header("If-Match", "\"1\""));

        assertThat(deleted.status()).isEqualTo(204);
        assertThat(deleteBook.inputs())
                .containsExactly(new DeleteBookRequest("1", VersionCondition.of(9L)),
                        new DeleteBookRequest("1", VersionCondition.of(1L)));
        assertThat(store.find("1")).isEmpty();

        // * counts as present: the operation gets AnyVersion, and a missing book is its 404.
        Result any = call(client.delete().uri("/books/1").header("If-Match", "*"));

        assertThat(any.status()).isEqualTo(404);
        assertThat(any.json().get("code").asString()).isEqualTo("BOOK_NOT_FOUND");
        assertThat(deleteBook.inputs()).last().isEqualTo(new DeleteBookRequest("1", VersionCondition.any()));
    }

    @Test
    void aNullableIfMatchMayBeAbsent() {
        // UpdateBookRequest.version is @Nullable: no If-Match is an unconditional write.
        Result updated = call(client
                .put()
                .uri("/books/1")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"title\":\"Dune Messiah\"}"));

        assertThat(updated.status()).as(updated.body()).isEqualTo(200);
        assertThat(updateBook.inputs()).containsExactly(new UpdateBookRequest("1", null, "Dune Messiah"));
    }

    @Test
    void deleteRaceLostAfterTheReadIs412() {
        // The operation reads version 1, then a concurrent write wins before its compare-and-set removal.
        store.competeOnNextWrite("Concurrent");
        Result lost = call(client.delete().uri("/books/1").header("If-Match", "\"1\""));

        assertThat(lost.status()).isEqualTo(412);
        assertThat(lost.json().get("code").asString()).isEqualTo("PRECONDITION_FAILED");
        assertThat(deleteBook.inputs()).containsExactly(new DeleteBookRequest("1", VersionCondition.of(1L)));
        assertThat(store.find("1")).contains(new Book("1", "Concurrent", 2));
    }

    @Test
    void unboundPreconditionIs412() {
        for (String header : List.of("If-Match", "If-None-Match")) {
            Result unbound = call(client
                    .put()
                    .uri("/notes/1")
                    .header(header, "\"1\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"text\":\"read it\"}"));

            assertThat(unbound.status()).as(header).isEqualTo(412);
            assertThat(unbound.json().get("code").asString()).as(header).isEqualTo("PRECONDITION_FAILED");
        }
        assertThat(updateNote.calls()).hasValue(0);
    }

    @Test
    void createOnlyPutUsesAnyVersion() {
        Result created = sendDraft("*", "first");

        assertThat(created.status()).isEqualTo(200);
        assertThat(created.json().get("text").asString()).isEqualTo("first");

        // The draft exists now, so a second create-only PUT fails, and nothing changes.
        Result again = sendDraft("*", "second");

        assertThat(again.status()).isEqualTo(412);
        assertThat(again.json().get("code").asString()).isEqualTo("PRECONDITION_FAILED");
        assertThat(putDraft.drafts()).containsEntry("1", new Draft("1", "first"));

        // Without If-None-Match the operation sees no condition and overwrites.
        Result overwritten = sendDraft(null, "third");

        assertThat(overwritten.status()).isEqualTo(200);
        assertThat(putDraft.drafts()).containsEntry("1", new Draft("1", "third"));
        assertThat(putDraft.inputs())
                .extracting(PutDraftRequest::unless)
                .containsExactly(VersionCondition.any(), VersionCondition.any(), null);
    }

    private Result sendDraft(@Nullable String ifNoneMatch, String text) {
        RestTestClient.RequestBodySpec request = client.put().uri("/drafts/1");
        if (ifNoneMatch != null) {
            request.header("If-None-Match", ifNoneMatch);
        }
        return call(request.contentType(MediaType.APPLICATION_JSON).body("{\"text\":\"" + text + "\"}"));
    }

    @Test
    void ifUnmodifiedSinceIsIgnored() {
        Result updated = call(client
                .put()
                .uri("/books/1")
                .header("If-Unmodified-Since", "Sat, 01 Jan 2000 00:00:00 GMT")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"title\":\"Dune Messiah\"}"));

        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.json().get("version").asLong()).isEqualTo(2);
        assertThat(store.find("1")).contains(new Book("1", "Dune Messiah", 2));
    }

    @Nested
    @TestPropertySource(properties = "synapse.api.rest.errors.status.[STALE_VERSION]=422")
    class StaleVersionStatus {

        @Autowired
        private RestTestClient nestedClient;

        @Autowired
        private BookStore nestedStore;

        @BeforeEach
        void resetNested() {
            nestedStore.reset();
        }

        @Test
        void staleVersionStatusCanBeOverridden() {
            nestedStore.competeOnNextWrite("Concurrent");
            Result withoutIfMatch = call(nestedClient
                    .put()
                    .uri("/books/1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"title\":\"Lost\"}"));

            assertThat(withoutIfMatch.status()).isEqualTo(422);
            assertThat(withoutIfMatch.json().get("code").asString()).isEqualTo("STALE_VERSION");
            assertThat(nestedStore.find("1")).contains(new Book("1", "Concurrent", 2));

            // With If-Match the code is PRECONDITION_FAILED, which the STALE_VERSION override doesn't touch.
            nestedStore.competeOnNextWrite("Concurrent again");
            Result withIfMatch = call(nestedClient
                    .put()
                    .uri("/books/1")
                    .header("If-Match", "\"2\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"title\":\"Lost\"}"));

            assertThat(withIfMatch.status()).isEqualTo(412);
            assertThat(withIfMatch.json().get("code").asString()).isEqualTo("PRECONDITION_FAILED");
            assertThat(nestedStore.find("1")).contains(new Book("1", "Concurrent again", 3));
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {

        @Bean
        BookStore bookStore() {
            return new BookStore();
        }

        @Bean
        GetBookOperation getBookOperation(BookStore store) {
            return new GetBookOperation(store);
        }

        @Bean
        CreateBookOperation createBookOperation(BookStore store) {
            return new CreateBookOperation(store);
        }

        @Bean
        UpdateBookOperation updateBookOperation(BookStore store) {
            return new UpdateBookOperation(store);
        }

        @Bean
        DeleteBookOperation deleteBookOperation(BookStore store) {
            return new DeleteBookOperation(store);
        }

        @Bean
        UpdateNoteOperation updateNoteOperation() {
            return new UpdateNoteOperation();
        }

        @Bean
        PutDraftOperation putDraftOperation() {
            return new PutDraftOperation();
        }

        @Bean
        RestBindings conditional(GetBookOperation getBook, CreateBookOperation createBook,
                UpdateBookOperation updateBook, DeleteBookOperation deleteBook, UpdateNoteOperation updateNote,
                PutDraftOperation putDraft) {
            return RestBindings
                    .builder()
                    .get("/books/{id}", getBook)
                    .notFound(BookErrors.BOOK_NOT_FOUND)
                    .etag(Book::version)
                    .post("/books", createBook)
                    .created(book -> "/books/" + book.id())
                    .etag(Book::version)
                    .put("/books/{id}", updateBook)
                    .header("If-Match", UpdateBookRequest::version)
                    .notFound(BookErrors.BOOK_NOT_FOUND)
                    .delete("/books/{id}", deleteBook)
                    .header("If-Match", DeleteBookRequest::version)
                    .put("/notes/{id}", updateNote)
                    .put("/drafts/{id}", putDraft)
                    .header("If-None-Match", PutDraftRequest::unless)
                    .build();
        }
    }

    record Book(String id, String title, long version) {
    }

    record BookId(String id) {
    }

    record CreateBookRequest(String title) {
    }

    record UpdateBookRequest(String id, @Nullable VersionCondition<Long> version, String title) {
    }

    /** Deletes a book; the version is bound from {@code If-Match}, which the route requires. */
    /** A delete that requires a version: its condition isn't {@code @Nullable}. */
    record DeleteBookRequest(String id, VersionCondition<Long> version) {
    }

    record Note(String id, String text) {
    }

    record UpdateNoteRequest(String id, String text) {
    }

    record Draft(String id, String text) {
    }

    /** Puts a draft; {@code If-None-Match: *} makes it create-only. */
    record PutDraftRequest(String id, @Nullable VersionCondition<Long> unless, String text) {
    }

    /**
     * A versioned in-memory store with compare-and-set, holding book 1 ("Dune", version 1) after a reset. A test can
     * make a concurrent write win the next compare-and-set: the store applies it after the operation's read, just
     * before the operation's own write.
     */
    static class BookStore {

        private final Map<String, Book> books = new ConcurrentHashMap<>();

        private final AtomicLong ids = new AtomicLong();

        private final AtomicReference<@Nullable String> competingTitle = new AtomicReference<>();

        public void reset() {
            competingTitle.set(null);
            books.clear();
            ids.set(1);
            books.put("1", new Book("1", "Dune", 1));
        }

        public Optional<Book> find(String id) {
            return Optional.ofNullable(books.get(id));
        }

        public Book insert(String title) {
            Book book = new Book(String.valueOf(ids.incrementAndGet()), title, 1);
            books.put(book.id(), book);
            return book;
        }

        /** Makes a write with the title, and the next version, win the race against the next compare-and-set. */
        public void competeOnNextWrite(String title) {
            competingTitle.set(title);
        }

        /** Replaces the book when it still has the expected version; returns whether it did. */
        public boolean compareAndSet(String id, long expectedVersion, Book replacement) {
            applyCompetingWrite(id);
            Book[] replaced = new Book[1];
            books.computeIfPresent(id, (key, current) -> {
                if (current.version() != expectedVersion) {
                    return current;
                }
                replaced[0] = replacement;
                return replacement;
            });
            return replaced[0] != null;
        }

        /** Removes the book when it still has the expected version; returns whether it did. */
        public boolean removeIfVersion(String id, long expectedVersion) {
            applyCompetingWrite(id);
            boolean[] removed = new boolean[1];
            books.computeIfPresent(id, (key, current) -> {
                if (current.version() != expectedVersion) {
                    return current;
                }
                removed[0] = true;
                return null;
            });
            return removed[0];
        }

        private void applyCompetingWrite(String id) {
            String title = competingTitle.getAndSet(null);
            if (title != null) {
                books.computeIfPresent(id, (key, current) -> new Book(id, title, current.version() + 1));
            }
        }
    }

    static class GetBookOperation implements QueryOperation<BookId, Optional<Book>> {

        private final BookStore store;

        private final AtomicInteger calls = new AtomicInteger();

        GetBookOperation(BookStore store) {
            this.store = store;
        }

        /** Public, so the call reaches the target through the pipeline proxy. */
        public AtomicInteger calls() {
            return calls;
        }

        @Override
        public Optional<Book> execute(BookId input) {
            calls.incrementAndGet();
            return store.find(input.id());
        }
    }

    static class CreateBookOperation implements CommandOperation<CreateBookRequest, Book> {

        private final BookStore store;

        private final AtomicInteger calls = new AtomicInteger();

        CreateBookOperation(BookStore store) {
            this.store = store;
        }

        public AtomicInteger calls() {
            return calls;
        }

        @Override
        public Book execute(CreateBookRequest input) {
            calls.incrementAndGet();
            return store.insert(input.title());
        }
    }

    /**
     * Updates a book: {@code STALE_VERSION} when the request's version differs from the stored one, then a
     * compare-and-set with the version it read.
     */
    static class UpdateBookOperation implements CommandOperation<UpdateBookRequest, Optional<Book>> {

        private final BookStore store;

        private final AtomicInteger calls = new AtomicInteger();

        private final List<UpdateBookRequest> inputs = new CopyOnWriteArrayList<>();

        UpdateBookOperation(BookStore store) {
            this.store = store;
        }

        public AtomicInteger calls() {
            return calls;
        }

        public List<UpdateBookRequest> inputs() {
            return inputs;
        }

        @Override
        public Optional<Book> execute(UpdateBookRequest input) {
            calls.incrementAndGet();
            inputs.add(input);
            Optional<Book> found = store.find(input.id());
            if (found.isEmpty()) {
                return found;
            }
            Book current = found.get();
            VersionCondition<Long> expected = input.version();
            if (expected != null && !expected.matches(current.version())) {
                throw new SynapseException(SynapseErrors.STALE_VERSION);
            }
            Book replacement = new Book(current.id(), input.title(), current.version() + 1);
            if (!store.compareAndSet(current.id(), current.version(), replacement)) {
                throw new SynapseException(SynapseErrors.STALE_VERSION);
            }
            return Optional.of(replacement);
        }
    }

    /**
     * Deletes a book as {@link UpdateBookOperation} updates one: {@code STALE_VERSION} when the request's version
     * differs from the stored one, then a compare-and-set removal with the version it read.
     */
    static class DeleteBookOperation implements CommandOperation<DeleteBookRequest, Void> {

        private final BookStore store;

        private final List<DeleteBookRequest> inputs = new CopyOnWriteArrayList<>();

        DeleteBookOperation(BookStore store) {
            this.store = store;
        }

        public List<DeleteBookRequest> inputs() {
            return inputs;
        }

        @Override
        @SuppressWarnings("NullAway") // A void operation returns null; ServiceOperation has no @Nullable bound on O.
        public Void execute(DeleteBookRequest input) {
            inputs.add(input);
            Book current = store
                    .find(input.id())
                    .orElseThrow(() -> new SynapseException(BookErrors.BOOK_NOT_FOUND, input.id()));
            // Never null: the route requires If-Match, so a request without it is 428 before this runs.
            if (!input.version().matches(current.version())) {
                throw new SynapseException(SynapseErrors.STALE_VERSION);
            }
            if (!store.removeIfVersion(current.id(), current.version())) {
                throw new SynapseException(SynapseErrors.STALE_VERSION);
            }
            return null;
        }
    }

    static class UpdateNoteOperation implements CommandOperation<UpdateNoteRequest, Note> {

        private final AtomicInteger calls = new AtomicInteger();

        public AtomicInteger calls() {
            return calls;
        }

        @Override
        public Note execute(UpdateNoteRequest input) {
            calls.incrementAndGet();
            return new Note(input.id(), input.text());
        }
    }

    /**
     * Creates or replaces a draft. Given {@code AnyVersion} ({@code If-None-Match: *}) it only creates: a draft that
     * exists already is {@code PRECONDITION_FAILED}. Without a condition it overwrites.
     */
    static class PutDraftOperation implements CommandOperation<PutDraftRequest, Draft> {

        private final Map<String, Draft> drafts = new ConcurrentHashMap<>();

        private final List<PutDraftRequest> inputs = new CopyOnWriteArrayList<>();

        public Map<String, Draft> drafts() {
            return drafts;
        }

        public List<PutDraftRequest> inputs() {
            return inputs;
        }

        @Override
        public Draft execute(PutDraftRequest input) {
            inputs.add(input);
            if (input.unless() instanceof VersionCondition.AnyVersion<Long> && drafts.containsKey(input.id())) {
                throw new SynapseException(SynapseErrors.PRECONDITION_FAILED);
            }
            Draft draft = new Draft(input.id(), input.text());
            drafts.put(draft.id(), draft);
            return draft;
        }
    }
}
