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
package io.americanexpress.synapse.samples.multistore.operation;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.samples.multistore.data.orders.StoredOrder;
import io.americanexpress.synapse.samples.multistore.model.Order;

/** Maps the orders store's entity to the API's record. */
final class OrderMapping {

    private OrderMapping() {
    }

    /**
     * Maps a saved order. Its id, version and audit stamps are set once it is saved; the getters are nullable only
     * because a new entity has none yet.
     *
     * @param order a saved order
     * @return the record
     */
    static Order toOrder(StoredOrder order) {
        return new Order(saved(order.getId(), "id"), saved(order.getCustomer(), "customer"),
                saved(order.getAmount(), "amount"), saved(order.getCreatedAt(), "createdAt"),
                saved(order.getCreatedBy(), "createdBy"), saved(order.getVersion(), "version"));
    }

    private static <T> T saved(@Nullable T value, String name) {
        return Objects.requireNonNull(value, () -> "A saved order has a " + name);
    }
}
