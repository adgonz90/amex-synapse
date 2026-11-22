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
import io.americanexpress.synapse.samples.books.model.BookV2;
import io.americanexpress.synapse.samples.books.model.GetBookRequest;

/**
 * Reads one book in version 2 of the API ({@link BookV2}, with a list of authors). It is its own operation, with its
 * own name ({@code get-book-v2}), so its logs and metrics are told apart from version 1's. An empty result is a 404
 * {@code BOOK_NOT_FOUND}, as in version 1.
 */
@Component
public class GetBookV2Operation implements QueryOperation<GetBookRequest, Optional<BookV2>> {

    private final InMemoryBookStore store;

    /**
     * Creates the operation.
     *
     * @param store the catalog
     */
    public GetBookV2Operation(InMemoryBookStore store) {
        this.store = store;
    }

    @Override
    public Optional<BookV2> execute(GetBookRequest request) {
        return store.find(request.id()).map(BookMapping::toBookV2);
    }
}
