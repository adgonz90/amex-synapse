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

import io.americanexpress.synapse.samples.books.business.model.CatalogBook;
import io.americanexpress.synapse.samples.books.data.StoredBook;

/** Maps the store's books to the business model. Used only inside the business layer. */
final class CatalogBookMapping {

    private CatalogBookMapping() {
    }

    static CatalogBook toCatalogBook(StoredBook book) {
        return new CatalogBook(book.id(), book.isbn(), book.title(), book.author(), book.year(), book.addedVia());
    }
}
