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
package io.americanexpress.synapse.samples.books.operation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;
import io.americanexpress.synapse.core.paging.SortOrder;
import io.americanexpress.synapse.samples.books.contract.BookErrors;
import io.americanexpress.synapse.samples.books.data.InMemoryBookStore;
import io.americanexpress.synapse.samples.books.model.Book;
import io.americanexpress.synapse.samples.books.model.CreateBookRequest;
import io.americanexpress.synapse.samples.books.model.DeleteBookRequest;
import io.americanexpress.synapse.samples.books.model.GetBookRequest;
import io.americanexpress.synapse.samples.books.model.SearchBooksRequest;
import io.americanexpress.synapse.samples.books.model.UpdateBookRequest;
import io.americanexpress.synapse.test.operation.SynapseOperationTest;

import static io.americanexpress.synapse.test.assertj.SynapseAssertions.assertThatSynapseException;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The operations and business beans with the real pipeline, without a web server. The slice leaves out every other
 * component, so the test adds the in-memory store. The tests share that store (one Spring context), so each one
 * works on books it creates or doesn't change.
 */
@SynapseOperationTest
@Import(InMemoryBookStore.class)
class BookOperationsTest {

    @Autowired
    GetBookOperation getBook;

    @Autowired
    SearchBooksOperation searchBooks;

    @Autowired
    CreateBookOperation createBook;

    @Autowired
    UpdateBookOperation updateBook;

    @Autowired
    DeleteBookOperation deleteBook;

    @Test
    void createsAndReadsABookAtVersionOne() {
        Book created = createBook
                .execute(new CreateBookRequest("isbn-create", "The Dispossessed", "Ursula K. Le Guin", 1974, "web"));

        assertThat(created.addedVia()).isEqualTo("web");
        assertThat(created.version()).isEqualTo(1);
        assertThat(getBook.execute(new GetBookRequest(created.id()))).contains(created);
    }

    @Test
    void thePipelineValidatesTheRequestBeforeTheOperationRuns() {
        assertThatSynapseException(() -> createBook.execute(new CreateBookRequest("", " ", "Author", -1, null)))
                .hasCode(SynapseErrors.VALIDATION_FAILED)
                .hasCategory(ErrorCategory.INVALID_INPUT)
                .hasViolation("isbn", "must not be blank")
                .hasViolation("title", "must not be blank")
                .hasViolation("year", "must be greater than 0");
    }

    @Test
    void anIsbnAlreadyInTheCatalogIsDuplicateIsbn() {
        assertThatSynapseException(
                () -> createBook.execute(new CreateBookRequest("978-0441172719", "Dune", "Frank Herbert", 1965, null)))
                .hasCode(BookErrors.DUPLICATE_ISBN)
                .hasCategory(ErrorCategory.CONFLICT);
    }

    @Test
    void updatingAnUnknownBookIsBookNotFound() {
        assertThatSynapseException(() -> updateBook
                .execute(new UpdateBookRequest(UUID.randomUUID(), "isbn-update", "Title", "Author", null, null)))
                .hasCode(BookErrors.BOOK_NOT_FOUND);
    }

    @Test
    void anUpdateIncrementsTheVersionAndAStaleOneIsStaleVersion() {
        Book created = createBook.execute(new CreateBookRequest("isbn-update-version", "Title", "Author", null, null));

        Book updated = updateBook
                .execute(new UpdateBookRequest(created.id(), "isbn-update-version", "New title", "Author", null, 1L));

        assertThat(updated.version()).isEqualTo(2);
        assertThatSynapseException(() -> updateBook
                .execute(new UpdateBookRequest(created.id(), "isbn-update-version", "Lost", "Author", null, 1L)))
                .hasCode(SynapseErrors.STALE_VERSION)
                .hasCategory(ErrorCategory.CONFLICT);
        assertThat(getBook.execute(new GetBookRequest(created.id()))).contains(updated);
    }

