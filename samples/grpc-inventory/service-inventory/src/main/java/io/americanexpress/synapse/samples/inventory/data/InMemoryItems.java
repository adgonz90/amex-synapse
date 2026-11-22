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
package io.americanexpress.synapse.samples.inventory.data;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

import org.springframework.stereotype.Component;

import io.americanexpress.synapse.samples.inventory.contract.Discount;
import io.americanexpress.synapse.samples.inventory.contract.Item;
import io.americanexpress.synapse.samples.inventory.contract.ItemStatus;
import io.americanexpress.synapse.samples.inventory.contract.Location;
import io.americanexpress.synapse.samples.inventory.contract.Money;

/**
 * A stand-in for a database: the items live in memory and are lost when the service stops. It starts with three items
 * at version 1, so the API answers straight away: one with a fixed price, one discounted, and one discontinued without
 * a price.
 *
 * <p>
 * Every method is {@code synchronized}, so the store is thread-safe and {@link #update} is atomic, as a row lock or a
 * single {@code UPDATE} statement would make it. Synapse's data modules replace this with a Spring Data repository on
 * a named store.
 */
@Component
public class InMemoryItems {

    /** WID-001: a widget with a fixed price. */
    public static final UUID WIDGET = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** GAD-002: a discounted gadget. */
    public static final UUID GADGET = UUID.fromString("00000000-0000-0000-0000-000000000002");

    /** GIZ-003: a discontinued gizmo without a price. */
    public static final UUID GIZMO = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private final Map<UUID, Item> items = new LinkedHashMap<>();

    /** Creates the store with its three seed items. */
    public InMemoryItems() {
        insert(new Item(WIDGET, "WID-001", ItemStatus.ACTIVE, List.of("hardware"), Map.of("color", "blue"),
                new Location("east", "A1", 3), 40, Instant.parse("2026-11-01T09:00:00Z"), Duration.ofDays(3), 10,
                new Money("USD", new BigDecimal("19.99")), null, null, 1));
        insert(new Item(GADGET, "GAD-002", ItemStatus.ACTIVE, List.of("electronics", "gift"), Map.of(),
                new Location("east", "B4", 12), 5, null, null, null, null,
                new Discount(new Money("USD", new BigDecimal("49.00")), 20), null, 1));
        insert(new Item(GIZMO, "GIZ-003", ItemStatus.DISCONTINUED, List.of(), Map.of(), new Location("west", "C2", 7),
                0, null, null, null, null, null, null, 1));
    }

    /**
     * Finds an item.
     *
     * @param id the id
     * @return the item, or empty
     */
    public synchronized Optional<Item> find(UUID id) {
        return Optional.ofNullable(items.get(id));
    }

    /**
     * Returns every item, in the order they were added.
     *
     * @return a copy of the items
     */
    public synchronized List<Item> findAll() {
        return new ArrayList<>(items.values());
    }

    /**
     * Adds an item.
     *
     * @param item the item, with a new id
     * @throws IllegalArgumentException if an item has its id already
     */
    public synchronized void insert(Item item) {
        if (items.putIfAbsent(item.id(), item) != null) {
            throw new IllegalArgumentException("An item with this id exists already");
        }
    }

    /**
     * Replaces an item with what {@code change} makes of it, in one atomic step: no other change to the item runs
     * between the read and the write. An exception {@code change} throws leaves the item as it was.
     *
     * @param id the id
     * @param change the new state of the item, from its current state
     * @return the new state, or empty when no item has the id
     */
    public synchronized Optional<Item> update(UUID id, UnaryOperator<Item> change) {
        Item current = items.get(id);
        if (current == null) {
            return Optional.empty();
        }
        Item changed = change.apply(current);
        items.put(id, changed);
        return Optional.of(changed);
    }

    /**
     * Removes an item.
     *
     * @param id the id
     * @return whether an item had the id
     */
    public synchronized boolean remove(UUID id) {
        return items.remove(id) != null;
    }
}
