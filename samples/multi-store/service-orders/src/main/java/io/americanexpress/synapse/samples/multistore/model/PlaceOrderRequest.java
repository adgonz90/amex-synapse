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
package io.americanexpress.synapse.samples.multistore.model;

import java.math.BigDecimal;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Places an order. The pipeline checks the constraints before the operation runs; they match the columns of the
 * {@code orders} table, so a valid request always fits.
 *
 * @param customer who places the order
 * @param amount the order's amount: positive, with at most 10 digits before the point and 2 after
 */
public record PlaceOrderRequest(@NotBlank @Size(max = 255) String customer,
        @Positive @Digits(integer = 10, fraction = 2) BigDecimal amount) {
}
