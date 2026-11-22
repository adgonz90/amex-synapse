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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;

import io.americanexpress.synapse.core.conditional.VersionCondition;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;
import io.americanexpress.synapse.core.paging.SortOrder;
import io.americanexpress.synapse.samples.books.model.Book;
import io.americanexpress.synapse.samples.books.model.BookV2;
import io.americanexpress.synapse.samples.books.model.CatalogStats;
import io.americanexpress.synapse.samples.books.model.CreateBookRequest;
import io.americanexpress.synapse.samples.books.model.DeleteBookRequest;
import io.americanexpress.synapse.samples.books.model.GetBookRequest;
import io.americanexpress.synapse.samples.books.model.SearchBooksRequest;
import io.americanexpress.synapse.samples.books.model.UpdateBookRequest;
import io.americanexpress.synapse.samples.books.operation.CreateBookOperation;
import io.americanexpress.synapse.samples.books.operation.DeleteBookOperation;
import io.americanexpress.synapse.samples.books.operation.GetBookOperation;
import io.americanexpress.synapse.samples.books.operation.GetBookV2Operation;
import io.americanexpress.synapse.samples.books.operation.GetCatalogStatsOperation;
import io.americanexpress.synapse.samples.books.operation.SearchBooksOperation;
import io.americanexpress.synapse.samples.books.operation.UpdateBookOperation;
import io.americanexpress.synapse.test.rest.SynapseRestTest;

