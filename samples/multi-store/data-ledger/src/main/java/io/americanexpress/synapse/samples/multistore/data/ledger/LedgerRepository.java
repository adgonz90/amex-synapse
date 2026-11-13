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

import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.ListCrudRepository;

/**
 * The ledger store's repository. It is in the store's package, so Synapse registers it on the ledger store, and its
 * own methods run in transactions of the ledger store.
 */
public interface LedgerRepository extends ListCrudRepository<LedgerEntry, Long> {

    /**
     * Finds the entries of an order.
     *
     * @param orderId the order's id
     * @return its entries, in no particular order
     */
    List<LedgerEntry> findByOrderId(UUID orderId);
}
