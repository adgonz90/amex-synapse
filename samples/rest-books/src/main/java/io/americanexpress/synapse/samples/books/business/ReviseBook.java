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

import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.operation.BusinessOperation;
import io.americanexpress.synapse.samples.books.business.model.BookRevision;
import io.americanexpress.synapse.samples.books.business.model.CatalogBook;
import io.americanexpress.synapse.samples.books.contract.BookErrors;
import io.americanexpress.synapse.samples.books.data.InMemoryBookStore;
import io.americanexpress.synapse.samples.books.data.StoredBook;

/** Replaces the details of a book. Rule: one book per ISBN. */
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
     * Replaces the book's details, keeping the channel it was added through.
     *
     * @param revision the new details
     * @return the revised book
     * @throws SynapseException {@link BookErrors#BOOK_NOT_FOUND} when no book has the id,
     *         {@link BookErrors#DUPLICATE_ISBN} when another book has the ISBN
     */
    @Override
    public CatalogBook execute(BookRevision revision) {
        StoredBook current = store
                .find(revision.id())
                .orElseThrow(() -> new SynapseException(BookErrors.BOOK_NOT_FOUND, revision.id()));
        StoredBook revised = new StoredBook(revision.id(), revision.isbn(), revision.title(), revision.author(),
                revision.year(), current.addedVia());
        return switch (store.replace(revised)) {
            case WRITTEN -> CatalogBookMapping.toCatalogBook(revised);
            // Removed by a concurrent request since it was read.
            case NOT_FOUND -> throw new SynapseException(BookErrors.BOOK_NOT_FOUND, revision.id());
            case ISBN_TAKEN -> throw new SynapseException(BookErrors.DUPLICATE_ISBN, revision.isbn());
        };
    }
}