import static org.assertj.core.api.Assertions.assertThat;
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

    private static final Book DUNE = new Book(ID, "978-0441172719", "Dune", "Frank Herbert", 1965, null, 1);

    @Autowired
    RestTestClient client;

    @MockitoBean
    GetBookOperation getBook;

    @MockitoBean
    GetBookV2Operation getBookV2;

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
    void getAnswers304() {
        given(getBook.execute(new GetBookRequest(ID))).willReturn(Optional.of(DUNE));

        client
                .get()
                .uri("/books/{id}", ID)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("ETag", "\"1\"")
                .expectHeader()
                .valueEquals("Cache-Control", "no-cache")
                .expectHeader()
                .valueEquals("Vary", "Accept")
                .expectBody()
                .jsonPath("$.version")
                .isEqualTo(1);

        client
                .get()
                .uri("/books/{id}", ID)
                .header("If-None-Match", "\"1\"")
                .exchange()
                .expectStatus()
                .isNotModified()
                .expectHeader()
                .valueEquals("ETag", "\"1\"")
                .expectBody()
                .isEmpty();
    }

    @Test
    void versionTwoRouteWorksInTheSlice() {
        given(getBookV2.execute(new GetBookRequest(ID)))
                .willReturn(Optional.of(new BookV2(ID, "978-0441172719", "Dune", List.of("Frank Herbert"), 1965, 1)));
        given(getBook.execute(new GetBookRequest(ID))).willReturn(Optional.of(DUNE));

        client
                .get()
                .uri("/v2/books/{id}", ID)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("ETag", "\"1\"")
                .expectBody()
                .jsonPath("$.authors")
                .isEqualTo(List.of("Frank Herbert"))
                .jsonPath("$.author")
                .doesNotExist()
                .jsonPath("$.addedVia")
                .doesNotExist();
        // Version 1 stays on /books/{id}, with its own operation.
        client
                .get()
                .uri("/books/{id}", ID)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.author")
                .isEqualTo("Frank Herbert")
                .jsonPath("$.authors")
                .doesNotExist();
        then(getBookV2).should().execute(new GetBookRequest(ID));
        then(getBook).should().execute(new GetBookRequest(ID));

        // A version no route declares is no route at all.
        client
                .get()
                .uri("/v3/books/{id}", ID)
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("ROUTE_NOT_FOUND");
        given(getBookV2.execute(any())).willReturn(Optional.empty());
        client
                .get()
                .uri("/v2/books/{id}", ID)
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("BOOK_NOT_FOUND");
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
                // application-test.yaml turns detail on; .notFound fills the template with the path value.
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
        // No size: the page size defaults to synapse.paging.default-size (20).
        given(searchBooks.execute(new SearchBooksRequest("herbert", PageQuery.first(20))))
                .willReturn(PageResult.ofCursor(List.of(DUNE), null));

        client
                .get()
                .uri("/books?author=herbert")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.items[0].title")
                .isEqualTo("Dune");
    }

    @Test
    void searchReturnsAPageObject() {
        given(searchBooks.execute(any())).willReturn(new PageResult<>(List.of(DUNE), "1", true, 3L));

        var result = client
                .get()
                .uri("/books?sort=-year&size=1")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.items[0].title")
                .isEqualTo("Dune")
                .jsonPath("$.hasNext")
                .isEqualTo(true)
                .jsonPath("$.total")
                .isEqualTo(3)
                .returnResult();

        // The client gets an encrypted token, never the operation's own cursor ("1"), in the body and in Link.
        String body = new String(result.getResponseBodyContent(), StandardCharsets.UTF_8);
        String token = body.replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1");
        assertThat(token).isNotEqualTo("1").doesNotContain("\"");
        assertThat(result.getResponseHeaders().getFirst("Link"))
                .isEqualTo("</books?sort=-year&size=1&cursor=" + token + ">; rel=\"next\"");
        then(searchBooks)
                .should()
                .execute(new SearchBooksRequest(null,
                        new PageQuery(null, null, 1, List.of(new SortOrder("YEAR", SortOrder.Direction.DESC)))));
    }

    @Test
    void aSortKeyThatIsNotAllowedIsAViolation() {
        client
                .get()
                .uri("/books?sort=price")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.violations[0].field")
                .isEqualTo("sort")
                .jsonPath("$.violations[0].message")
                .isEqualTo("has a key that is not allowed (allowed: title, year)");
        then(searchBooks).should(never()).execute(any());
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
        Book created = new Book(ID, "isbn-1", "Title", "Author", null, "mobile", 1);
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
                .expectHeader()
                .valueEquals("ETag", "\"1\"")
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
        // No If-Match: the version is null, and the business operation checks the version it reads.
        UpdateBookRequest request = new UpdateBookRequest(ID, "978-0441172719", "Dune", "Frank Herbert", 1965, null);
        given(updateBook.execute(request)).willReturn(DUNE);

        client
                .put()
                .uri("/books/{id}", ID)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"isbn\":\"978-0441172719\",\"title\":\"Dune\",\"author\":\"Frank Herbert\",\"year\":1965}")
                .exchange()
                .expectStatus()
                .isOk()
                // RFC 9110 9.3.4: no ETag on a PUT response. The client reads the new version from the body.
                .expectHeader()
                .doesNotExist("ETag");
        then(updateBook).should().execute(request);
    }

    @Test
    void ifMatchFillsTheVersion() {
        UpdateBookRequest request = new UpdateBookRequest(ID, "978-0441172719", "Dune", "Frank Herbert", 1965,
                VersionCondition.of(3L));
        given(updateBook.execute(request)).willReturn(DUNE);

        client
                .put()
                .uri("/books/{id}", ID)
                .header("If-Match", "\"3\"")
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
        client
                .delete()
                .uri("/books/{id}", ID)
                .header("If-Match", "\"1\"")
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody()
                .isEmpty();
        then(deleteBook).should().execute(new DeleteBookRequest(ID, VersionCondition.of(1L)));
    }

    @Test
    void deleteWithIfMatchAnyIs204() {
        // * counts as present: the required version is AnyVersion.
        client.delete().uri("/books/{id}", ID).header("If-Match", "*").exchange().expectStatus().isNoContent();
        then(deleteBook).should().execute(new DeleteBookRequest(ID, VersionCondition.any()));
    }

    @Test
    void deleteWithoutIfMatchIs428() {
        client
                .delete()
                .uri("/books/{id}", ID)
                .exchange()
                .expectStatus()
                .isEqualTo(428)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("PRECONDITION_REQUIRED")
                .jsonPath("$.violations[0].field")
                .isEqualTo("If-Match");
        then(deleteBook).should(never()).execute(any());
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
