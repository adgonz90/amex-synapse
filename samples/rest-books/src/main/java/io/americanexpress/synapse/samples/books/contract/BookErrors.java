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
package io.americanexpress.synapse.samples.books.contract;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.ErrorDetails;

/**
 * The error codes of the catalog. Callers may match them, so they are part of the service's contract: the REST
 * bindings ({@code .notFound}), the operations and the business code all use them. In a multi-module service this
 * enum lives in the {@code contract-books} module.
 */
public enum BookErrors implements ErrorDetails {

    /** No book has the id. Argument: the id. */
    BOOK_NOT_FOUND("Book {0} was not found", ErrorCategory.NOT_FOUND),

    /** Another book already has the ISBN. Argument: the ISBN. */
    DUPLICATE_ISBN("A book with ISBN {0} already exists", ErrorCategory.CONFLICT);

    private final String messageTemplate;

    private final ErrorCategory category;

    BookErrors(String messageTemplate, ErrorCategory category) {
        this.messageTemplate = messageTemplate;
        this.category = category;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public String messageTemplate() {
        return messageTemplate;
    }

    @Override
    public ErrorCategory category() {
        return category;
    }
}
