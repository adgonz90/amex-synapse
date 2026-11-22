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
package io.americanexpress.synapse.data.internal.store;

import java.util.List;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.transaction.PlatformTransactionManager;

import io.americanexpress.synapse.data.DataAccess;

/**
 * Stands in for synapse-data-jdbc in this module's tests, so a store with {@link DataAccess#AUTO} resolves to
 * {@link DataAccess#JDBC}. It registers none of its own beans: the tests here cover the beans every store gets. As
 * synapse-data-jdbc does for its {@code <prefix>TransactionManager}, it makes a test's transaction manager with that
 * name the store's (see {@link StoreRegistrar#registerStoreBean}). Listed in the test
 * {@code META-INF/spring.factories}.
 */
public class TestJdbcStoreInfrastructure implements StoreInfrastructure {

    private static final String TRANSACTION_MANAGER = "TransactionManager";

    @Override
    public DataAccess access() {
        return DataAccess.JDBC;
    }

    /** One prefix, so the startup report's tests see an access module's keys. */
    @Override
    public List<IgnoredKeys> ignoredKeys() {
        return List.of(IgnoredKeys.prefix("spring.test.access"));
    }

    /** A test type, and one that is absent, so the startup report's tests see the beans an access module ignores. */
    @Override
    public List<String> ignoredBeanTypes() {
        return List.of(IgnoredByTestStores.class.getName(), "com.example.AbsentType");
    }

    /** A bean type that the test stores don't use. */
    public interface IgnoredByTestStores {
    }

    @Override
    public void register(StoreDefinition store, BeanDefinitionRegistry registry, Environment environment,
            ResourceLoader resourceLoader) {
        // The data-core beans are registered by StoreRegistrar. A test's bean named like the JDBC module's
        // transaction manager replaces it, so it is marked as the store's; no other bean is registered.
        if (registry.containsBeanDefinition(store.beanName(TRANSACTION_MANAGER))) {
            StoreRegistrar
                    .registerStoreBean(registry, store, TRANSACTION_MANAGER, PlatformTransactionManager.class,
                            "unused", "unused");
        }
    }
}
