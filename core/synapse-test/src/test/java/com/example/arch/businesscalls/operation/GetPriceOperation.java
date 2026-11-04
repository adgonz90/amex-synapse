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
package com.example.arch.businesscalls.operation;

import com.example.arch.businesscalls.business.PriceBook;
import com.example.arch.businesscalls.business.PricingErrors;
import com.example.arch.businesscalls.business.model.Price;
import com.example.arch.businesscalls.business.model.PriceInput;
import com.example.arch.businesscalls.model.GetPriceRequest;
import com.example.arch.businesscalls.model.PriceView;
import org.springframework.stereotype.Component;

import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.operation.QueryOperation;

/**
 * Calls business code through its BusinessOperation, and uses a business model and a business error enum.
 */
@Component
public class GetPriceOperation implements QueryOperation<GetPriceRequest, PriceView> {

    private final PriceBook prices;

    public GetPriceOperation(PriceBook prices) {
        this.prices = prices;
    }

    @Override
    public PriceView execute(GetPriceRequest input) {
        if (input.isbn().isEmpty()) {
            throw new SynapseException(PricingErrors.UNKNOWN_BOOK, input.isbn());
        }
        Price price = prices.execute(new PriceInput(input.isbn()));
        return new PriceView(price.isbn(), price.amount());
    }
}
