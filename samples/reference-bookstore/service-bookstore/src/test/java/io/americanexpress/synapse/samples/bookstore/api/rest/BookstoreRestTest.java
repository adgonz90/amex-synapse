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
package io.americanexpress.synapse.samples.bookstore.api.rest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;

import io.americanexpress.synapse.core.conditional.VersionCondition;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;
import io.americanexpress.synapse.core.paging.SortOrder;
import io.americanexpress.synapse.samples.bookstore.contract.Book;
import io.americanexpress.synapse.samples.bookstore.contract.CreateBookRequest;
import io.americanexpress.synapse.samples.bookstore.contract.DeleteBookRequest;
import io.americanexpress.synapse.samples.bookstore.contract.GetBookRequest;
import io.americanexpress.synapse.samples.bookstore.contract.SearchBooksRequest;
import io.americanexpress.synapse.samples.bookstore.contract.UpdateBookRequest;
import io.americanexpress.synapse.samples.bookstore.operation.CreateBookOperation;
import io.americanexpress.synapse.samples.bookstore.operation.DeleteBookOperation;
import io.americanexpress.synapse.samples.bookstore.operation.GetBookOperation;
import io.americanexpress.synapse.samples.bookstore.operation.SearchBooksOperation;
import io.americanexpress.synapse.samples.bookstore.operation.UpdateBookOperation;
import io.americanexpress.synapse.test.rest.SynapseRestTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * The REST slice: the bindings, the edge and the problem responses on MockMvc, with every operation mocked. It checks
 * how requests bind to the operations' inputs and how outputs map to responses, including what only REST has: the
 * conditional GET (304). The answers both protocols must give alike are the scenarios of {@code BookstoreScenarios};
 * the operations are tested in {@code BookOperationsTest}. No store starts.
 */
@SynapseRestTest
class BookstoreRestTest {

    private static final UUID ID = UUID.fromString("019a5e3c-4f00-7000-8000-000000000001");

    private static final Book DUNE = new Book(ID, "978-0-441-17271-9", "Dune", "Frank Herbert", 1965, 4);

    private static final String DUNE_JSON = "{\"isbn\":\"978-0-441-17271-9\",\"title\":\"Dune\","
            + "\"author\":\"Frank Herbert\",\"publishedYear\":1965}";

    @Autowired
    RestTestClient client;

    @Autowired
    ApplicationContext context;

    @MockitoBean
    CreateBookOperation createBook;

    @MockitoBean
    GetBookOperation getBook;

    @MockitoBean
    SearchBooksOperation searchBooks;

    @MockitoBean
    UpdateBookOperation updateBook;

    @MockitoBean
    DeleteBookOperation deleteBook;

    @Test
    void createIs201WithLocationAndEtag() {
        given(createBook.execute(new CreateBookRequest("978-0-441-17271-9", "Dune", "Frank Herbert", 1965)))
                .willReturn(DUNE);

        client
                .post()
                .uri("/books")
                .contentType(MediaType.APPLICATION_JSON)
                .body(DUNE_JSON)
                .exchange()
                .expectStatus()
                .isCreated()
                .expectHeader()
                .valueEquals("Location", "/books/" + ID)
                .expectHeader()
                .valueEquals("ETag", "\"4\"")
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(ID.toString())
                .jsonPath("$.publishedYear")
                .isEqualTo(1965)
                .jsonPath("$.version")
                .isEqualTo(4);
    }

    @Test
    void getIs200WithEtagAndAnswersIfNoneMatchWith304() {
        given(getBook.execute(new GetBookRequest(ID))).willReturn(Optional.of(DUNE));

        client
                .get()
                .uri("/books/{id}", ID)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("ETag", "\"4\"")
                .expectBody()
                .jsonPath("$.title")
                .isEqualTo("Dune");

        // HTTP caching, REST only: gRPC has no 304, and GetBook takes no version.
        client
                .get()
                .uri("/books/{id}", ID)
                .header("If-None-Match", "\"4\"")
                .exchange()
                .expectStatus()
                .isNotModified()
                .expectHeader()
                .valueEquals("ETag", "\"4\"")
                .expectBody()
                .isEmpty();
    }

