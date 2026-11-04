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

import java.util.List;

import org.springframework.stereotype.Component;

import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.samples.books.data.InMemoryBookStore;
import io.americanexpress.synapse.samples.books.data.StoredBook;
import io.americanexpress.synapse.samples.books.model.CatalogStats;

/** Counts the books and authors of the catalog. It takes no input ({@code Void}). */
@Component
public class GetCatalogStatsOperation implements QueryOperation<Void, CatalogStats> {

    private final InMemoryBookStore store;

    /**
     * Creates the operation.
     *
     * @param store the catalog
     */
    public GetCatalogStatsOperation(InMemoryBookStore store) {
        this.store = store;
    }

    @Override
    public CatalogStats execute(Void input) {
        List<StoredBook> books = store.findAll();
        return new CatalogStats(books.size(), (int) books.stream().map(StoredBook::author).distinct().count());
    }
}
