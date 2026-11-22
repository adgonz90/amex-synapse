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

import org.springframework.stereotype.Component;

import io.americanexpress.synapse.core.conditional.VersionCondition.AnyVersion;
import io.americanexpress.synapse.core.conditional.VersionCondition.Version;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.operation.BusinessOperation;
import io.americanexpress.synapse.samples.books.business.model.BookRemoval;
import io.americanexpress.synapse.samples.books.business.model.CatalogBook;
import io.americanexpress.synapse.samples.books.contract.BookErrors;
import io.americanexpress.synapse.samples.books.data.InMemoryBookStore;
import io.americanexpress.synapse.samples.books.data.StoredBook;
import io.americanexpress.synapse.samples.books.data.WriteOutcome;

/**
 * Removes a book from the catalog, only if it is still at the version the caller expects. It returns the removed book,
 * although its only caller doesn't use it: a {@code Void} output would have to return {@code null}, which NullAway
 * rejects without a suppression ({@code BusinessOperation} has no {@code @Nullable} bound on its output).
 */
@Component
public class RemoveBook implements BusinessOperation<BookRemoval, CatalogBook> {

    private final InMemoryBookStore store;

    /**
     * Creates the business operation.
     *
     * @param store the catalog
     */
    public RemoveBook(InMemoryBookStore store) {
        this.store = store;
    }

    /**
     * Removes the book.
     *
     * @param removal the id of the book, and the version the caller expects
     * @return the removed book
     * @throws SynapseException {@link BookErrors#BOOK_NOT_FOUND} when no book has the id,
     *         {@link SynapseErrors#STALE_VERSION} when the book isn't at the expected version (the removal's, or the
     *         one read here for {@code AnyVersion})
     */
    @Override
    public CatalogBook execute(BookRemoval removal) {
        StoredBook current = store
                .find(removal.id())
                .orElseThrow(() -> new SynapseException(BookErrors.BOOK_NOT_FOUND, removal.id()));
        // A removal always names its version; AnyVersion (If-Match: *) is the version just read.
        long expected = switch (removal.version()) {
            case AnyVersion<Long> any -> current.version();
            case Version<Long>(Long version) -> version;
        };
        if (expected != current.version()) {
            throw new SynapseException(SynapseErrors.STALE_VERSION);
        }
        WriteOutcome outcome = store.remove(removal.id(), expected);
        if (outcome == WriteOutcome.NOT_FOUND) {
            // Removed by a concurrent request since it was read.
            throw new SynapseException(BookErrors.BOOK_NOT_FOUND, removal.id());
        }
        if (outcome == WriteOutcome.STALE) {
            // Changed by a concurrent request since it was read.
            throw new SynapseException(SynapseErrors.STALE_VERSION);
        }
        return CatalogBookMapping.toCatalogBook(current);
    }
}
