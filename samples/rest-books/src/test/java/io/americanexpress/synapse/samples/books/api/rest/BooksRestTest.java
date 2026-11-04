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
package io.americanexpress.synapse.samples.books.api.rest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;

import io.americanexpress.synapse.samples.books.model.Book;
import io.americanexpress.synapse.samples.books.model.CatalogStats;
import io.americanexpress.synapse.samples.books.model.CreateBookRequest;
import io.americanexpress.synapse.samples.books.model.DeleteBookRequest;
import io.americanexpress.synapse.samples.books.model.GetBookRequest;
import io.americanexpress.synapse.samples.books.model.SearchBooksRequest;
import io.americanexpress.synapse.samples.books.model.UpdateBookRequest;
import io.americanexpress.synapse.samples.books.operation.CreateBookOperation;
import io.americanexpress.synapse.samples.books.operation.DeleteBookOperation;
import io.americanexpress.synapse.samples.books.operation.GetBookOperation;
import io.americanexpress.synapse.samples.books.operation.GetCatalogStatsOperation;
import io.americanexpress.synapse.samples.books.operation.SearchBooksOperation;
import io.americanexpress.synapse.samples.books.operation.UpdateBookOperation;
import io.americanexpress.synapse.test.rest.SynapseRestTest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * The REST slice: the bindings, the edge filters and the problem responses on MockMvc, with every operation mocked.
 * It checks how requests bind to the operations' inputs and how outputs map to responses; the operations themselves
 * are tested in {@code BookOperationsTest}.
 */
@SynapseRestTest
class BooksRestTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final Book DUNE = new Book(ID, "978-0441172719", "Dune", "Frank Herbert", 1965, null);

    @Autowired
    RestTestClient client;

    @MockitoBean
    GetBookOperation getBook;

    @MockitoBean
    SearchBooksOperation searchBooks;

    @MockitoBean
    GetCatalogStatsOperation getCatalogStats;

    @MockitoBean
    CreateBookOperation createBook;

    @MockitoBean
    UpdateBookOperation updateBook;

    @MockitoBean
    DeleteBookOperation deleteBook;

    @Test
    void thePathVariableFillsTheId() {
        given(getBook.execute(new GetBookRequest(ID))).willReturn(Optional.of(DUNE));

        client
                .get()
                .uri("/books/{id}", ID)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.title")
                .isEqualTo("Dune")
                .jsonPath("$.year")
                .isEqualTo(1965);
    }

    @Test
    void anEmptyResultIsBookNotFound() {
        given(getBook.execute(any())).willReturn(Optional.empty());

        client
                .get()
                .uri("/books/{id}", ID)
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("BOOK_NOT_FOUND")
                // The test profile shows detail; .notFound fills the template with the path value.
                .jsonPath("$.detail")
                .isEqualTo("Book " + ID + " was not found");
    }

    @Test
    void anIdThatIsNotAUuidIsAViolation() {
        client
                .get()
                .uri("/books/not-a-uuid")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("VALIDATION_FAILED")
                .jsonPath("$.violations[0].field")
                .isEqualTo("id")
                .jsonPath("$.violations[0].message")
                .isEqualTo("is not a valid value");
        then(getBook).should(never()).execute(any());
    }

    @Test
    void theQueryStringFillsTheSearch() {
        given(searchBooks.execute(new SearchBooksRequest("herbert"))).willReturn(List.of(DUNE));

        client
                .get()
                .uri("/books?author=herbert")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].title")
                .isEqualTo("Dune");
    }

    @Test
    void theTotalIsAResponseHeaderAndStaysInTheBody() {
        given(getCatalogStats.execute(any())).willReturn(new CatalogStats(3, 2));

        client
                .get()
                .uri("/books/stats")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("X-Books-Total", "3")
                .expectBody()
                .json("{\"books\":3,\"authors\":2}");
    }

    @Test
    void createIs201WithLocationAndTakesTheChannelFromTheContext() {
        Book created = new Book(ID, "isbn-1", "Title", "Author", null, "mobile");
        given(createBook.execute(new CreateBookRequest("isbn-1", "Title", "Author", null, "mobile")))
                .willReturn(created);

        client
                .post()
                .uri("/books")
                .header("X-Channel", "mobile")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"isbn\":\"isbn-1\",\"title\":\"Title\",\"author\":\"Author\"}")
                .exchange()
                .expectStatus()
                .isCreated()
                .expectHeader()
                .valueEquals("Location", "/books/" + ID)
                .expectBody()
                .jsonPath("$.addedVia")
                .isEqualTo("mobile");
    }

    @Test
    void aChannelInTheBodyThatDiffersFromTheContextIsRejected() {
        client
                .post()
                .uri("/books")
                .header("X-Channel", "mobile")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"isbn\":\"isbn-1\",\"title\":\"Title\",\"author\":\"Author\",\"channel\":\"web\"}")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.violations[0].field")
                .isEqualTo("channel")
                .jsonPath("$.violations[0].message")
                .isEqualTo("conflicts with the value from the request context");
        then(createBook).should(never()).execute(any());
    }

    @Test
    void aMissingComponentIsAViolationBeforeTheOperationRuns() {
        client
                .post()
                .uri("/books")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"title\":\"Title\",\"author\":\"Author\"}")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.violations[0].field")
                .isEqualTo("isbn")
                .jsonPath("$.violations[0].message")
                .isEqualTo("must not be null");
        then(createBook).should(never()).execute(any());
    }

    @Test
    void theIdComesFromThePathAndTheRestFromTheBody() {
        UpdateBookRequest request = new UpdateBookRequest(ID, "978-0441172719", "Dune", "Frank Herbert", 1965);
        given(updateBook.execute(request)).willReturn(DUNE);

        client
                .put()
                .uri("/books/{id}", ID)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"isbn\":\"978-0441172719\",\"title\":\"Dune\",\"author\":\"Frank Herbert\",\"year\":1965}")
                .exchange()
                .expectStatus()
                .isOk();
        then(updateBook).should().execute(request);
    }

    @Test
    void aBodyIdThatDiffersFromThePathIsAConflict() {
        client
                .put()
                .uri("/books/{id}", ID)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"id\":\"" + UUID.randomUUID() + "\",\"isbn\":\"i\",\"title\":\"t\",\"author\":\"a\"}")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.violations[0].field")
                .isEqualTo("id")
                .jsonPath("$.violations[0].message")
                .isEqualTo("conflicts with the value from the path");
        then(updateBook).should(never()).execute(any());
    }

    @Test
    void deleteIs204() {
        client.delete().uri("/books/{id}", ID).exchange().expectStatus().isNoContent().expectBody().isEmpty();
        then(deleteBook).should().execute(new DeleteBookRequest(ID));
    }

    @Test
    void anotherMethodIs405WithAllow() {
        client
                .patch()
                .uri("/books/{id}", ID)
                .exchange()
                .expectStatus()
                .isEqualTo(405)
                .expectHeader()
                .valueEquals("Allow", "GET, HEAD, PUT, DELETE, OPTIONS")
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void aBodyOverTheRouteLimitIs413() {
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
        then(createBook).should(never()).execute(any());
    }
}
