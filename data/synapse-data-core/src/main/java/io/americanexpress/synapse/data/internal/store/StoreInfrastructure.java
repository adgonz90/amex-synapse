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

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;

import io.americanexpress.synapse.data.DataAccess;

/**
 * An access module's part of store registration: synapse-data-jpa and synapse-data-jdbc each list one in
 * {@code META-INF/spring.factories}. Its presence is what makes the access available.
 */
public interface StoreInfrastructure {

    /**
     * Returns the access this module provides.
     *
     * @return {@link DataAccess#JPA} or {@link DataAccess#JDBC}
     */
    DataAccess access();

    /**
     * Returns whether the libraries the module builds its beans with are on the class path. A module that isn't
     * available is left out when a store's access is resolved, as if it were absent.
     *
     * @param classLoader the class loader of the application, or {@code null} for the default one
     * @return {@code true} by default
     */
    default boolean isAvailable(@Nullable ClassLoader classLoader) {
        return true;
    }

    /**
     * Returns the Boot keys that the module's stores ignore. When a store uses the module, the data stores' startup
     * report warns about each one the application sets, naming the key, never its value.
     *
     * @return the keys, none by default
     */
    default List<IgnoredKeys> ignoredKeys() {
        return List.of();
    }

    /**
     * Returns the type of the transaction manager the module builds for its stores. When a store's
     * {@code <prefix>TransactionManager} is not one (or a subclass of it), the data stores' startup report warns that
     * the store loses what that manager adds.
     *
     * @return the type, or {@code null} (the default) when the module builds none
     */
    default @Nullable Class<?> transactionManagerType() {
        return null;
    }

    /**
     * Registers the access module's bean definitions for a store, after the data-core ones. Use
     * {@link StoreRegistrar#registerStoreBean} so each one skips a name the store's class defines, and gets the store's
     * qualifier and primary flag.
     *
     * @param store the store
     * @param registry the registry
     * @param environment the environment
     * @param resourceLoader the resource loader
     */
    void register(StoreDefinition store, BeanDefinitionRegistry registry, Environment environment,
            ResourceLoader resourceLoader);
}
