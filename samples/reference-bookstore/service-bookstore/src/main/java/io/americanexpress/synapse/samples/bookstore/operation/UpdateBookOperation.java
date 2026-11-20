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
import org.springframework.transaction.annotation.Transactional;

import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.samples.bookstore.contract.Book;
import io.americanexpress.synapse.samples.bookstore.contract.BookErrors;
import io.americanexpress.synapse.samples.bookstore.contract.UpdateBookRequest;
import io.americanexpress.synapse.samples.bookstore.data.books.BookRepository;
import io.americanexpress.synapse.samples.bookstore.data.books.StoredBook;

/**
 * Replaces a book's details, only at the version the caller names. The request always names one (the version isn't
 * {@code @Nullable}, so the edges reject a request without it), and {@code AnyVersion} ({@code *}) matches whatever
 * version this operation reads.
 */
@Component
public class UpdateBookOperation implements CommandOperation<UpdateBookRequest, Book> {

    private final BookRepository books;

    /**
     * Creates the operation.
     *
     * @param books the books store's repository
     */
    public UpdateBookOperation(BookRepository books) {
        this.books = books;
    }

    /**
     * Reads the book, checks its version, and writes the change, in one transaction of the books store, so the read
     * and the write see the same row.
     *
     * @throws SynapseException {@link BookErrors#BOOK_NOT_FOUND} when no book has the id;
     *         {@link SynapseErrors#STALE_VERSION} when the book isn't at the version the request names. The request
     *         carried that version, so both edges answer it {@code PRECONDITION_FAILED} (REST 412, gRPC
     *         {@code ABORTED}). Another request that changes the book between the read and the write
     *         fails the write itself (JPA's {@code @Version}), which is {@code STALE_VERSION} too;
     *         {@link SynapseErrors#ALREADY_EXISTS} when another book has the new ISBN
     */
    @Override
    @Transactional
    public Book execute(UpdateBookRequest request) {
        StoredBook book = books
                .findById(request.id())
                .orElseThrow(() -> new SynapseException(BookErrors.BOOK_NOT_FOUND, request.id()));
        if (!request.version().matches(BookMapping.version(book))) {
            throw new SynapseException(SynapseErrors.STALE_VERSION);
        }
        book.revise(request.isbn(), request.title(), request.author(), request.publishedYear());
        // The flush writes the change now, so the answer has the new version, and a failure (the version check, the
        // ISBN's constraint) is this statement's, before the commit.
        return BookMapping.toBook(books.saveAndFlush(book));
    }
}
