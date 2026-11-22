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
package io.americanexpress.synapse.samples.multistore.data.orders;

import java.math.BigDecimal;
import java.math.RoundingMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.data.jpa.AuditedEntity;

/**
 * An order as the orders store keeps it (table {@code orders}; {@code order} is a reserved word in SQL).
 * {@link AuditedEntity} gives it the rest: a UUIDv7 id assigned when it is first saved, a {@code version} for
 * optimistic locking (0 when it is placed), and the audit columns {@code created_at}, {@code created_by},
 * {@code modified_at} and {@code modified_by}, which Synapse stamps on every save.
 *
 * <p>
 * The entity never leaves the service's data layer: the operations map it to the API's {@code Order} record.
 */
@Entity
@Table(name = "orders")
public class StoredOrder extends AuditedEntity {

    @Column(name = "customer", nullable = false)
    private @Nullable String customer;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private @Nullable BigDecimal amount;

    /** For JPA. */
    protected StoredOrder() {
    }

    /**
     * Creates an order that is not saved yet.
     *
     * @param customer who placed the order
     * @param amount the order's amount, with at most 2 decimals. It is kept with exactly 2 (12.5 is 12.50), as the
     *        {@code numeric(12, 2)} column holds it, so the order reads the same before and after it is saved
     * @throws ArithmeticException when the amount has more than 2 decimals
     */
    public StoredOrder(String customer, BigDecimal amount) {
        this.customer = customer;
        this.amount = amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    /**
     * Returns who placed the order.
     *
     * @return the customer
     */
    public @Nullable String getCustomer() {
        return customer;
    }

    /**
     * Returns the order's amount.
     *
     * @return the amount
     */
    public @Nullable BigDecimal getAmount() {
        return amount;
    }
}
