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
package io.americanexpress.synapse.samples.multistore.api.rest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.samples.multistore.contract.OrderErrors;
import io.americanexpress.synapse.samples.multistore.model.DeleteOrderRequest;
import io.americanexpress.synapse.samples.multistore.model.Order;
import io.americanexpress.synapse.samples.multistore.operation.CancelOrderOperation;
import io.americanexpress.synapse.samples.multistore.operation.GetOrderOperation;
import io.americanexpress.synapse.samples.multistore.operation.PlaceOrderOperation;

/**
 * The REST API of the orders service. This bean is all the REST configuration the service has: Synapse turns it into
 * Spring MVC routes, binds each request to the operation's input record, runs the operation through the pipeline and
 * maps the output (or the error) to the response. Nothing here knows which store an operation uses.
 */
@Configuration(proxyBeanMethods = false)
public class OrdersRestConfiguration {

    @Bean
    RestBindings orders(PlaceOrderOperation placeOrder, GetOrderOperation getOrder, CancelOrderOperation cancelOrder) {
        return RestBindings
                .builder()
                // The JSON body fills PlaceOrderRequest. 201 with a Location header, and the new order's ETag ("0"),
                // ready for the If-Match of a cancel.
                .post("/orders", placeOrder)
                .created(order -> "/orders/" + order.id())
                .etag(Order::version)
                // The path variable {id} fills GetOrderRequest.id. An empty Optional is a 404 with ORDER_NOT_FOUND.
                .get("/orders/{id}", getOrder)
                .notFound(OrderErrors.ORDER_NOT_FOUND)
                .etag(Order::version)
                // 204 with no body. If-Match fills DeleteOrderRequest.version, which isn't @Nullable, so it is
                // required: a DELETE without it is 428 PRECONDITION_REQUIRED. A version the order no longer has is
                // 412 PRECONDITION_FAILED.
                .delete("/orders/{id}", cancelOrder)
                .header("If-Match", DeleteOrderRequest::version)
                .build();
    }
}
