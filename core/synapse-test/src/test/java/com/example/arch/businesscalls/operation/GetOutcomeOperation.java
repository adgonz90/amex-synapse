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

import com.example.arch.businesscalls.business.Audited;
import com.example.arch.businesscalls.business.NotPriced;
import com.example.arch.businesscalls.business.OutcomeBook;
import com.example.arch.businesscalls.business.PriceOutcome;
import com.example.arch.businesscalls.business.Priced;
import com.example.arch.businesscalls.business.PricingException;
import com.example.arch.businesscalls.business.QuoteLookup;
import com.example.arch.businesscalls.business.model.Discount;
import com.example.arch.businesscalls.business.model.Money;
import com.example.arch.businesscalls.business.model.Price;
import com.example.arch.businesscalls.business.model.PriceInput;
import org.springframework.stereotype.Component;

/**
 * Uses a sealed business result, a business annotation, a business exception, business model types (an interface and
 * a class) and a BusinessOperation interface that doesn't redeclare execute.
 */
@Audited
@Component
public class GetOutcomeOperation {

    private final OutcomeBook outcomes;

    private final QuoteLookup quotes;

    public GetOutcomeOperation(OutcomeBook outcomes, QuoteLookup quotes) {
        this.outcomes = outcomes;
        this.quotes = quotes;
    }

    public Money price(String isbn, Discount discount) {
        PriceOutcome outcome = outcome(isbn);
        if (outcome instanceof NotPriced notPriced) {
            throw new PricingException(notPriced.reason());
        }
        Price quote = quotes.execute(new PriceInput(isbn));
        long amount = ((Priced) outcome).amount() + quote.amount();
        return new Money(amount - amount * discount.percent() / 100);
    }

    public PriceOutcome outcome(String isbn) {
        return outcomes.execute(new PriceInput(isbn));
    }
}
