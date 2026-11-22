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

import java.util.Optional;

import org.springframework.stereotype.Component;

import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.samples.books.data.InMemoryBookStore;
import io.americanexpress.synapse.samples.books.model.Book;
import io.americanexpress.synapse.samples.books.model.GetBookRequest;

/**
 * Reads one book. An empty result is not an error here: the REST binding answers it with a 404 and the code
 * {@code BOOK_NOT_FOUND}. A query with no business rule reads the store directly.
 */
@Component
public class GetBookOperation implements QueryOperation<GetBookRequest, Optional<Book>> {

    private final InMemoryBookStore store;

    /**
     * Creates the operation.
     *
     * @param store the catalog
     */
    public GetBookOperation(InMemoryBookStore store) {
        this.store = store;
    }

    @Override
    public Optional<Book> execute(GetBookRequest request) {
        return store.find(request.id()).map(BookMapping::toBook);
    }
}
