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

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.SynapseErrors;
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
    void createsAndReadsABook() {
        Book created = createBook
                .execute(new CreateBookRequest("isbn-create", "The Dispossessed", "Ursula K. Le Guin", 1974, "web"));

        assertThat(created.addedVia()).isEqualTo("web");
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
                .execute(new UpdateBookRequest(UUID.randomUUID(), "isbn-update", "Title", "Author", null)))
                .hasCode(BookErrors.BOOK_NOT_FOUND);
    }

    @Test
    void deletesABookOnce() {
        Book created = createBook.execute(new CreateBookRequest("isbn-delete", "Title", "Author", null, null));

        deleteBook.execute(new DeleteBookRequest(created.id()));

        assertThat(getBook.execute(new GetBookRequest(created.id()))).isEmpty();
        assertThatSynapseException(() -> deleteBook.execute(new DeleteBookRequest(created.id())))
                .hasCode(BookErrors.BOOK_NOT_FOUND);
    }

    @Test
    void searchesByPartOfTheAuthorsNameIgnoringCase() {
        assertThat(searchBooks.execute(new SearchBooksRequest("HERBERT")))
                .extracting(Book::title)
                .containsExactly("Dune");
        assertThat(searchBooks.execute(new SearchBooksRequest(null))).hasSizeGreaterThanOrEqualTo(3);
    }
}
