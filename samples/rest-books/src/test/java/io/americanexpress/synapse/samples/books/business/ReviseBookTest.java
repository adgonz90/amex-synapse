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

import io.americanexpress.synapse.core.conditional.VersionCondition;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.samples.books.business.model.BookRemoval;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * {@link SynapseOperations#invoke} runs a business operation through its pipeline (validation and observation) around
 * a plain instance, with no Spring context.
 */
class ReviseBookTest {

    private static final StoredBook DUNE = new StoredBook(InMemoryBookStore.DUNE, "978-0441172719", "Dune",
            "Frank Herbert", 1965, null, 1);

    private final InMemoryBookStore store = new InMemoryBookStore();

    @Test
    void revisesABookAndKeepsTheChannelItWasAddedThrough() {
        CatalogBook added = SynapseOperations
                .invoke(new AddBook(store), new NewBook("isbn-1", "Title", "Author", null, "mobile"));
        assertThat(added.version()).isEqualTo(1);

        CatalogBook revised = SynapseOperations
                .invoke(new ReviseBook(store),
                        new BookRevision(added.id(), "isbn-1", "New title", "Author", 2001, 1L));

        assertThat(revised).isEqualTo(new CatalogBook(added.id(), "isbn-1", "New title", "Author", 2001, "mobile", 2));
        assertThat(store.find(added.id())).map(StoredBook::title).contains("New title");
    }

    @Test
    void withoutAVersionTheRevisionChecksTheVersionItRead() {
        CatalogBook revised = SynapseOperations
                .invoke(new ReviseBook(store),
                        new BookRevision(InMemoryBookStore.DUNE, "978-0441172719", "Dune", "Frank Herbert", 1966,
                                null));

        assertThat(revised.version()).isEqualTo(2);
        assertThat(store.find(InMemoryBookStore.DUNE)).map(StoredBook::year).contains(1966);
    }

    @Test
    void aVersionThatIsNotTheCurrentOneIsStaleVersion() {
        assertThatSynapseException(() -> SynapseOperations
                .invoke(new ReviseBook(store),
                        new BookRevision(InMemoryBookStore.DUNE, "978-0441172719", "Changed", "Frank Herbert", 1965,
                                5L)))
                .hasCode(SynapseErrors.STALE_VERSION);
        assertThat(store.find(InMemoryBookStore.DUNE)).contains(DUNE);
    }

    @Test
    void aWriteThatWinsAfterTheReadIsStaleVersion() {
        InMemoryBookStore racing = mock();
        given(racing.find(InMemoryBookStore.DUNE)).willReturn(Optional.of(DUNE));
        given(racing.replace(any(), anyLong())).willReturn(WriteOutcome.STALE);

        assertThatSynapseException(() -> SynapseOperations
                .invoke(new ReviseBook(racing),
                        new BookRevision(InMemoryBookStore.DUNE, "978-0441172719", "Dune", "Frank Herbert", 1965,
                                1L)))
                .hasCode(SynapseErrors.STALE_VERSION);
    }

    @Test
    void anUnknownBookIsBookNotFound() {
        assertThatSynapseException(() -> SynapseOperations
                .invoke(new ReviseBook(store),
                        new BookRevision(UUID.randomUUID(), "isbn-1", "Title", "Author", null, null)))
                .hasCode(BookErrors.BOOK_NOT_FOUND);
    }

    @Test
    void anotherBooksIsbnIsDuplicateIsbn() {
        assertThatSynapseException(() -> SynapseOperations
                .invoke(new ReviseBook(store),
                        new BookRevision(InMemoryBookStore.DUNE, "978-0441478125", "Dune", "Frank Herbert", 1965,
                                null)))
                .hasCode(BookErrors.DUPLICATE_ISBN);
    }

    @Test
    void aBookRemovedWhileBeingRevisedIsBookNotFound() {
        InMemoryBookStore racing = mock();
        given(racing.find(InMemoryBookStore.DUNE)).willReturn(Optional.of(DUNE));
        given(racing.replace(any(), anyLong())).willReturn(WriteOutcome.NOT_FOUND);

        assertThatSynapseException(() -> SynapseOperations
                .invoke(new ReviseBook(racing),
                        new BookRevision(InMemoryBookStore.DUNE, "978-0441172719", "Dune", "Frank Herbert", 1965,
                                null)))
                .hasCode(BookErrors.BOOK_NOT_FOUND);
    }

    @Test
    void removesABookAndReturnsIt() {
        CatalogBook removed = SynapseOperations
                .invoke(new RemoveBook(store), new BookRemoval(InMemoryBookStore.DUNE, VersionCondition.of(1L)));

        assertThat(removed.title()).isEqualTo("Dune");
        assertThat(store.find(InMemoryBookStore.DUNE)).isEmpty();
    }

    @Test
    void removingAnyVersionRemovesTheVersionItRead() {
        SynapseOperations
                .invoke(new RemoveBook(store), new BookRemoval(InMemoryBookStore.DUNE, VersionCondition.any()));

        assertThat(store.find(InMemoryBookStore.DUNE)).isEmpty();
    }

    @Test
    void removingWithAStaleVersionIsStaleVersionAndKeepsTheBook() {
        assertThatSynapseException(() -> SynapseOperations
                .invoke(new RemoveBook(store), new BookRemoval(InMemoryBookStore.DUNE, VersionCondition.of(5L))))
                .hasCode(SynapseErrors.STALE_VERSION);
        assertThat(store.find(InMemoryBookStore.DUNE)).contains(DUNE);
    }

    @Test
    void removingAnUnknownBookIsBookNotFound() {
        assertThatSynapseException(
                () -> SynapseOperations
                        .invoke(new RemoveBook(store), new BookRemoval(UUID.randomUUID(), VersionCondition.any())))
                .hasCode(BookErrors.BOOK_NOT_FOUND);
    }

    @Test
    void aRemovalThatLosesARaceIsStaleVersionOrBookNotFound() {
        InMemoryBookStore racing = mock();
        given(racing.find(InMemoryBookStore.DUNE)).willReturn(Optional.of(DUNE));
        given(racing.remove(InMemoryBookStore.DUNE, 1L)).willReturn(WriteOutcome.STALE, WriteOutcome.NOT_FOUND);
        RemoveBook removeBook = new RemoveBook(racing);
        BookRemoval removal = new BookRemoval(InMemoryBookStore.DUNE, VersionCondition.of(1L));

        // Changed by a concurrent request since it was read...
        assertThatSynapseException(() -> SynapseOperations.invoke(removeBook, removal))
                .hasCode(SynapseErrors.STALE_VERSION);
        // ...or removed by one.
        assertThatSynapseException(() -> SynapseOperations.invoke(removeBook, removal))
                .hasCode(BookErrors.BOOK_NOT_FOUND);
    }
}
