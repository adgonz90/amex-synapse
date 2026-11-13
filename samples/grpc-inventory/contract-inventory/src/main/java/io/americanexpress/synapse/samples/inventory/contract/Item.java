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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A stock item, as every method returns it. Each component maps to the {@code Item} message's field of the same name,
 * with the kind of mapping Synapse gives its type:
 * <ul>
 * <li>{@code id}: {@code string}, through Boot's conversion; {@code status}: the enum, by name;</li>
 * <li>{@code tags}: {@code repeated string}; {@code attributes}: {@code map<string, string>}; {@code location}: a
 * nested message;</li>
 * <li>{@code restockedAt}: {@code google.protobuf.Timestamp}; {@code leadTime}: {@code google.protobuf.Duration};
 * {@code reorderLevel}: {@code google.protobuf.Int32Value}, unset for {@code null};</li>
 * <li>{@code sku}: the field {@code sku_code}, and {@code fixedPrice} and {@code discountedPrice}: the members of
 * {@code oneof price}. These three have no field of their name, so the bindings map them with {@code .map(...)}.</li>
 * </ul>
 *
 * @param id the id
 * @param sku the stock-keeping unit
 * @param status whether the item is still sold
 * @param tags labels for searching
 * @param attributes free-form properties, such as {@code color}
 * @param location where the item is stored
 * @param quantity the units in stock, not negative
 * @param restockedAt when stock was last added, or {@code null} if it never was
 * @param leadTime how long a reorder takes to arrive, or {@code null} when not known
 * @param reorderLevel reorder when the quantity falls to this level, or {@code null} for none
 * @param fixedPrice the price, when the item has a fixed one
 * @param discountedPrice the price, when the item is discounted
 * @param createdVia the channel the item was created through, or {@code null} when the caller sent none
 * @param version 1 when the item is created, plus 1 on every change; the gRPC {@code etag} of {@code GetItem} and
 *        {@code CreateItem} carries it as an entity tag
 * @throws IllegalArgumentException when both prices are set: the {@code oneof} holds one, so such an item can't be
 *         written to a response
 */
public record Item(UUID id, String sku, ItemStatus status, List<String> tags, Map<String, String> attributes,
        Location location, int quantity, @Nullable Instant restockedAt, @Nullable Duration leadTime,
        @Nullable Integer reorderLevel, @Nullable Money fixedPrice, @Nullable Discount discountedPrice,
        @Nullable String createdVia, long version) {

    /** Copies the collections, and keeps at most one price. */
    public Item {
        tags = List.copyOf(tags);
        attributes = Map.copyOf(attributes);
        if (fixedPrice != null && discountedPrice != null) {
            throw new IllegalArgumentException("An item has a fixed or a discounted price, not both");
        }
    }

    /**
     * Returns the price that is set, whichever member of {@code oneof price} it is.
     *
     * @return the fixed or discounted price, or {@code null} when the item has none
     */
    public @Nullable Price price() {
        return fixedPrice != null ? fixedPrice : discountedPrice;
    }

    /**
     * Returns the next version of this item with another quantity.
     *
     * @param newQuantity the units in stock
     * @param newRestockedAt when stock was last added
     * @return the item at {@code version + 1}
     */
    public Item withStock(int newQuantity, @Nullable Instant newRestockedAt) {
        return new Item(id, sku, status, tags, attributes, location, newQuantity, newRestockedAt, leadTime,
                reorderLevel, fixedPrice, discountedPrice, createdVia, version + 1);
    }
}
