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

import org.springframework.context.annotation.Configuration;

import io.americanexpress.synapse.data.DataAccess;
import io.americanexpress.synapse.data.SynapseDataStore;

/**
 * Declares the orders store. Synapse builds it from {@code synapse.data.stores.orders.*} (this module's
 * {@code META-INF/synapse/config*.yaml}): a Hikari pool named {@code orders}, Flyway migrations from
 * {@code db/migration/orders}, a JPA EntityManagerFactory for the entities of this package, the transaction manager
 * {@code @Transactional("orders")} names, and the repositories. Every bean carries the qualifier {@code orders}.
 *
 * <p>
 * The service also has the ledger store, on Spring Data JDBC, so both access modules are on its class path and the
 * store names its access: JPA. A service whose only store is this one could leave the default ({@code AUTO}).
 */
@Configuration(proxyBeanMethods = false)
@SynapseDataStore(value = "orders", access = DataAccess.JPA)
public class OrdersStore {
}
