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
package io.americanexpress.synapse.samples.multistore.operation;

import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.samples.multistore.contract.OrderErrors;
import io.americanexpress.synapse.samples.multistore.data.orders.OrderRepository;
import io.americanexpress.synapse.samples.multistore.data.orders.StoredOrder;
import io.americanexpress.synapse.samples.multistore.model.DeleteOrderRequest;

/**
 * Cancels an order, only at the version the caller expects. It deletes the order from the orders store; the ledger
 * keeps the entry of its placement. It has no output ({@code Void}).
 */
@Component
public class CancelOrderOperation implements CommandOperation<DeleteOrderRequest, Void> {

    private final OrderRepository orders;

    /**
     * Creates the operation.
     *
     * @param orders the orders store's repository
     */
    public CancelOrderOperation(OrderRepository orders) {
        this.orders = orders;
    }

    /**
     * Deletes the order, in a transaction of the orders store, so the read and the delete see the same row.
     *
     * @throws SynapseException {@link OrderErrors#ORDER_NOT_FOUND} when no order has the id;
     *         {@link SynapseErrors#STALE_VERSION} when the order isn't at the expected version. The request always
     *         carries a version, so REST answers that with 412 {@code PRECONDITION_FAILED}. A concurrent change between
     *         the read and the delete fails the delete itself (JPA's {@code @Version}), which is {@code STALE_VERSION}
     *         too.
     */
    @Override
    @Transactional("orders")
    // A Void operation returns null, and the output type of ServiceOperation has no @Nullable bound, so NullAway has to
    // be told.
    @SuppressWarnings("NullAway")
    public Void execute(DeleteOrderRequest request) {
        StoredOrder order = orders
                .findById(request.id())
                .orElseThrow(() -> new SynapseException(OrderErrors.ORDER_NOT_FOUND, request.id()));
        if (!request.version().matches(Objects.requireNonNull(order.getVersion(), "version"))) {
            throw new SynapseException(SynapseErrors.STALE_VERSION);
        }
        orders.delete(order);
        return null;
    }
}
