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
package io.americanexpress.synapse.samples.inventory.operation;

import java.util.Optional;

import org.springframework.stereotype.Component;

import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.samples.inventory.contract.GetItemRequest;
import io.americanexpress.synapse.samples.inventory.contract.Item;
import io.americanexpress.synapse.samples.inventory.data.InMemoryItems;

/**
 * Reads one item. An empty result is not an error here: the gRPC binding answers it with {@code NOT_FOUND} and the
 * reason {@code ITEM_NOT_FOUND} ({@code .notFound(...)}).
 */
@Component
public class GetItemOperation implements QueryOperation<GetItemRequest, Optional<Item>> {

    private final InMemoryItems items;

    /**
     * Creates the operation.
     *
     * @param items the inventory
     */
    public GetItemOperation(InMemoryItems items) {
        this.items = items;
    }

    @Override
    public Optional<Item> execute(GetItemRequest request) {
        return items.find(request.id());
    }
}
