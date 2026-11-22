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
package com.example.arch.good.operation;

import java.util.Optional;

import com.example.arch.good.business.BookLookup;
import com.example.arch.good.contract.model.Book;
import com.example.arch.good.model.BookView;
import com.example.arch.good.model.GetBookRequest;
import org.springframework.stereotype.Component;

import io.americanexpress.synapse.core.operation.QueryOperation;

@Component
public class GetBookOperation implements QueryOperation<GetBookRequest, Optional<BookView>> {

    private final BookLookup lookup;

    public GetBookOperation(BookLookup lookup) {
        this.lookup = lookup;
    }

    @Override
    public Optional<BookView> execute(GetBookRequest input) {
        Book book = lookup.execute(input.isbn());
        return Optional.of(new BookView(book.isbn(), book.title()));
    }
}
