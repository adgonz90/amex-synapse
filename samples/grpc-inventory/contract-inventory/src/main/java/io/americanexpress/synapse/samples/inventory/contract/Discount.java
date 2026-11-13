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
package io.americanexpress.synapse.samples.inventory.contract;

import java.math.BigDecimal;
import java.math.RoundingMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * A discounted price: a list price and a percentage off it.
 *
 * @param listPrice the price before the discount
 * @param percentOff the discount, 1 to 99 percent
 */
public record Discount(@Valid Money listPrice, @Min(1) @Max(99) int percentOff) implements Price {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override
    public Money amountDue() {
        BigDecimal due = listPrice
                .amount()
                .multiply(HUNDRED.subtract(BigDecimal.valueOf(percentOff)))
                .divide(HUNDRED, listPrice.amount().scale(), RoundingMode.HALF_EVEN);
        return new Money(listPrice.currencyCode(), due);
    }
}
