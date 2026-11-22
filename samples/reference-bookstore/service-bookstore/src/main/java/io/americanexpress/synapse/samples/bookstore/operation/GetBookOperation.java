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
package io.americanexpress.synapse.samples.bookstore.operation;

import java.util.Optional;

import org.springframework.stereotype.Component;

import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.samples.bookstore.contract.Book;
import io.americanexpress.synapse.samples.bookstore.contract.GetBookRequest;
import io.americanexpress.synapse.samples.bookstore.data.books.BookRepository;

/**
 * Reads one book. An empty result is not an error here: each protocol's binding answers it with
 * {@code BOOK_NOT_FOUND} ({@code .notFound(...)}): REST 404, gRPC {@code NOT_FOUND}.
 */
@Component
public class GetBookOperation implements QueryOperation<GetBookRequest, Optional<Book>> {

    private final BookRepository books;

    /**
     * Creates the operation.
     *
     * @param books the books store's repository
     */
    public GetBookOperation(BookRepository books) {
        this.books = books;
    }

    @Override
    public Optional<Book> execute(GetBookRequest request) {
        return books.findById(request.id()).map(BookMapping::toBook);
    }
}
