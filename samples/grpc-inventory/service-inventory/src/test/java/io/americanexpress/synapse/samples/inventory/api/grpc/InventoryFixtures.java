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
package io.americanexpress.synapse.samples.inventory.api.grpc;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.americanexpress.synapse.core.paging.PageResult;
import io.americanexpress.synapse.samples.inventory.contract.Discount;
import io.americanexpress.synapse.samples.inventory.contract.Item;
import io.americanexpress.synapse.samples.inventory.contract.ItemStatus;
import io.americanexpress.synapse.samples.inventory.contract.Location;
import io.americanexpress.synapse.samples.inventory.contract.Money;

/**
 * The contract records that {@link InventoryGrpcTest}'s mocked operations return. The contract's records and the
 * generated messages share their names ({@code Item}, {@code Location}), so the records are built here and the test
 * imports the messages.
 */
final class InventoryFixtures {

    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    /** With nanoseconds, which a {@code Timestamp} keeps. */
    static final Instant RESTOCKED = Instant.parse("2026-11-01T09:00:00.123456789Z");

    private InventoryFixtures() {
    }

    /** An item with every field set, and a fixed price of USD 19.99. */
    static Item widget() {
        return new Item(ID, "WID-001", ItemStatus.ACTIVE, List.of("hardware", "blue"), Map.of("color", "blue"),
                new Location("east", "A1", 3), 40, RESTOCKED, Duration.ofHours(72).plusMillis(500), 10,
                new Money("USD", new BigDecimal("19.99")), null, "web", 1);
    }

    /** An item with every nullable component null, and so no price. */
    static Item bare() {
        return new Item(ID, "GIZ-003", ItemStatus.DISCONTINUED, List.of(), Map.of(), new Location("west", "C2", 7), 0,
                null, null, null, null, null, null, 3);
    }

    /** An item at 20% off USD 49.00. */
    static Item discounted() {
        return new Item(ID, "GAD-002", ItemStatus.ACTIVE, List.of(), Map.of(), new Location("east", "B4", 12), 5,
                null, null, null, null, new Discount(new Money("USD", new BigDecimal("49.00")), 20), null, 2);
    }

    /** An item with another SKU, for pages. */
    static Item numbered(int n) {
        return new Item(new UUID(0, n), "SKU-" + n, ItemStatus.ACTIVE, List.of(), Map.of(),
                new Location("east", "A1", 1),
                n, null, null, null, null, null, null, 1);
    }

    /** A page by offset only, as {@code ListItemsOperation} returns it: the edge turns it into a page token. */
    static PageResult<Item> page(List<Item> items, boolean hasNext, long total) {
        return PageResult.of(items, hasNext).withTotal(total);
    }
}
