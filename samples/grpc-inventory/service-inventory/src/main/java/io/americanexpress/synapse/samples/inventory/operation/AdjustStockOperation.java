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

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.samples.inventory.contract.AdjustStockRequest;
import io.americanexpress.synapse.samples.inventory.contract.InventoryErrors;
import io.americanexpress.synapse.samples.inventory.contract.Item;
import io.americanexpress.synapse.samples.inventory.data.InMemoryItems;

/**
 * Adds or removes stock, in one atomic step with the read, so concurrent adjustments all count. Removing more than is
 * in stock is {@code OUT_OF_STOCK} and changes nothing; over gRPC it is {@code FAILED_PRECONDITION} for this operation
 * only (see {@code application.yaml}). Adding stock records the time as the restock time. Adding so much that the
 * quantity would pass {@code Integer.MAX_VALUE} is {@code VALIDATION_FAILED} on {@code delta}, and a delta of 0 changes
 * nothing, not even the version.
 */
@Component
public class AdjustStockOperation implements CommandOperation<AdjustStockRequest, Item> {

    /** The violation of a delta that would take the quantity past {@code Integer.MAX_VALUE}. */
    static final String DELTA_TOO_LARGE = "is too large for the quantity in stock";

    private final InMemoryItems items;

    private final Clock clock;

    /**
     * Creates the operation.
     *
     * @param items the inventory
     * @param clock the time
     */
    public AdjustStockOperation(InMemoryItems items, Clock clock) {
        this.items = items;
        this.clock = clock;
    }

    @Override
    public Item execute(AdjustStockRequest request) {
        return items.update(request.id(), current -> {
            if (request.delta() == 0) {
                // Nothing changes, so the item keeps its version.
                return current;
            }
            int quantity;
            try {
                quantity = Math.addExact(current.quantity(), request.delta());
            } catch (ArithmeticException e) {
                // Only adding can overflow (the quantity is never negative): the request asks for more than an int.
                throw SynapseException
                        .ofViolations(SynapseErrors.VALIDATION_FAILED,
                                List.of(new Violation("delta", DELTA_TOO_LARGE)));
            }
            if (quantity < 0) {
                throw new SynapseException(InventoryErrors.OUT_OF_STOCK, current.id(), current.quantity());
            }
            @Nullable
            Instant restockedAt = request.delta() > 0 ? clock.instant() : current.restockedAt();
            return current.withStock(quantity, restockedAt);
        }).orElseThrow(() -> new SynapseException(InventoryErrors.ITEM_NOT_FOUND, request.id()));
    }
}
