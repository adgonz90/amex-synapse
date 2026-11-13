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

import org.springframework.stereotype.Component;

import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.samples.bookstore.contract.Book;
import io.americanexpress.synapse.samples.bookstore.contract.CreateBookRequest;
import io.americanexpress.synapse.samples.bookstore.data.books.BookRepository;
import io.americanexpress.synapse.samples.bookstore.data.books.StoredBook;

/**
 * Adds a book to the catalog, at version 0. The pipeline validates the request before this runs. One repository call
 * needs no {@code @Transactional}: the repository runs it in a transaction of its store.
 */
@Component
public class CreateBookOperation implements CommandOperation<CreateBookRequest, Book> {

    private final BookRepository books;

    /**
     * Creates the operation.
     *
     * @param books the books store's repository
     */
    public CreateBookOperation(BookRepository books) {
        this.books = books;
    }

    /**
     * Saves the book, and flushes, so the INSERT runs now: the answer has the id and version the store gave it.
     *
     * @throws SynapseException {@link SynapseErrors#ALREADY_EXISTS} when another book has the ISBN: the table's unique
     *         constraint fails the INSERT, and Synapse's data translator names it, also when two requests race
     */
    @Override
    public Book execute(CreateBookRequest request) {
        StoredBook book = new StoredBook(request.isbn(), request.title(), request.author(), request.publishedYear());
        return BookMapping.toBook(books.saveAndFlush(book));
    }
}
