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
package com.example.arch.bad.businesscalls.business;

import org.springframework.stereotype.Component;

import io.americanexpress.synapse.core.operation.BusinessOperation;

/**
 * A BusinessOperation with other public methods, which Synapse doesn't observe: one named execute.
 */
@Component
public class PriceBook implements BusinessOperation<String, Long> {

    @Override
    public Long execute(String isbn) {
        return quote(isbn);
    }

    public long execute(String isbn, int copies) {
        return quote(isbn) * copies;
    }

    public long quote(String isbn) {
        return isbn.length();
    }
}
