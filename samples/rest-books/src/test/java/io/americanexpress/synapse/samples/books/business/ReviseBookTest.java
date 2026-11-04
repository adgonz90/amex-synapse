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
package io.americanexpress.synapse.samples.books.business;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.samples.books.business.model.BookRevision;
import io.americanexpress.synapse.samples.books.business.model.CatalogBook;
import io.americanexpress.synapse.samples.books.business.model.NewBook;
import io.americanexpress.synapse.samples.books.contract.BookErrors;
import io.americanexpress.synapse.samples.books.data.InMemoryBookStore;
import io.americanexpress.synapse.samples.books.data.StoredBook;
import io.americanexpress.synapse.samples.books.data.WriteOutcome;
import io.americanexpress.synapse.test.operation.SynapseOperations;

import static io.americanexpress.synapse.test.assertj.SynapseAssertions.assertThatSynapseException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * {@link SynapseOperations#invoke} runs a business operation through its pipeline (validation and observation) around
 * a plain instance, with no Spring context.
 */
class ReviseBookTest {

    private final InMemoryBookStore store = new InMemoryBookStore();

    @Test
    void revisesABookAndKeepsTheChannelItWasAddedThrough() {
        CatalogBook added = SynapseOperations
                .invoke(new AddBook(store), new NewBook("isbn-1", "Title", "Author", null, "mobile"));

        CatalogBook revised = SynapseOperations
                .invoke(new ReviseBook(store), new BookRevision(added.id(), "isbn-1", "New title", "Author", 2001));

        assertThat(revised).isEqualTo(new CatalogBook(added.id(), "isbn-1", "New title", "Author", 2001, "mobile"));
        assertThat(store.find(added.id())).map(StoredBook::title).contains("New title");
    }

    @Test
    void removesABookAndReturnsIt() {
        CatalogBook removed = SynapseOperations.invoke(new RemoveBook(store), InMemoryBookStore.DUNE);

        assertThat(removed.title()).isEqualTo("Dune");
        assertThat(store.find(InMemoryBookStore.DUNE)).isEmpty();
    }

    @Test
    void anUnknownBookIsBookNotFound() {
        assertThatSynapseException(() -> SynapseOperations
                .invoke(new ReviseBook(store),
                        new BookRevision(UUID.randomUUID(), "isbn-1", "Title", "Author", null)))
                .hasCode(BookErrors.BOOK_NOT_FOUND);
    }

    @Test
    void anotherBooksIsbnIsDuplicateIsbn() {
        assertThatSynapseException(() -> SynapseOperations
                .invoke(new ReviseBook(store),
                        new BookRevision(InMemoryBookStore.DUNE, "978-0441478125", "Dune", "Frank Herbert", 1965)))
                .hasCode(BookErrors.DUPLICATE_ISBN);
    }

    @Test
    void aBookRemovedWhileBeingRevisedIsBookNotFound() {
        InMemoryBookStore racing = mock();
        StoredBook dune = new StoredBook(InMemoryBookStore.DUNE, "978-0441172719", "Dune", "Frank Herbert", 1965, null);
        given(racing.find(InMemoryBookStore.DUNE)).willReturn(Optional.of(dune));
        given(racing.replace(any())).willReturn(WriteOutcome.NOT_FOUND);

        assertThatSynapseException(() -> SynapseOperations
                .invoke(new ReviseBook(racing),
                        new BookRevision(InMemoryBookStore.DUNE, "978-0441172719", "Dune", "Frank Herbert", 1965)))
                .hasCode(BookErrors.BOOK_NOT_FOUND);
    }
}
