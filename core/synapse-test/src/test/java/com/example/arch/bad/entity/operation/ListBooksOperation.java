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
package com.example.arch.bad.entity.operation;

import java.util.List;

import com.example.arch.bad.entity.data.BookRecord;
import com.example.arch.bad.entity.model.ListBooksRequest;

/**
 * The output type comes through a generic base class: {@code PageResult<BookRecord>}.
 */
public class ListBooksOperation extends AbstractPagedOperation<ListBooksRequest, BookRecord> {

    @Override
    protected List<BookRecord> items(ListBooksRequest input) {
        return List.of(new BookRecord("isbn-" + input.size()));
    }
}
