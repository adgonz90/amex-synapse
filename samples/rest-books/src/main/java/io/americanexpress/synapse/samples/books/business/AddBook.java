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

import java.util.UUID;

import org.springframework.stereotype.Component;

import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.operation.BusinessOperation;
import io.americanexpress.synapse.samples.books.business.model.CatalogBook;
import io.americanexpress.synapse.samples.books.business.model.NewBook;
import io.americanexpress.synapse.samples.books.contract.BookErrors;
import io.americanexpress.synapse.samples.books.data.InMemoryBookStore;
import io.americanexpress.synapse.samples.books.data.StoredBook;
import io.americanexpress.synapse.samples.books.data.WriteOutcome;

/** Adds a book to the catalog. Rule: one book per ISBN. */
@Component
public class AddBook implements BusinessOperation<NewBook, CatalogBook> {

    private final InMemoryBookStore store;

    /**
     * Creates the business operation.
     *
     * @param store the catalog
     */
    public AddBook(InMemoryBookStore store) {
        this.store = store;
    }

    /**
     * Adds the book with a new id.
     *
     * @param book the book
     * @return the added book
     * @throws SynapseException {@link BookErrors#DUPLICATE_ISBN} when another book has the ISBN
     */
    @Override
    public CatalogBook execute(NewBook book) {
        StoredBook stored = new StoredBook(UUID.randomUUID(), book.isbn(), book.title(), book.author(), book.year(),
                book.addedVia());
        if (store.insert(stored) == WriteOutcome.ISBN_TAKEN) {
            throw new SynapseException(BookErrors.DUPLICATE_ISBN, book.isbn());
        }
        return CatalogBookMapping.toCatalogBook(stored);
    }
}
