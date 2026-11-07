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
import io.americanexpress.synapse.samples.books.business.RemoveBook;
import io.americanexpress.synapse.samples.books.model.DeleteBookRequest;

/** Removes a book. It has no output ({@code Void}). */
@Component
public class DeleteBookOperation implements CommandOperation<DeleteBookRequest, Void> {

    private final RemoveBook removeBook;

    /**
     * Creates the operation.
     *
     * @param removeBook the business operation that removes the book
     */
    public DeleteBookOperation(RemoveBook removeBook) {
        this.removeBook = removeBook;
    }

    @Override
    // A Void operation returns null, and the output type of ServiceOperation has no @Nullable bound, so NullAway has to
    // be told.
    @SuppressWarnings("NullAway")
    public Void execute(DeleteBookRequest request) {
        removeBook.execute(BookMapping.toRemoval(request));
        return null;
    }
}
