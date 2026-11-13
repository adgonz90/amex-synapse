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
package ${package}.api.grpc;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.americanexpress.synapse.api.grpc.GrpcBindings;

import ${package}.contract.Item;
import ${package}.contract.ItemErrors;
import ${package}.contract.proto.ItemServiceGrpc;
import ${package}.operation.CreateItemOperation;
import ${package}.operation.GetItemOperation;

/**
 * The gRPC API of the items: {@code ${package}.v1.ItemService}, generated in contract-${serviceName}. This bean is all
 * the gRPC configuration they have: Synapse turns it into a service that Spring Boot's server serves, maps each
 * request message to the operation's input record, runs the operation through the pipeline and maps the output (or
 * the error) to the response message (or the status).
 *
 * <p>
 * Each method is bound to the operation of the REST route of the same name in {@code ItemRestConfiguration}, with the
 * same declarations: the not-found code, and the entity tag on the read and the create. Every message field maps to
 * the record component with the same name, so no method needs a {@code .map}. Every method is bound explicitly, so
 * this class lists the whole API.
 */
@Configuration(proxyBeanMethods = false)
public class ItemGrpcConfiguration {

    // The bean is named after the method, so each aggregate's REST and gRPC bindings need names of their own.
    @Bean
    GrpcBindings itemGrpcBindings(CreateItemOperation createItem, GetItemOperation getItem) {
        return GrpcBindings
                .service(ItemServiceGrpc.getServiceDescriptor())
                // POST /items. The response carries the new item's etag ("0"), as REST's 201 carries its ETag. A name
                // another item has is ALREADY_EXISTS.
                .unary(ItemServiceGrpc.getCreateItemMethod(), createItem)
                .etag(Item::version)
                // GET /items/{id}. An empty Optional is NOT_FOUND with the reason ITEM_NOT_FOUND in ErrorInfo, and the
                // response carries the item's etag, as REST's ETag header.
                .unary(ItemServiceGrpc.getGetItemMethod(), getItem)
                .notFound(ItemErrors.ITEM_NOT_FOUND)
                .etag(Item::version)
                .build();
    }
}
