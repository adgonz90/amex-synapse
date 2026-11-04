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

import java.util.function.Supplier;

import com.example.arch.bad.businesscalls.business.DiscountCalculator;
import com.example.arch.bad.businesscalls.business.PriceBook;
import com.example.arch.bad.businesscalls.business.PriceMath;

/**
 * Reaches business code around the pipeline: constructs a BusinessOperation and a business class, calls a static
 * business method, and calls an execute overload.
 */
public class ConstructingOperation {

    private final PriceBook prices;

    public ConstructingOperation(PriceBook prices) {
        this.prices = prices;
    }

    public long constructed(String isbn) {
        return new PriceBook().execute(isbn);
    }

    public Supplier<PriceBook> factory() {
        return PriceBook::new;
    }

    public long helper(long amount) {
        return new DiscountCalculator().apply(amount);
    }

    public long rounded(long amount) {
        return PriceMath.round(amount);
    }

    public long overload(String isbn) {
        return prices.execute(isbn, 2);
    }
}
