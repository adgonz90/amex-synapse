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

/**
 * An item's price: one of the members of {@code oneof price}. Synapse maps a {@code oneof} member by member, so a
 * record carries each one as its own {@code @Nullable} component ({@link Item#fixedPrice()},
 * {@link Item#discountedPrice()}), and {@link Item#price()} returns the one that is set as this type, for code that
 * handles both with a {@code switch}.
 */
public sealed interface Price permits Money, Discount {

    /**
     * Returns what a buyer pays.
     *
     * @return the amount
     */
    Money amountDue();
}
