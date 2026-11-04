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
package com.acme.data.orders.operation;

import com.acme.data.orders.business.OrderPricing;
import com.acme.data.orders.data.OrderRepository;
import com.acme.data.orders.model.GetOrderRequest;
import com.acme.data.orders.model.Order;

import io.americanexpress.synapse.core.operation.QueryOperation;

/**
 * In the operation layer of {@code com.acme.data.orders}, not in a data layer of {@code com.acme}.
 */
public class GetOrderOperation implements QueryOperation<GetOrderRequest, Order> {

    private final OrderPricing pricing;

    private final OrderRepository orders;

    public GetOrderOperation(OrderPricing pricing, OrderRepository orders) {
        this.pricing = pricing;
        this.orders = orders;
    }

    @Override
    public Order execute(GetOrderRequest input) {
        return new Order(orders.find(input.id()), pricing.execute(input.id()));
    }
}