    @Test
    void deletesABookOnce() {
        Book created = createBook.execute(new CreateBookRequest("isbn-delete", "Title", "Author", null, null));

        assertThatSynapseException(() -> deleteBook.execute(new DeleteBookRequest(created.id(), 2L)))
                .hasCode(SynapseErrors.STALE_VERSION);
        deleteBook.execute(new DeleteBookRequest(created.id(), 1L));

        assertThat(getBook.execute(new GetBookRequest(created.id()))).isEmpty();
        assertThatSynapseException(() -> deleteBook.execute(new DeleteBookRequest(created.id(), null)))
                .hasCode(BookErrors.BOOK_NOT_FOUND);
    }

    @Test
    void searchesByPartOfTheAuthorsNameIgnoringCase() {
        assertThat(searchBooks.execute(new SearchBooksRequest("HERBERT", PageQuery.first(20))).items())
                .extracting(Book::title)
                .containsExactly("Dune");
        assertThat(searchBooks.execute(new SearchBooksRequest(null, PageQuery.first(20))).items())
                .hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void pagesByCursorSortedByTitleByDefault() {
        addPagingBooks("cursor");

        PageResult<Book> first = searchBooks.execute(new SearchBooksRequest("paging-cursor", PageQuery.first(2)));

        assertThat(first.items()).extracting(Book::title).containsExactly("A", "B");
        assertThat(first.hasNext()).isTrue();
        assertThat(first.total()).isEqualTo(3);
        String cursor = Objects.requireNonNull(first.nextCursor());

        PageResult<Book> last = searchBooks
                .execute(new SearchBooksRequest("paging-cursor", PageQuery.ofCursor(cursor, 2, List.of())));

        assertThat(last.items()).extracting(Book::title).containsExactly("C");
        assertThat(last.hasNext()).isFalse();
        assertThat(last.nextCursor()).isNull();
    }

    @Test
    void pagesByOffsetInTheRequestedOrder() {
        addPagingBooks("offset");
        List<SortOrder> newestFirst = List.of(new SortOrder("YEAR", SortOrder.Direction.DESC));

        PageResult<Book> page = searchBooks
                .execute(new SearchBooksRequest("paging-offset", PageQuery.ofOffset(1, 2, newestFirst)));

        // By year, newest first: C (2003), A (2002), B (2001).
        assertThat(page.items()).extracting(Book::title).containsExactly("A", "B");
        assertThat(page.hasNext()).isFalse();

        PageResult<Book> beyond = searchBooks
                .execute(new SearchBooksRequest("paging-offset", PageQuery.ofOffset(10, 2, newestFirst)));

        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.hasNext()).isFalse();
    }

    @Test
    void booksWithoutAYearComeLastInBothDirections() {
        createBook.execute(new CreateBookRequest("isbn-years-x", "X", "paging-years", null, null));
        createBook.execute(new CreateBookRequest("isbn-years-y", "Y", "paging-years", 2000, null));
        createBook.execute(new CreateBookRequest("isbn-years-z", "Z", "paging-years", 1990, null));

        assertThat(titles(List.of(new SortOrder("YEAR", SortOrder.Direction.ASC)))).containsExactly("Z", "Y", "X");
        assertThat(titles(List.of(new SortOrder("YEAR", SortOrder.Direction.DESC)))).containsExactly("Y", "Z", "X");
        assertThat(titles(List.of(new SortOrder("TITLE", SortOrder.Direction.DESC)))).containsExactly("Z", "Y", "X");
    }

    private List<String> titles(List<SortOrder> sort) {
        return searchBooks
                .execute(new SearchBooksRequest("paging-years", PageQuery.ofOffset(0, 10, sort)))
                .items()
                .stream()
                .map(Book::title)
                .toList();
    }

    private void addPagingBooks(String author) {
        createBook.execute(new CreateBookRequest("isbn-" + author + "-b", "B", "paging-" + author, 2001, null));
        createBook.execute(new CreateBookRequest("isbn-" + author + "-c", "C", "paging-" + author, 2003, null));
        createBook.execute(new CreateBookRequest("isbn-" + author + "-a", "A", "paging-" + author, 2002, null));
    }
}
