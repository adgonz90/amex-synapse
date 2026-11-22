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

import org.springframework.stereotype.Component;

import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.samples.inventory.contract.DeleteItemRequest;
import io.americanexpress.synapse.samples.inventory.contract.InventoryErrors;
import io.americanexpress.synapse.samples.inventory.data.InMemoryItems;

/** Removes an item. An unknown id is {@code ITEM_NOT_FOUND}. Over gRPC the answer is {@code google.protobuf.Empty}. */
@Component
public class DeleteItemOperation implements CommandOperation<DeleteItemRequest, Void> {

    private final InMemoryItems items;

    /**
     * Creates the operation.
     *
     * @param items the inventory
     */
    public DeleteItemOperation(InMemoryItems items) {
        this.items = items;
    }

    @Override
    @SuppressWarnings("NullAway") // A Void output is null.
    public Void execute(DeleteItemRequest request) {
        if (!items.remove(request.id())) {
            throw new SynapseException(InventoryErrors.ITEM_NOT_FOUND, request.id());
        }
        return null;
    }
}
