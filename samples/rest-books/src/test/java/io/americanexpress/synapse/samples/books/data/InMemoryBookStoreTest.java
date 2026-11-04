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

class InMemoryBookStoreTest {

    private final InMemoryBookStore store = new InMemoryBookStore();

    @Test
    void startsWithThreeBooks() {
        assertThat(store.findAll())
                .extracting(StoredBook::id)
                .containsExactly(InMemoryBookStore.DUNE, InMemoryBookStore.LEFT_HAND_OF_DARKNESS,
                        InMemoryBookStore.WIZARD_OF_EARTHSEA);
        assertThat(store.find(InMemoryBookStore.DUNE)).map(StoredBook::title).contains("Dune");
    }

    @Test
    void insertsABookWithAFreeIsbn() {
        StoredBook book = book(UUID.randomUUID(), "isbn-new");

        assertThat(store.insert(book)).isEqualTo(WriteOutcome.WRITTEN);
        assertThat(store.find(book.id())).contains(book);
    }

    @Test
    void rejectsAnIsbnThatAnotherBookHas() {
        StoredBook book = book(UUID.randomUUID(), "978-0441172719");

        assertThat(store.insert(book)).isEqualTo(WriteOutcome.ISBN_TAKEN);
        assertThat(store.find(book.id())).isEmpty();
    }

    @Test
    void replacesABookKeepingItsOwnIsbn() {
        StoredBook revised = book(InMemoryBookStore.DUNE, "978-0441172719");

        assertThat(store.replace(revised)).isEqualTo(WriteOutcome.WRITTEN);
        assertThat(store.find(InMemoryBookStore.DUNE)).contains(revised);
    }

    @Test
    void replaceReportsAMissingBookAndATakenIsbn() {
        assertThat(store.replace(book(UUID.randomUUID(), "isbn-new"))).isEqualTo(WriteOutcome.NOT_FOUND);
        assertThat(store.replace(book(InMemoryBookStore.DUNE, "978-0441478125"))).isEqualTo(WriteOutcome.ISBN_TAKEN);
    }

    @Test
    void removesABookOnce() {
        assertThat(store.remove(InMemoryBookStore.DUNE)).isPresent();
        assertThat(store.remove(InMemoryBookStore.DUNE)).isEmpty();
        assertThat(store.find(InMemoryBookStore.DUNE)).isEmpty();
    }

    private static StoredBook book(UUID id, String isbn) {
        return new StoredBook(id, isbn, "Title", "Author", null, null);
    }
}
