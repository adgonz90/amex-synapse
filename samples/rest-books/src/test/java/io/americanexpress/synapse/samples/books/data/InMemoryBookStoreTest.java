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
package io.americanexpress.synapse.samples.books.data;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class InMemoryBookStoreTest {

    private final InMemoryBookStore store = new InMemoryBookStore();

    @Test
    void startsWithThreeBooksAtVersionOne() {
        assertThat(store.findAll())
                .extracting(StoredBook::id)
                .containsExactly(InMemoryBookStore.DUNE, InMemoryBookStore.LEFT_HAND_OF_DARKNESS,
                        InMemoryBookStore.WIZARD_OF_EARTHSEA);
        assertThat(store.findAll()).extracting(StoredBook::version).containsOnly(1L);
        assertThat(store.find(InMemoryBookStore.DUNE)).map(StoredBook::title).contains("Dune");
    }

    @Test
    void insertsABookWithAFreeIsbn() {
        StoredBook book = book(UUID.randomUUID(), "isbn-new", 1);

        assertThat(store.insert(book)).isEqualTo(WriteOutcome.WRITTEN);
        assertThat(store.find(book.id())).contains(book);
    }

    @Test
    void rejectsAnIsbnThatAnotherBookHas() {
        StoredBook book = book(UUID.randomUUID(), "978-0441172719", 1);

        assertThat(store.insert(book)).isEqualTo(WriteOutcome.ISBN_TAKEN);
        assertThat(store.find(book.id())).isEmpty();
    }

    @Test
    void replacesABookKeepingItsOwnIsbn() {
        StoredBook revised = book(InMemoryBookStore.DUNE, "978-0441172719", 2);

        assertThat(store.replace(revised, 1)).isEqualTo(WriteOutcome.WRITTEN);
        assertThat(store.find(InMemoryBookStore.DUNE)).contains(revised);
    }

    @Test
    void replaceReportsAMissingBookAndATakenIsbn() {
        assertThat(store.replace(book(UUID.randomUUID(), "isbn-new", 2), 1)).isEqualTo(WriteOutcome.NOT_FOUND);
        assertThat(store.replace(book(InMemoryBookStore.DUNE, "978-0441478125", 2), 1))
                .isEqualTo(WriteOutcome.ISBN_TAKEN);
    }

    @Test
    void replaceIsACompareAndSet() {
        StoredBook first = book(InMemoryBookStore.DUNE, "978-0441172719", 2);

        assertThat(store.replace(first, 1)).isEqualTo(WriteOutcome.WRITTEN);
        assertThat(store.find(InMemoryBookStore.DUNE)).map(StoredBook::version).contains(2L);

        // A second writer that also read version 1 loses, and the book keeps the first writer's state.
        StoredBook second = new StoredBook(InMemoryBookStore.DUNE, "978-0441172719", "Other", "Author", null, null, 2);
        assertThat(store.replace(second, 1)).isEqualTo(WriteOutcome.STALE);
        assertThat(store.find(InMemoryBookStore.DUNE)).contains(first);
    }

    @Test
    void versionsStartAtOneAndGrowByOne() {
        assertThatIllegalArgumentException().isThrownBy(() -> store.insert(book(UUID.randomUUID(), "isbn-new", 2)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> store.replace(book(InMemoryBookStore.DUNE, "978-0441172719", 3), 1));
        assertThat(store.find(InMemoryBookStore.DUNE)).map(StoredBook::version).contains(1L);
    }

    @Test
    void removeChecksTheVersionWhenGiven() {
        assertThat(store.remove(InMemoryBookStore.DUNE, 5L)).isEqualTo(WriteOutcome.STALE);
        assertThat(store.find(InMemoryBookStore.DUNE)).isPresent();

        assertThat(store.remove(InMemoryBookStore.DUNE, null)).isEqualTo(WriteOutcome.WRITTEN);
        assertThat(store.find(InMemoryBookStore.DUNE)).isEmpty();
    }

    @Test
    void removesABookOnce() {
        assertThat(store.remove(InMemoryBookStore.DUNE, 1L)).isEqualTo(WriteOutcome.WRITTEN);
        assertThat(store.remove(InMemoryBookStore.DUNE, 1L)).isEqualTo(WriteOutcome.NOT_FOUND);
        assertThat(store.find(InMemoryBookStore.DUNE)).isEmpty();
    }

    private static StoredBook book(UUID id, String isbn, long version) {
        return new StoredBook(id, isbn, "Title", "Author", null, null, version);
    }
}
