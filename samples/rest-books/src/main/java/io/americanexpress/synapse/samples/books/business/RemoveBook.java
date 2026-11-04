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
import io.americanexpress.synapse.samples.books.contract.BookErrors;
import io.americanexpress.synapse.samples.books.data.InMemoryBookStore;

/**
 * Removes a book from the catalog. It returns the removed book, although its only caller doesn't use it: a {@code Void}
 * output would have to return {@code null}, which NullAway rejects without a suppression ({@code BusinessOperation}
 * has no {@code @Nullable} bound on its output).
 */
@Component
public class RemoveBook implements BusinessOperation<UUID, CatalogBook> {

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
     * @param id the id of the book
     * @return the removed book
     * @throws SynapseException {@link BookErrors#BOOK_NOT_FOUND} when no book has the id
     */
    @Override
    public CatalogBook execute(UUID id) {
        return store
                .remove(id)
                .map(CatalogBookMapping::toCatalogBook)
                .orElseThrow(() -> new SynapseException(BookErrors.BOOK_NOT_FOUND, id));
    }
}
