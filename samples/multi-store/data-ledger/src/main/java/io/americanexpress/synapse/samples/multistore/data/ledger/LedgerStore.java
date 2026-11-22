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

import org.springframework.context.annotation.Configuration;

import io.americanexpress.synapse.data.DataAccess;
import io.americanexpress.synapse.data.SynapseDataStore;

/**
 * Declares the ledger store. Synapse builds it from {@code synapse.data.stores.ledger.*} (this module's
 * {@code META-INF/synapse/config*.yaml}): a Hikari pool named {@code ledger}, Flyway migrations from
 * {@code db/migration/ledger}, the Spring Data JDBC infrastructure for the {@code @Table} aggregates of this package,
 * the transaction manager {@code @Transactional("ledger")} names, and the repositories. Every bean carries the
 * qualifier {@code ledger}.
 *
 * <p>
 * The service also has the orders store, on JPA, so both access modules are on its class path and the store names its
 * access: JDBC. A service whose only store is this one could leave the default ({@code AUTO}).
 */
@Configuration(proxyBeanMethods = false)
@SynapseDataStore(value = "ledger", access = DataAccess.JDBC)
public class LedgerStore {
}
