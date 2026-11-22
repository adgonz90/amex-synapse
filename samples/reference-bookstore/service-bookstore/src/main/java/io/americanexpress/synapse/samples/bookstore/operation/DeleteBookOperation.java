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
import io.americanexpress.synapse.samples.bookstore.contract.BookErrors;
import io.americanexpress.synapse.samples.bookstore.contract.DeleteBookRequest;
import io.americanexpress.synapse.samples.bookstore.data.books.BookRepository;
import io.americanexpress.synapse.samples.bookstore.data.books.StoredBook;

/**
 * Removes a book, only at the version the caller names, as {@link UpdateBookOperation} changes one. It has no output
 * ({@code Void}): REST answers 204, gRPC {@code google.protobuf.Empty}.
 */
@Component
public class DeleteBookOperation implements CommandOperation<DeleteBookRequest, Void> {

    private final BookRepository books;

    /**
     * Creates the operation.
     *
     * @param books the books store's repository
     */
    public DeleteBookOperation(BookRepository books) {
        this.books = books;
    }

    /**
     * Reads the book, checks its version, and deletes it, in one transaction of the books store.
     *
     * @throws SynapseException {@link BookErrors#BOOK_NOT_FOUND} when no book has the id;
     *         {@link SynapseErrors#STALE_VERSION} when the book isn't at the version the request names, which both
     *         edges answer {@code PRECONDITION_FAILED}. A change between the read and the delete fails the delete
     *         itself (JPA's {@code @Version}), which is {@code STALE_VERSION} too
     */
    @Override
    @Transactional
    // A Void operation returns null, and the output type of ServiceOperation has no @Nullable bound, so NullAway has to
    // be told.
    @SuppressWarnings("NullAway")
    public Void execute(DeleteBookRequest request) {
        StoredBook book = books
                .findById(request.id())
                .orElseThrow(() -> new SynapseException(BookErrors.BOOK_NOT_FOUND, request.id()));
        if (!request.version().matches(BookMapping.version(book))) {
            throw new SynapseException(SynapseErrors.STALE_VERSION);
        }
        books.delete(book);
        // The flush writes the delete now, so a failure (the version check) is this statement's, before the commit, as
        // in UpdateBookOperation.
        books.flush();
        return null;
    }
}
