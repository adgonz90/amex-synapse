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

import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.operation.BusinessOperation;
import io.americanexpress.synapse.samples.books.business.model.BookRevision;
import io.americanexpress.synapse.samples.books.business.model.CatalogBook;
import io.americanexpress.synapse.samples.books.contract.BookErrors;
import io.americanexpress.synapse.samples.books.data.InMemoryBookStore;
import io.americanexpress.synapse.samples.books.data.StoredBook;

/**
 * Replaces the details of a book. Rules: one book per ISBN, and no lost updates: the write succeeds only if the book
 * is still at the version the change is based on.
 */
@Component
public class ReviseBook implements BusinessOperation<BookRevision, CatalogBook> {

    private final InMemoryBookStore store;

    /**
     * Creates the business operation.
     *
     * @param store the catalog
     */
    public ReviseBook(InMemoryBookStore store) {
        this.store = store;
    }

    /**
     * Replaces the book's details, keeping the channel it was added through, and increments its version.
     *
     * @param revision the new details, and the version they are based on (or {@code null})
     * @return the revised book
     * @throws SynapseException {@link BookErrors#BOOK_NOT_FOUND} when no book has the id,
     *         {@link SynapseErrors#STALE_VERSION} when the book isn't at the expected version (the revision's, or the
     *         one read here when the revision has none), {@link BookErrors#DUPLICATE_ISBN} when another book has the
     *         ISBN
     */
    @Override
    public CatalogBook execute(BookRevision revision) {
        StoredBook current = store
                .find(revision.id())
                .orElseThrow(() -> new SynapseException(BookErrors.BOOK_NOT_FOUND, revision.id()));
        // The caller's version (from If-Match over REST) when it sent one, otherwise the version just read. The
        // fast path fails without writing when the caller's version is already out of date.
        Long requested = revision.version();
        long expected = requested == null ? current.version() : requested;
        if (expected != current.version()) {
            throw new SynapseException(SynapseErrors.STALE_VERSION);
        }
        StoredBook revised = new StoredBook(revision.id(), revision.isbn(), revision.title(), revision.author(),
                revision.year(), current.addedVia(), expected + 1);
        return switch (store.replace(revised, expected)) {
            case WRITTEN -> CatalogBookMapping.toCatalogBook(revised);
            // Removed by a concurrent request since it was read.
            case NOT_FOUND -> throw new SynapseException(BookErrors.BOOK_NOT_FOUND, revision.id());
            // Changed by a concurrent request since it was read: the compare-and-set catches what the fast path can't.
            case STALE -> throw new SynapseException(SynapseErrors.STALE_VERSION);
            case ISBN_TAKEN -> throw new SynapseException(BookErrors.DUPLICATE_ISBN, revision.isbn());
        };
    }
}
