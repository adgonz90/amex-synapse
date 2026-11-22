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
package io.americanexpress.synapse.samples.multistore;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.americanexpress.synapse.core.operation.ServiceOperation;
import io.americanexpress.synapse.samples.multistore.data.ledger.LedgerRepository;
import io.americanexpress.synapse.samples.multistore.data.orders.OrderRepository;
import io.americanexpress.synapse.test.data.StoreConnection;
import io.americanexpress.synapse.test.data.SynapseContainers;
import io.americanexpress.synapse.test.data.SynapseDataTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @SynapseDataTest("orders")} in the service, where both data modules, and so both stores, are on the class
 * path: the slice starts the orders store alone (its pool, migrations, entity and repository) and leaves out the
 * ledger store and every operation. It finds {@code OrdersApplication} as its configuration.
 */
@SynapseDataTest("orders")
class OrdersStoreSliceIT {

    @StoreConnection("orders")
    static final PostgreSQLContainer ORDERS = SynapseContainers.postgres("orders");

    @Autowired
    private ApplicationContext context;

    @Test
    void onlyTheOrdersStoreStarts() {
        assertThat(context.containsBean("ordersDataSource")).isTrue();
        assertThat(context.containsBean("ordersTransactionManager")).isTrue();
        assertThat(context.getBeanNamesForType(OrderRepository.class)).containsExactly("orderRepository");
        assertThat(context.containsBeanDefinition("ledgerDataSource")).isFalse();
        assertThat(context.containsBeanDefinition("ledgerTransactionManager")).isFalse();
        assertThat(context.getBeanNamesForType(LedgerRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(ServiceOperation.class)).isEmpty();
    }
}
