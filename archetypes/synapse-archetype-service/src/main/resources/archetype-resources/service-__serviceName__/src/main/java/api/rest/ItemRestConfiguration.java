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
package ${package}.api.rest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.americanexpress.synapse.api.rest.RestBindings;

import ${package}.contract.Item;
import ${package}.contract.ItemErrors;
import ${package}.operation.CreateItemOperation;
import ${package}.operation.GetItemOperation;

/**
 * The REST API of the items. This bean is all the REST configuration they have: Synapse turns it into Spring MVC
 * routes, binds each request to the operation's input record, runs the operation through the pipeline and maps the
 * output (or the error) to the response. {@code ItemGrpcConfiguration} binds the same operations over gRPC, with the
 * same declarations where gRPC has them, so the two APIs take the same requests and give the same answers.
 *
 * <p>
 * With {@code synapse-api-rest-openapi} on the class path, the same bean is the API's documentation: every route
 * below, with its parameters, schemas, statuses and problem responses, is in the OpenAPI document at
 * {@code /v3/api-docs}, which {@code application-local.yaml} turns on (it is off by default).
 */
@Configuration(proxyBeanMethods = false)
public class ItemRestConfiguration {

    // The bean is named after the method, so each aggregate's REST and gRPC bindings need names of their own.
    @Bean
    RestBindings itemRestBindings(CreateItemOperation createItem, GetItemOperation getItem) {
        return RestBindings
                .builder()
                // The JSON body fills CreateItemRequest. 201 with a Location header, and the new item's ETag ("0"). A
                // name another item has is 409 ALREADY_EXISTS.
                .post("/items", createItem)
                .created(item -> "/items/" + item.id())
                .etag(Item::version)
                // The path variable {id} fills GetItemRequest.id. An empty Optional is a 404 with ITEM_NOT_FOUND. The
                // item's version is its ETag, and a GET whose If-None-Match matches it is answered 304 (HTTP caching,
                // which gRPC doesn't have).
                .get("/items/{id}", getItem)
                .notFound(ItemErrors.ITEM_NOT_FOUND)
                .etag(Item::version)
                .build();
    }
}
