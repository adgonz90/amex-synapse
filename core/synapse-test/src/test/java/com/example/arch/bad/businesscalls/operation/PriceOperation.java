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
package com.example.arch.bad.businesscalls.operation;

import com.example.arch.bad.businesscalls.business.PriceBook;
import com.example.arch.bad.businesscalls.business.PricingErrors;
import com.example.arch.bad.businesscalls.business.PricingException;
import com.example.arch.bad.businesscalls.business.model.Money;
import com.example.arch.bad.businesscalls.business.model.Price;

/**
 * Follows the rule: calls execute on a BusinessOperation, creates business models, values and exceptions, and calls
 * record accessors, enum methods and exception methods.
 */
public class PriceOperation {

    private final PriceBook prices;

    public PriceOperation(PriceBook prices) {
        this.prices = prices;
    }

    public Price price(String isbn) {
        if (isbn.isEmpty()) {
            PricingException failure = new PricingException(PricingErrors.valueOf("UNKNOWN_BOOK").name());
            throw new IllegalArgumentException(failure.getMessage(), failure);
        }
        Price price = new Price(isbn, prices.execute(isbn));
        return new Price(price.isbn(), new Money(price.amount()).cents());
    }
}