    @Test
    void anUnknownBookIs404BookNotFound() {
        given(getBook.execute(new GetBookRequest(ID))).willReturn(Optional.empty());

        client
                .get()
                .uri("/books/{id}", ID)
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("BOOK_NOT_FOUND");
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
                .isEqualTo("id");
        then(getBook).should(never()).execute(any());
    }

    @Test
    void theQueryStringFillsTheSearchAndThePageSignalsTheNextOne() {
        given(searchBooks.execute(any())).willReturn(PageResult.of(List.of(DUNE), true).withTotal(3));

        var result = client
                .get()
                .uri("/books?author=herbert&size=1&sort=title,-publishedYear")
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

        // The operation returned no cursor (a Spring Data Page): the Link is to the next offset, and nextCursor is a
        // token that carries it, which gRPC's page_token takes too.
        assertThat(result.getResponseHeaders().getFirst("Link"))
                .isEqualTo("</books?author=herbert&size=1&sort=title,-publishedYear&offset=1>; rel=\"next\"");
        String body = new String(result.getResponseBodyContent(), StandardCharsets.UTF_8);
        assertThat(body).containsPattern("\"nextCursor\":\"[A-Za-z0-9_-]+\"");
        // REST's lowerCamel keys arrive as the constant names, as over gRPC.
        then(searchBooks)
                .should()
                .execute(new SearchBooksRequest("herbert",
                        new PageQuery(null, null, 1, List
                                .of(new SortOrder("TITLE", SortOrder.Direction.ASC),
                                        new SortOrder("PUBLISHED_YEAR", SortOrder.Direction.DESC)))));
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
                .isEqualTo("has a key that is not allowed (allowed: title, publishedYear)");
        then(searchBooks).should(never()).execute(any());
    }

    @Test
    void updateTakesTheIdFromThePathAndTheVersionFromIfMatch() {
        UpdateBookRequest request = new UpdateBookRequest(ID, "978-0-441-17271-9", "Dune", "Frank Herbert", 1965,
                VersionCondition.of(3L));
        given(updateBook.execute(request)).willReturn(DUNE);

        client
                .put()
                .uri("/books/{id}", ID)
                .header("If-Match", "\"3\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(DUNE_JSON)
                .exchange()
                .expectStatus()
                .isOk()
                // RFC 9110 9.3.4: no ETag on a PUT response. The client reads the book again for its new one.
                .expectHeader()
                .doesNotExist("ETag");
        then(updateBook).should().execute(request);
    }

    @Test
    void aBodyIdThatDiffersFromThePathIsAViolation() {
        client
                .put()
                .uri("/books/{id}", ID)
                .header("If-Match", "\"3\"")
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
    void updateWithoutIfMatchIs428() {
        client
                .put()
                .uri("/books/{id}", ID)
                .contentType(MediaType.APPLICATION_JSON)
                .body(DUNE_JSON)
                .exchange()
                .expectStatus()
                .isEqualTo(428)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("PRECONDITION_REQUIRED")
                .jsonPath("$.violations[0].field")
                .isEqualTo("If-Match");
        then(updateBook).should(never()).execute(any());
    }

    @Test
    void deleteIs204AndStarIsAnyVersion() {
        client
                .delete()
                .uri("/books/{id}", ID)
                .header("If-Match", "*")
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody()
                .isEmpty();
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
                .isEqualTo("PRECONDITION_REQUIRED");
        then(deleteBook).should(never()).execute(any());
    }

    @Test
    void noStoreStarts() {
        assertThat(context.containsBean("booksDataSource")).isFalse();
    }
}
