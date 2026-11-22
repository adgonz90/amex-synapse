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
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;

/**
 * An amount of money, and a fixed price. The proto's {@code string amount} maps to a {@link BigDecimal} through Boot's
 * conversion ({@code "19.99"}); a string that isn't a number is {@code INVALID_ARGUMENT}.
 *
 * @param currencyCode the ISO 4217 code, such as {@code USD}
 * @param amount the amount, not negative
 */
public record Money(@Pattern(regexp = "[A-Z]{3}") String currencyCode, @DecimalMin("0") BigDecimal amount)
        implements
            Price {

    @Override
    public Money amountDue() {
        return this;
    }
}
