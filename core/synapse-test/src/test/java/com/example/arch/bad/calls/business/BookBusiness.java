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
package com.example.arch.bad.calls.business;

import com.example.arch.bad.calls.operation.GetBookOperation;
import org.springframework.stereotype.Service;

import io.americanexpress.synapse.core.operation.BusinessOperation;

@Service
public class BookBusiness implements BusinessOperation<String, String> {

    private final GetBookOperation operation;

    public BookBusiness(GetBookOperation operation) {
        this.operation = operation;
    }

    @Override
    public String execute(String isbn) {
        return operation.title(isbn);
    }
}
