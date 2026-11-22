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
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the ledger, each call in a transaction of the ledger store. With two stores and neither primary, every
 * {@code @Transactional} names its store, as here; an unqualified one fails startup, since Spring couldn't choose a
 * transaction manager.
 */
@Component
public class Ledger {

    private final LedgerRepository entries;

    /**
     * Creates the ledger.
     *
     * @param entries the ledger's entries
     */
    public Ledger(LedgerRepository entries) {
        this.entries = entries;
    }

    /**
     * Records that an order was placed. The entry is committed in the ledger store when this method returns, whatever
     * the caller's transaction in another store does next.
     *
     * @param orderId the order's id
     * @param amount the order's amount
     * @return the saved entry, with its number and audit stamps
     */
    @Transactional("ledger")
    public LedgerEntry record(UUID orderId, BigDecimal amount) {
        return entries.save(new LedgerEntry(orderId, amount));
    }
}
