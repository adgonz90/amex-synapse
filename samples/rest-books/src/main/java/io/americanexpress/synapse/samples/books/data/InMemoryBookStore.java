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

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * A stand-in for a database: the catalog lives in memory and is lost when the service stops. It starts with three
 * books at version 1, so the API answers straight away.
 *
 * <p>
 * Every method is {@code synchronized}, so the store is thread-safe, and the ISBN and version checks of a write are
 * atomic with the write, as a unique constraint and a version column would make them. Synapse's data modules (a later
 * phase) replace this with a Spring Data repository on a named store.
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
        seed(new StoredBook(DUNE, "978-0441172719", "Dune", "Frank Herbert", 1965, null, 1));
        seed(new StoredBook(LEFT_HAND_OF_DARKNESS, "978-0441478125", "The Left Hand of Darkness", "Ursula K. Le Guin",
                1969, null, 1));
        seed(new StoredBook(WIZARD_OF_EARTHSEA, "978-0547773742", "A Wizard of Earthsea", "Ursula K. Le Guin", 1968,
                null, 1));
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
     * @param book the book, with a new id and version 1
     * @return {@link WriteOutcome#WRITTEN} or {@link WriteOutcome#ISBN_TAKEN}
     * @throws IllegalArgumentException if the version isn't 1
     */
    public synchronized WriteOutcome insert(StoredBook book) {
        if (book.version() != 1) {
            throw new IllegalArgumentException("A new book has version 1, not " + book.version());
        }
        if (isbnTakenByAnother(book)) {
            return WriteOutcome.ISBN_TAKEN;
        }
        books.put(book.id(), book);
        return WriteOutcome.WRITTEN;
    }

    /**
     * Replaces the book with the same id if its version is still {@code expectedVersion}, unless another book has its
     * ISBN. The version check and the write are one atomic step (a compare-and-set), as
     * {@code UPDATE ... WHERE id = ? AND version = ?} is in a database: when two requests read the same version, only
     * the first one's write succeeds.
     *
     * @param book the new state of the book, with the next version ({@code expectedVersion + 1})
     * @param expectedVersion the version the caller read and based its change on
     * @return {@link WriteOutcome#WRITTEN}, {@link WriteOutcome#NOT_FOUND}, {@link WriteOutcome#STALE} or
     *         {@link WriteOutcome#ISBN_TAKEN}
     * @throws IllegalArgumentException if the book's version isn't {@code expectedVersion + 1}
     */
    public synchronized WriteOutcome replace(StoredBook book, long expectedVersion) {
        if (book.version() != expectedVersion + 1) {
            throw new IllegalArgumentException(
                    "A replacement of version " + expectedVersion + " has version " + (expectedVersion + 1)
                            + ", not " + book.version());
        }
        StoredBook current = books.get(book.id());
        if (current == null) {
            return WriteOutcome.NOT_FOUND;
        }
        if (current.version() != expectedVersion) {
            return WriteOutcome.STALE;
        }
        if (isbnTakenByAnother(book)) {
            return WriteOutcome.ISBN_TAKEN;
        }
        books.put(book.id(), book);
        return WriteOutcome.WRITTEN;
    }

    /**
     * Removes a book, if its version is still {@code expectedVersion} when one is given (a compare-and-set, as for
     * {@link #replace}).
     *
     * @param id the id
     * @param expectedVersion the version the caller read, or {@code null} to remove whatever the version
     * @return {@link WriteOutcome#WRITTEN}, {@link WriteOutcome#NOT_FOUND} or {@link WriteOutcome#STALE}
     */
    public synchronized WriteOutcome remove(UUID id, @Nullable Long expectedVersion) {
        StoredBook current = books.get(id);
        if (current == null) {
            return WriteOutcome.NOT_FOUND;
        }
        if (expectedVersion != null && current.version() != expectedVersion) {
            return WriteOutcome.STALE;
        }
        books.remove(id);
        return WriteOutcome.WRITTEN;
    }

    /** Compares ISBNs exactly as given (no normalization of hyphens or ISBN-10/13): a stand-in, not ISBN handling. */
    private boolean isbnTakenByAnother(StoredBook book) {
        return books
                .values()
                .stream()
                .anyMatch(other -> other.isbn().equals(book.isbn()) && !other.id().equals(book.id()));
    }
}
