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
package io.americanexpress.synapse.samples.inventory.operation;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.samples.inventory.contract.CreateItemRequest;
import io.americanexpress.synapse.samples.inventory.contract.Item;
import io.americanexpress.synapse.samples.inventory.contract.ItemStatus;
import io.americanexpress.synapse.samples.inventory.data.InMemoryItems;

/**
 * Adds an item, active and at version 1. Stock it starts with counts as a restock at the current time, which comes
 * from the application's {@link Clock} (a {@code MutableClock} in tests).
 */
@Component
public class CreateItemOperation implements CommandOperation<CreateItemRequest, Item> {

    private final InMemoryItems items;

    private final Clock clock;

    /**
     * Creates the operation.
     *
     * @param items the inventory
     * @param clock the time
     */
    public CreateItemOperation(InMemoryItems items, Clock clock) {
        this.items = items;
        this.clock = clock;
    }

    @Override
    public Item execute(CreateItemRequest request) {
        @Nullable
        Instant restockedAt = request.quantity() > 0 ? clock.instant() : null;
        Item item = new Item(UUID.randomUUID(), request.sku(), ItemStatus.ACTIVE, request.tags(), request.attributes(),
                request.location(), request.quantity(), restockedAt, request.leadTime(), request.reorderLevel(),
                request.fixedPrice(), request.discountedPrice(), request.channel(), 1);
        items.insert(item);
        return item;
    }
}
