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
package ${package}.operation;

import java.util.Optional;

import org.springframework.stereotype.Component;

import io.americanexpress.synapse.core.operation.QueryOperation;

import ${package}.contract.GetItemRequest;
import ${package}.contract.Item;
import ${package}.data.${serviceName}.ItemRepository;

/**
 * Reads one item. An empty result is not an error here: each protocol's binding answers it with
 * {@code ITEM_NOT_FOUND} ({@code .notFound(...)}): REST 404, gRPC {@code NOT_FOUND}.
 */
@Component
public class GetItemOperation implements QueryOperation<GetItemRequest, Optional<Item>> {

    private final ItemRepository items;

    /**
     * Creates the operation.
     *
     * @param items the ${serviceName} store's repository
     */
    public GetItemOperation(ItemRepository items) {
        this.items = items;
    }

    @Override
    public Optional<Item> execute(GetItemRequest request) {
        return items.findById(request.id()).map(ItemMapping::toItem);
    }
}
