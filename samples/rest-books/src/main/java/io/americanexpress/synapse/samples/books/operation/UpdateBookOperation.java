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
import io.americanexpress.synapse.samples.books.business.ReviseBook;
import io.americanexpress.synapse.samples.books.business.model.BookRevision;
import io.americanexpress.synapse.samples.books.model.Book;
import io.americanexpress.synapse.samples.books.model.UpdateBookRequest;

/** Replaces the details of a book. */
@Component
public class UpdateBookOperation implements CommandOperation<UpdateBookRequest, Book> {

    private final ReviseBook reviseBook;

    /**
     * Creates the operation.
     *
     * @param reviseBook the business operation that replaces the details
     */
    public UpdateBookOperation(ReviseBook reviseBook) {
        this.reviseBook = reviseBook;
    }

    @Override
    public Book execute(UpdateBookRequest request) {
        BookRevision revision = new BookRevision(request.id(), request.isbn(), request.title(), request.author(),
                request.year(), request.version());
        return BookMapping.toBook(reviseBook.execute(revision));
    }
}
