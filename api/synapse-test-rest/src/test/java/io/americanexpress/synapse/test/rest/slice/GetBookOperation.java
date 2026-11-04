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
package io.americanexpress.synapse.test.rest.slice;

import java.util.Optional;

import org.springframework.stereotype.Component;

import io.americanexpress.synapse.core.operation.QueryOperation;

/** Reads a book. The slice leaves this class out: the tests mock it. */
@Component
class GetBookOperation implements QueryOperation<BookId, Optional<Book>> {

    @Override
    public Optional<Book> execute(BookId input) {
        return Optional.of(new Book(input.id(), "the real operation ran"));
    }
}
