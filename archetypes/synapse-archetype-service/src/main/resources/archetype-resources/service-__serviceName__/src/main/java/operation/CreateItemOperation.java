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

import org.springframework.stereotype.Component;

import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.operation.CommandOperation;

import ${package}.contract.CreateItemRequest;
import ${package}.contract.Item;
import ${package}.data.${serviceName}.ItemRepository;
import ${package}.data.${serviceName}.StoredItem;

/**
 * Adds an item, at version 0. The pipeline validates the request before this runs. One repository call needs no
 * {@code @Transactional}: the repository runs it in a transaction of its store.
 */
@Component
public class CreateItemOperation implements CommandOperation<CreateItemRequest, Item> {

    private final ItemRepository items;

    /**
     * Creates the operation.
     *
     * @param items the ${serviceName} store's repository
     */
    public CreateItemOperation(ItemRepository items) {
        this.items = items;
    }

    /**
     * Saves the item, and flushes, so the INSERT runs now: the answer has the id and version the store gave it.
     *
     * @throws SynapseException {@link SynapseErrors#ALREADY_EXISTS} when another item has the name: the table's unique
     *         constraint fails the INSERT, and Synapse's data translator names it, also when two requests race
     */
    @Override
    public Item execute(CreateItemRequest request) {
        return ItemMapping.toItem(items.saveAndFlush(new StoredItem(request.name(), request.description())));
    }
}
