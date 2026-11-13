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

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.samples.bookstore.contract.Book;
import io.americanexpress.synapse.samples.bookstore.data.books.StoredBook;

/** Maps the books store's entity to the contract's record. */
final class BookMapping {

    private BookMapping() {
    }

    /**
     * Maps a saved book. Its id and version are set once it is saved, and its other columns are not null; the getters
     * are nullable only because a new entity has none of them yet.
     *
     * @param book a saved book
     * @return the record
     */
    static Book toBook(StoredBook book) {
        return new Book(saved(book.getId(), "id"), saved(book.getIsbn(), "isbn"), saved(book.getTitle(), "title"),
                saved(book.getAuthor(), "author"), book.getPublishedYear(), saved(book.getVersion(), "version"));
    }

    /**
     * Returns the version of a saved book.
     *
     * @param book a saved book
     * @return its version
     */
    static long version(StoredBook book) {
        return saved(book.getVersion(), "version");
    }

    private static <T> T saved(@Nullable T value, String name) {
        return Objects.requireNonNull(value, () -> "A saved book has its " + name);
    }
}
