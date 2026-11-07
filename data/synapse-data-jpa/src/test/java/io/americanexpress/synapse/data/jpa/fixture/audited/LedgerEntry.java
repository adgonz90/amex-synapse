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
package io.americanexpress.synapse.data.jpa.fixture.audited;

import jakarta.persistence.Entity;

import io.americanexpress.synapse.data.jpa.LongAuditedEntity;

/**
 * An audited ledger entry (table {@code ledger_entry}) whose {@code Long} id comes from the sequence
 * {@code ledger_entry_seq}.
 */
@Entity
public class LedgerEntry extends LongAuditedEntity {

    private long amount;

    /** For JPA. */
    protected LedgerEntry() {
    }

    /**
     * Creates an entry.
     *
     * @param amount the amount
     */
    public LedgerEntry(long amount) {
        this.amount = amount;
    }

    /**
     * Returns the amount.
     *
     * @return the amount
     */
    public long getAmount() {
        return amount;
    }
}
