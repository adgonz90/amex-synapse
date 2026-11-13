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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

/**
 * Adds an item to the inventory. The pipeline checks the constraints before the operation runs; over gRPC a violation
 * names the proto field ({@code sku_code}, {@code location.warehouse}).
 *
 * @param sku the stock-keeping unit (the field {@code sku_code})
 * @param tags labels for searching
 * @param attributes free-form properties
 * @param location where the item is stored; over gRPC an unset {@code location} is {@code must not be null}
 * @param quantity the units in stock to start with
 * @param leadTime how long a reorder takes to arrive, if known
 * @param reorderLevel reorder when the quantity falls to this level, if any
 * @param fixedPrice the price, when it is fixed (the {@code oneof} member {@code fixed})
 * @param discountedPrice the price, when it is discounted (the member {@code discounted})
 * @param channel the channel the request came through. The gRPC binding fills it from the request context (the
 *        {@code x-channel} metadata); the message has no field for it, so a caller can't set it in the request
 * @throws IllegalArgumentException when both prices are set, which a gRPC request can't do (the {@code oneof} holds
 *         one) and another protocol's could
 */
public record CreateItemRequest(@NotBlank @Size(max = 32) String sku, List<String> tags,
        Map<String, String> attributes, @Valid Location location, @PositiveOrZero int quantity,
        @Nullable Duration leadTime, @Nullable @PositiveOrZero Integer reorderLevel, @Nullable @Valid Money fixedPrice,
        @Nullable @Valid Discount discountedPrice, @Nullable String channel) {

    /** Copies the collections, and keeps at most one price. */
    public CreateItemRequest {
        tags = List.copyOf(tags);
        attributes = Map.copyOf(attributes);
        if (fixedPrice != null && discountedPrice != null) {
            throw new IllegalArgumentException("An item has a fixed or a discounted price, not both");
        }
    }
}
