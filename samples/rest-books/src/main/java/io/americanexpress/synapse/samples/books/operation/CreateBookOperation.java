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

import org.springframework.stereotype.Component;

import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.samples.books.business.AddBook;
import io.americanexpress.synapse.samples.books.business.model.NewBook;
import io.americanexpress.synapse.samples.books.model.Book;
import io.americanexpress.synapse.samples.books.model.CreateBookRequest;

/**
 * Adds a book. The pipeline validates the request (its Jakarta Validation constraints) before this runs; the business
 * operation applies the rules of the catalog.
 */
@Component
public class CreateBookOperation implements CommandOperation<CreateBookRequest, Book> {

    private final AddBook addBook;

    /**
     * Creates the operation.
     *
     * @param addBook the business operation that adds the book
     */
    public CreateBookOperation(AddBook addBook) {
        this.addBook = addBook;
    }

    @Override
    public Book execute(CreateBookRequest request) {
        NewBook book = new NewBook(request.isbn(), request.title(), request.author(), request.year(),
                request.channel());
        return BookMapping.toBook(addBook.execute(book));
    }
}
