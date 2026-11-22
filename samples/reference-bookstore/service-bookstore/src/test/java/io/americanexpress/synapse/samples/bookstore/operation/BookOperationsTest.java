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
package io.americanexpress.synapse.samples.bookstore.operation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import io.americanexpress.synapse.core.conditional.VersionCondition;
import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;
import io.americanexpress.synapse.core.paging.SortOrder;
import io.americanexpress.synapse.data.jpa.IdEntity;
import io.americanexpress.synapse.data.jpa.VersionedEntity;
import io.americanexpress.synapse.samples.bookstore.contract.Book;
import io.americanexpress.synapse.samples.bookstore.contract.BookErrors;
import io.americanexpress.synapse.samples.bookstore.contract.CreateBookRequest;
import io.americanexpress.synapse.samples.bookstore.contract.DeleteBookRequest;
import io.americanexpress.synapse.samples.bookstore.contract.GetBookRequest;
import io.americanexpress.synapse.samples.bookstore.contract.SearchBooksRequest;
import io.americanexpress.synapse.samples.bookstore.contract.UpdateBookRequest;
import io.americanexpress.synapse.samples.bookstore.data.books.BookRepository;
import io.americanexpress.synapse.samples.bookstore.data.books.StoredBook;
import io.americanexpress.synapse.test.operation.SynapseOperationTest;

import static io.americanexpress.synapse.test.assertj.SynapseAssertions.assertThatSynapseException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

/**
 * The operations with the real pipeline (validation, error translation), without a web server or a database: the
 * slice starts only the operations, so the store's repository is a mock. {@code BookstoreJvmIT} runs them on
 * PostgreSQL.
 */
@SynapseOperationTest
class BookOperationsTest {

    private static final UUID ID = UUID.fromString("019a5e3c-4f00-7000-8000-000000000001");

    @Autowired
    CreateBookOperation createBook;

    @Autowired
    GetBookOperation getBook;

    @Autowired
    SearchBooksOperation searchBooks;

    @Autowired
    UpdateBookOperation updateBook;

    @Autowired
    DeleteBookOperation deleteBook;

    @MockitoBean
    BookRepository books;

    @Test
    void createMapsAndSaves() {
        given(books.saveAndFlush(any())).willAnswer(invocation -> saved(invocation.getArgument(0), 0));

        Book created = createBook
                .execute(new CreateBookRequest("978-0-441-47812-5", "The Left Hand of Darkness",
                        "Ursula K. Le Guin", 1969));

        assertThat(created)
                .isEqualTo(new Book(ID, "978-0-441-47812-5", "The Left Hand of Darkness", "Ursula K. Le Guin", 1969,
                        0));
        ArgumentCaptor<StoredBook> saved = ArgumentCaptor.forClass(StoredBook.class);
        then(books).should().saveAndFlush(saved.capture());
        assertThat(saved.getValue().getIsbn()).isEqualTo("978-0-441-47812-5");
        assertThat(saved.getValue().getTitle()).isEqualTo("The Left Hand of Darkness");
        assertThat(saved.getValue().getAuthor()).isEqualTo("Ursula K. Le Guin");
        assertThat(saved.getValue().getPublishedYear()).isEqualTo(1969);
    }

    @Test
    void thePipelineValidatesTheRequestBeforeTheOperationRuns() {
        assertThatSynapseException(() -> createBook.execute(new CreateBookRequest("", " ", "Author", 0)))
                .hasCode(SynapseErrors.VALIDATION_FAILED)
                .hasCategory(ErrorCategory.INVALID_INPUT)
                .hasViolation("isbn", "must not be blank")
                .hasViolation("title", "must not be blank")
                .hasViolation("publishedYear", "must be greater than 0");
        then(books).shouldHaveNoInteractions();
    }

