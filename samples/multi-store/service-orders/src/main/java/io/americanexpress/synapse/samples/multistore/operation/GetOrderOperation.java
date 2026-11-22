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

import java.util.Optional;

import org.springframework.stereotype.Component;

import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.samples.multistore.data.orders.OrderRepository;
import io.americanexpress.synapse.samples.multistore.model.GetOrderRequest;
import io.americanexpress.synapse.samples.multistore.model.Order;

/**
 * Reads one order. An empty result is not an error here: the REST binding answers it with a 404 and the code
 * {@code ORDER_NOT_FOUND}. One repository call needs no {@code @Transactional}: the repository runs it in a transaction
 * of its own store, the orders store.
 */
@Component
public class GetOrderOperation implements QueryOperation<GetOrderRequest, Optional<Order>> {

    private final OrderRepository orders;

    /**
     * Creates the operation.
     *
     * @param orders the orders store's repository
     */
    public GetOrderOperation(OrderRepository orders) {
        this.orders = orders;
    }

    @Override
    public Optional<Order> execute(GetOrderRequest request) {
        return orders.findById(request.id()).map(OrderMapping::toOrder);
    }
}
