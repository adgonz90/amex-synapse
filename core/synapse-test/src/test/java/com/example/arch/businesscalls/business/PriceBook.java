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
package com.example.arch.businesscalls.business;

import com.example.arch.businesscalls.business.model.Price;
import com.example.arch.businesscalls.business.model.PriceInput;
import org.springframework.stereotype.Component;

import io.americanexpress.synapse.core.operation.BusinessOperation;

/**
 * The business contract that operations call. It uses business beans that don't implement BusinessOperation.
 */
@Component
public class PriceBook implements BusinessOperation<PriceInput, Price> {

    private final DiscountStrategy discounts;

    private final IsbnRules rules;

    private final BookMapper mapper;

    private final CatalogService catalog;

    public PriceBook(DiscountStrategy discounts, IsbnRules rules, BookMapper mapper, CatalogService catalog) {
        this.discounts = discounts;
        this.rules = rules;
        this.mapper = mapper;
        this.catalog = catalog;
    }

    @Override
    public Price execute(PriceInput input) {
        rules.check(input.isbn());
        return mapper.toPrice(input.isbn(), discounts.apply(catalog.listPrice(input.isbn())));
    }
}
