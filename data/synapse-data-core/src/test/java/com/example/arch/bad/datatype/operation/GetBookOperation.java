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
package com.example.arch.bad.datatype.operation;

import java.util.UUID;

import com.example.arch.bad.datatype.data.BookRecord;
import com.example.arch.bad.datatype.model.GetBookRequest;

import io.americanexpress.synapse.core.operation.QueryOperation;

public class GetBookOperation implements QueryOperation<GetBookRequest, BookRecord> {

    @Override
    public BookRecord execute(GetBookRequest input) {
        return new BookRecord(new UUID(0L, 0L), input.isbn());
    }
}
