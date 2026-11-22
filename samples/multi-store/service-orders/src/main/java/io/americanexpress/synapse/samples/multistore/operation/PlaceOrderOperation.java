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

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.samples.multistore.data.ledger.Ledger;
import io.americanexpress.synapse.samples.multistore.data.orders.OrderRepository;
import io.americanexpress.synapse.samples.multistore.data.orders.StoredOrder;
import io.americanexpress.synapse.samples.multistore.model.Order;
import io.americanexpress.synapse.samples.multistore.model.PlaceOrderRequest;

/**
 * Places an order: the order in the orders store, then its entry in the ledger store. The pipeline validates the
 * request before this runs.
 */
@Component
public class PlaceOrderOperation implements CommandOperation<PlaceOrderRequest, Order> {

    private final OrderRepository orders;

    private final Ledger ledger;

    /**
     * Creates the operation.
     *
     * @param orders the orders store's repository
     * @param ledger the ledger store's writer
     */
    public PlaceOrderOperation(OrderRepository orders, Ledger ledger) {
        this.orders = orders;
        this.ledger = ledger;
    }

    /**
     * Places the order, in a transaction of the orders store. With two stores and neither primary, the
     * {@code @Transactional} names its store.
     */
    @Override
    @Transactional("orders")
    public Order execute(PlaceOrderRequest request) {
        // The flush sends the INSERT now, so a failure of the order (a constraint, a lost connection) happens before
        // anything is written to the ledger.
        StoredOrder order = orders.saveAndFlush(new StoredOrder(request.customer(), request.amount()));
        // The cross-store write is not atomic. The ledger entry is written in a transaction of the ledger store, which
        // commits when record returns; the order commits after this method returns. If that commit fails, the ledger
        // keeps an entry for an order that doesn't exist. There is no distributed transaction between two stores:
        // a service that must not lose either write reconciles them, or records the second one from an outbox.
        ledger.record(Objects.requireNonNull(order.getId(), "id"), Objects.requireNonNull(order.getAmount(), "amount"));
        return OrderMapping.toOrder(order);
    }
}
