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

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.americanexpress.synapse.api.grpc.GrpcBindings;
import io.americanexpress.synapse.samples.inventory.contract.CreateItemRequest;
import io.americanexpress.synapse.samples.inventory.contract.InventoryErrors;
import io.americanexpress.synapse.samples.inventory.contract.Item;
import io.americanexpress.synapse.samples.inventory.contract.ListItemsRequest;
import io.americanexpress.synapse.samples.inventory.contract.proto.InventoryServiceGrpc;
import io.americanexpress.synapse.samples.inventory.operation.AdjustStockOperation;
import io.americanexpress.synapse.samples.inventory.operation.CreateItemOperation;
import io.americanexpress.synapse.samples.inventory.operation.DeleteItemOperation;
import io.americanexpress.synapse.samples.inventory.operation.GetItemOperation;
import io.americanexpress.synapse.samples.inventory.operation.ListItemsOperation;

/**
 * The gRPC API of the inventory: {@code synapse.samples.inventory.v1.InventoryService}, generated in
 * {@code contract-inventory}. This bean is all the gRPC configuration the service has: Synapse turns it into a service
 * that Spring Boot's server serves, maps each request message to the operation's input record, runs the operation
 * through the pipeline and maps the output (or the error) to the response message (or the status).
 *
 * <p>
 * Every message field maps to the record component with the same name ({@code restocked_at} to {@code restockedAt}),
 * whatever its kind: {@code Timestamp}, {@code Duration}, wrapper, enum, repeated, map or nested message. Only three
 * components of {@code Item} need a {@code .map(...)}: {@code sku}, whose field is {@code sku_code}, and the two
 * members of {@code oneof price}, which Synapse never maps by name. A {@code .map} belongs to one method, so each
 * method that writes an {@code Item} declares them.
 */
@Configuration(proxyBeanMethods = false)
public class InventoryGrpcConfiguration {

    @Bean
    GrpcBindings inventory(GetItemOperation getItem, CreateItemOperation createItem, ListItemsOperation listItems,
            AdjustStockOperation adjustStock, DeleteItemOperation deleteItem) {
        return GrpcBindings
                .service(InventoryServiceGrpc.getServiceDescriptor())
                // An empty Optional is NOT_FOUND with the reason ITEM_NOT_FOUND in ErrorInfo. .etag writes the
                // item's version to Item.etag as the strong entity tag "1", as REST's ETag header would carry it.
                .unary(InventoryServiceGrpc.getGetItemMethod(), getItem)
                .map("sku_code", Item::sku)
                .map("fixed", Item::fixedPrice)
                .map("discounted", Item::discountedPrice)
                .notFound(InventoryErrors.ITEM_NOT_FOUND)
                .etag(Item::version)
                // The request's sku_code and price members fill CreateItemRequest. channel comes from the request
                // context instead (the x-channel metadata, see synapse.api.grpc.context.metadata in
                // application.yaml): the message has no field for it, so a caller can't set it. A create carries the
                // new item's etag, as REST's 201 carries its ETag.
                .unary(InventoryServiceGrpc.getCreateItemMethod(), createItem)
                .map("sku_code", CreateItemRequest::sku)
                .map("fixed", CreateItemRequest::fixedPrice)
                .map("discounted", CreateItemRequest::discountedPrice)
                .context(CreateItemRequest::channel, context -> context.attribute("channel").orElse(null))
                .map("sku_code", Item::sku)
                .map("fixed", Item::fixedPrice)
                .map("discounted", Item::discountedPrice)
                .etag(Item::version)
                // ListItemsRequest.page reads the AIP-158 fields page_size, page_token and order_by; order_by may
                // name the SortKey constants in lower case. The page's items go to the response's repeated items,
                // and Synapse writes next_page_token, has_next and total_size.
                .unary(InventoryServiceGrpc.getListItemsMethod(), listItems)
                .sortable(ListItemsRequest.SortKey.class)
                .map("sku_code", Item::sku)
                .map("fixed", Item::fixedPrice)
                .map("discounted", Item::discountedPrice)
                // No .etag: an entity tag goes on reads and creates. Synapse would allow one here (the input has no
                // VersionCondition, the shape of a create), but an adjustment changes an item, so a caller reads the
                // item again for its new etag, as after an update.
                .unary(InventoryServiceGrpc.getAdjustStockMethod(), adjustStock)
                .map("sku_code", Item::sku)
                .map("fixed", Item::fixedPrice)
                .map("discounted", Item::discountedPrice)
                // The operation returns nothing: the response is google.protobuf.Empty.
                .unary(InventoryServiceGrpc.getDeleteItemMethod(), deleteItem)
                .build();
    }
}
