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

import io.americanexpress.synapse.samples.books.business.model.CatalogBook;
import io.americanexpress.synapse.samples.books.data.StoredBook;
import io.americanexpress.synapse.samples.books.model.Book;

/**
 * Maps books to the service's model. Each layer owns its model and the caller does the mapping: the commands get the
 * business model from the business operations, and the queries, which read the store directly, get the store's model.
 */
final class BookMapping {

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
}