    @Test
    void getMapsTheStoredBookOrNothing() {
        given(books.findById(ID)).willReturn(Optional.of(dune(4)));

        assertThat(getBook.execute(new GetBookRequest(ID)))
                .contains(new Book(ID, "978-0-441-17271-9", "Dune", "Frank Herbert", null, 4));
        assertThat(getBook.execute(new GetBookRequest(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void updateWithTheVersionRevisesAndFlushes() {
        StoredBook stored = dune(2);
        given(books.findById(ID)).willReturn(Optional.of(stored));
        // The flush writes the change, and JPA raises the version.
        given(books.saveAndFlush(stored)).willAnswer(invocation -> saved(invocation.getArgument(0), 3));

        Book updated = updateBook.execute(update(VersionCondition.of(2L)));

        assertThat(updated).isEqualTo(new Book(ID, "978-0-441-17271-9", "Dune", "Frank Herbert", 1965, 3));
        assertThat(stored.getPublishedYear()).isEqualTo(1965);
    }

    @Test
    void updateWithAnotherVersionIsStaleVersionWithoutSave() {
        given(books.findById(ID)).willReturn(Optional.of(dune(2)));

        // Both edges answer this PRECONDITION_FAILED, because the request carried the version.
        assertThatSynapseException(() -> updateBook.execute(update(VersionCondition.of(3L))))
                .hasCode(SynapseErrors.STALE_VERSION)
                .hasCategory(ErrorCategory.CONFLICT);
        then(books).should(never()).saveAndFlush(any());
    }

    @Test
    void updateOfAnUnknownBookIsBookNotFound() {
        given(books.findById(ID)).willReturn(Optional.empty());

        assertThatSynapseException(() -> updateBook.execute(update(VersionCondition.any())))
                .hasCode(BookErrors.BOOK_NOT_FOUND)
                .hasCategory(ErrorCategory.NOT_FOUND);
    }

    @Test
    void deleteWithAnyVersionDeletes() {
        StoredBook stored = dune(5);
        given(books.findById(ID)).willReturn(Optional.of(stored));

        deleteBook.execute(new DeleteBookRequest(ID, VersionCondition.any()));

        // Deleted, then flushed, so a lost race fails this statement rather than the commit.
        InOrder order = inOrder(books);
        order.verify(books).delete(stored);
        order.verify(books).flush();
    }

    @Test
    void deleteWithAnotherVersionIsStaleVersionWithoutDelete() {
        given(books.findById(ID)).willReturn(Optional.of(dune(5)));

        assertThatSynapseException(() -> deleteBook.execute(new DeleteBookRequest(ID, VersionCondition.of(4L))))
                .hasCode(SynapseErrors.STALE_VERSION);
        then(books).should(never()).delete(any());
    }

    @Test
    void searchByAuthorReturnsTheSpringDataPageWithItsTotalAndNoCursor() {
        given(books.findByAuthorContainingIgnoreCase(eq("herbert"), any(Pageable.class)))
                .willAnswer(invocation -> page(invocation.getArgument(1), 7));

        PageResult<Book> page = searchBooks
                .execute(new SearchBooksRequest("herbert",
                        PageQuery.ofOffset(2, 1, List.of(new SortOrder("TITLE", SortOrder.Direction.ASC)))));

        assertThat(page.items()).containsExactly(new Book(ID, "978-0-441-17271-9", "Dune", "Frank Herbert", null, 0));
        assertThat(page.hasNext()).isTrue();
        assertThat(page.total()).isEqualTo(7);
        // No cursor: the edges give the caller a token that carries the next offset.
        assertThat(page.nextCursor()).isNull();
        Pageable pageable = searched();
        assertThat(pageable.getOffset()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(1);
        // TITLE is the entity's title; ties go by id.
        assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Order.asc("title"), Sort.Order.asc("id")));
    }

    @Test
    void searchWithoutAuthorReadsEveryBookByTitleThenId() {
        given(books.findAll(any(Pageable.class))).willAnswer(invocation -> page(invocation.getArgument(0), 1));

        searchBooks.execute(new SearchBooksRequest(null, PageQuery.first(20)));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        then(books).should().findAll(pageable.capture());
        assertThat(pageable.getValue().getOffset()).isZero();
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Order.asc("title"), Sort.Order.asc("id")));
    }

    @Test
    void searchSortsByTheKeysThenTitleThenId() {
        given(books.findByAuthorContainingIgnoreCase(eq("guin"), any(Pageable.class)))
                .willAnswer(invocation -> page(invocation.getArgument(1), 1));

        searchBooks
                .execute(new SearchBooksRequest("guin",
                        new PageQuery(null, null, 20,
                                List.of(new SortOrder("PUBLISHED_YEAR", SortOrder.Direction.DESC)))));

        assertThat(searched().getSort())
                .isEqualTo(Sort.by(Sort.Order.desc("publishedYear"), Sort.Order.asc("title"), Sort.Order.asc("id")));
    }

    @Test
    void searchByTitleDescendingKeepsTheCallersTitleOrder() {
        given(books.findByAuthorContainingIgnoreCase(eq("guin"), any(Pageable.class)))
                .willAnswer(invocation -> page(invocation.getArgument(1), 1));

        searchBooks
                .execute(new SearchBooksRequest("guin",
                        new PageQuery(null, null, 20, List.of(new SortOrder("TITLE", SortOrder.Direction.DESC)))));

        assertThat(searched().getSort()).isEqualTo(Sort.by(Sort.Order.desc("title"), Sort.Order.asc("id")));
    }

    private Pageable searched() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        then(books).should().findByAuthorContainingIgnoreCase(any(), pageable.capture());
        return pageable.getValue();
    }

    private static UpdateBookRequest update(VersionCondition<Long> version) {
        return new UpdateBookRequest(ID, "978-0-441-17271-9", "Dune", "Frank Herbert", 1965, version);
    }

    /** Dune as the store returns it: saved, at a version. */
    private static StoredBook dune(long version) {
        return saved(new StoredBook("978-0-441-17271-9", "Dune", "Frank Herbert", null), version);
    }

    /** The book with the id and version that JPA sets when it saves one. */
    private static StoredBook saved(StoredBook book, long version) {
        ReflectionTestUtils.setField(book, IdEntity.class, "id", ID, UUID.class);
        ReflectionTestUtils.setField(book, VersionedEntity.class, "version", version, Long.class);
        return book;
    }

    /** One page with Dune on it, of a search that matches {@code total} books. */
    private static Page<StoredBook> page(Pageable pageable, long total) {
        return new PageImpl<>(List.of(dune(0)), pageable, total);
    }
}
