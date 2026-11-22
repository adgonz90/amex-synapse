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
package io.americanexpress.synapse.samples.multistore.data.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import io.americanexpress.synapse.data.Auditable;

/**
 * One entry of the ledger (table {@code ledger_entry}): the amount an order was placed for. A Spring Data JDBC
 * aggregate, so it needs {@code @Table}: with both access modules on the class path, Spring Data gives repositories
 * only to annotated aggregates.
 *
 * <p>
 * The database numbers the entries (an identity column), so a new entry has no id and saving it is an {@code INSERT}.
 * Entries are never changed.
 *
 * <p>
 * It implements {@link Auditable} itself, with its own column names ({@code entry_created}, {@code entry_created_by},
 * {@code entry_modified}, {@code entry_modified_by}): Synapse stamps any {@code Auditable} aggregate before it is
 * saved, whatever its columns are called.
 */
@Table
public class LedgerEntry implements Auditable {

    @Id
    private @Nullable Long id;

    private @Nullable UUID orderId;

    private @Nullable BigDecimal amount;

    @Column("entry_created")
    private @Nullable Instant createdAt;

    @Column("entry_created_by")
    private @Nullable String createdBy;

    @Column("entry_modified")
    private @Nullable Instant modifiedAt;

    @Column("entry_modified_by")
    private @Nullable String modifiedBy;

    /** For Spring Data. */
    LedgerEntry() {
    }

    /**
     * Creates an entry that is not saved yet.
     *
     * @param orderId the order the entry is for, in the orders store
     * @param amount the order's amount
     */
    public LedgerEntry(UUID orderId, BigDecimal amount) {
        this.orderId = orderId;
        this.amount = amount;
    }

    /**
     * Returns the entry's number.
     *
     * @return the number, or {@code null} before the entry is first saved
     */
    public @Nullable Long getId() {
        return id;
    }

    /**
     * Returns the order the entry is for. The orders store is another database, so nothing enforces that the order
     * exists.
     *
     * @return the order's id
     */
    public @Nullable UUID getOrderId() {
        return orderId;
    }

    /**
     * Returns the amount.
     *
     * @return the amount
     */
    public @Nullable BigDecimal getAmount() {
        return amount;
    }

    @Override
    public @Nullable Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public @Nullable String getCreatedBy() {
        return createdBy;
    }

    @Override
    public @Nullable Instant getModifiedAt() {
        return modifiedAt;
    }

    @Override
    public @Nullable String getModifiedBy() {
        return modifiedBy;
    }

    @Override
    public void setCreated(Instant at, String by) {
        this.createdAt = at;
        this.createdBy = by;
    }

    @Override
    public void setModified(Instant at, String by) {
        this.modifiedAt = at;
        this.modifiedBy = by;
    }
}
