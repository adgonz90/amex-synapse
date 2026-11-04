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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * A stand-in for a database: the catalog lives in memory and is lost when the service stops. It starts with three
 * books, so the API answers straight away.
 *
 * <p>
 * Every method is {@code synchronized}, so the store is thread-safe and the ISBN check of a write is atomic, as a
 * unique constraint would make it. Synapse's data modules (a later phase) replace this with a Spring Data repository
 * on a named store.
 */
@Component
public class InMemoryBookStore {

    /** Dune, by Frank Herbert. */
    public static final UUID DUNE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** The Left Hand of Darkness, by Ursula K. Le Guin. */
    public static final UUID LEFT_HAND_OF_DARKNESS = UUID.fromString("00000000-0000-0000-0000-000000000002");

    /** A Wizard of Earthsea, by Ursula K. Le Guin. */
    public static final UUID WIZARD_OF_EARTHSEA = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private final Map<UUID, StoredBook> books = new LinkedHashMap<>();

    /** Creates the store with its three seed books. */
    public InMemoryBookStore() {
        seed(new StoredBook(DUNE, "978-0441172719", "Dune", "Frank Herbert", 1965, null));
        seed(new StoredBook(LEFT_HAND_OF_DARKNESS, "978-0441478125", "The Left Hand of Darkness", "Ursula K. Le Guin",
                1969, null));
        seed(new StoredBook(WIZARD_OF_EARTHSEA, "978-0547773742", "A Wizard of Earthsea", "Ursula K. Le Guin", 1968,
                null));
    }

    private void seed(StoredBook book) {
        books.put(book.id(), book);
    }

    /**
     * Finds a book.
     *
     * @param id the id
     * @return the book, or empty
     */
    public synchronized Optional<StoredBook> find(UUID id) {
        return Optional.ofNullable(books.get(id));
    }

    /**
     * Returns every book, in the order they were added.
     *
     * @return a copy of the books
     */
    public synchronized List<StoredBook> findAll() {
        return new ArrayList<>(books.values());
    }

    /**
     * Adds a book unless another book has its ISBN.
     *
     * @param book the book, with a new id
     * @return {@link WriteOutcome#WRITTEN} or {@link WriteOutcome#ISBN_TAKEN}
     */
    public synchronized WriteOutcome insert(StoredBook book) {
        if (isbnTakenByAnother(book)) {
            return WriteOutcome.ISBN_TAKEN;
        }
        books.put(book.id(), book);
        return WriteOutcome.WRITTEN;
    }

    /**
     * Replaces the book with the same id, unless another book has its ISBN.
     *
     * @param book the new state of the book
     * @return {@link WriteOutcome#WRITTEN}, {@link WriteOutcome#NOT_FOUND} or {@link WriteOutcome#ISBN_TAKEN}
     */
    public synchronized WriteOutcome replace(StoredBook book) {
        if (!books.containsKey(book.id())) {
            return WriteOutcome.NOT_FOUND;
        }
        if (isbnTakenByAnother(book)) {
            return WriteOutcome.ISBN_TAKEN;
        }
        books.put(book.id(), book);
        return WriteOutcome.WRITTEN;
    }

    /**
     * Removes a book.
     *
     * @param id the id
     * @return the removed book, or empty when there was none
     */
    public synchronized Optional<StoredBook> remove(UUID id) {
        return Optional.ofNullable(books.remove(id));
    }

    /** Compares ISBNs exactly as given (no normalization of hyphens or ISBN-10/13): a stand-in, not ISBN handling. */
    private boolean isbnTakenByAnother(StoredBook book) {
        return books
                .values()
                .stream()
                .anyMatch(other -> other.isbn().equals(book.isbn()) && !other.id().equals(book.id()));
    }
}
