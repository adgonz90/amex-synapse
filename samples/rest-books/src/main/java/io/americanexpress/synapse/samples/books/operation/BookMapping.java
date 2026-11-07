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

import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.core.conditional.VersionCondition;
import io.americanexpress.synapse.core.conditional.VersionCondition.AnyVersion;
import io.americanexpress.synapse.core.conditional.VersionCondition.Version;
import io.americanexpress.synapse.samples.books.business.model.BookRemoval;
import io.americanexpress.synapse.samples.books.business.model.BookRevision;
import io.americanexpress.synapse.samples.books.business.model.CatalogBook;
import io.americanexpress.synapse.samples.books.data.StoredBook;
import io.americanexpress.synapse.samples.books.model.Book;
import io.americanexpress.synapse.samples.books.model.BookV2;
import io.americanexpress.synapse.samples.books.model.DeleteBookRequest;
import io.americanexpress.synapse.samples.books.model.UpdateBookRequest;

/**
 * Maps books to the service's model. Each layer owns its model and the caller does the mapping: the commands get the
 * business model from the business operations, and the queries, which read the store directly, get the store's model.
 */
final class BookMapping {

    /** How the catalog separates the authors of one book. */
    private static final Pattern AUTHOR_SEPARATOR = Pattern.compile(", ", Pattern.LITERAL);

    private BookMapping() {
    }

    static Book toBook(CatalogBook book) {
        return new Book(book.id(), book.isbn(), book.title(), book.author(), book.year(), book.addedVia(),
                book.version());
    }

    static Book toBook(StoredBook book) {
        return new Book(book.id(), book.isbn(), book.title(), book.author(), book.year(), book.addedVia(),
                book.version());
    }

    /**
     * Maps a stored book to version 2 of the API. The store keeps the authors in one string, as version 1 shows them;
     * version 2 splits it on {@code ", "}.
     */
    static BookV2 toBookV2(StoredBook book) {
        return new BookV2(book.id(), book.isbn(), book.title(), AUTHOR_SEPARATOR.splitAsStream(book.author()).toList(),
                book.year(), book.version());
    }

    static BookRevision toRevision(UpdateBookRequest request) {
        return new BookRevision(request.id(), request.isbn(), request.title(), request.author(), request.year(),
                expectedVersion(request.version()));
    }

    /** A removal always carries its condition: the delete's contract requires one, so it is never {@code null}. */
    static BookRemoval toRemoval(DeleteBookRequest request) {
        return new BookRemoval(request.id(), request.version());
    }

    /**
     * Returns the version an update's business operation must find: the one a {@code Version} names, or {@code null}
     * for {@code AnyVersion} ({@code If-Match: *}) and for no condition (an update's is optional), which both mean "the
     * version it reads". An update needs the book to exist, so here {@code *} and no {@code If-Match} do the same
     * thing.
     */
    private static @Nullable Long expectedVersion(@Nullable VersionCondition<Long> condition) {
        return switch (condition) {
            case null -> null;
            case AnyVersion<Long> any -> null;
            case Version<Long>(Long version) -> version;
        };
    }
}
