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
package io.americanexpress.synapse.data.internal.migration;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;

import io.americanexpress.synapse.data.internal.store.StoreBeans;
import io.americanexpress.synapse.data.internal.store.StoreDefinition;
import io.americanexpress.synapse.data.internal.store.StoreNames;
import io.americanexpress.synapse.data.internal.store.StoreSettings;

/**
 * Builds each store's {@code <prefix>Flyway} bean, and finds a store's migration for its lifecycle. The data
 * auto-configuration defines it as {@value #BEAN_NAME} only while Flyway is on the class path, and the store
 * registrar registers the {@code <prefix>Flyway} definitions, which name it as their factory, only then too.
 */
public final class FlywayStoreFactory implements StoreMigrations {

    /** The factory's bean name. */
    public static final String BEAN_NAME = "synapseDataFlywayFactory";

    /** The suffix of the bean name of a store's Flyway. */
    public static final String BEAN_SUFFIX = "Flyway";

    private final ConfigurableListableBeanFactory beanFactory;

    private final Environment environment;

    /**
     * Creates the factory.
     *
     * @param beanFactory the bean factory, for the store's definition and DataSource
     * @param environment the environment the store settings are bound from
     */
    public FlywayStoreFactory(ConfigurableListableBeanFactory beanFactory, Environment environment) {
        this.beanFactory = beanFactory;
        this.environment = environment;
    }

    /**
     * Builds a store's Flyway on its {@code <prefix>DataSource}, without opening a connection.
     *
     * @param store the store name
     * @return the Flyway
     * @see FlywayStoreMigration#flyway
     */
    public Flyway flyway(String store) {
        String prefix = StoreNames.beanPrefix(store);
        StoreDefinition definition = beanFactory
                .getBean(prefix + StoreDefinition.BEAN_SUFFIX, StoreDefinition.class);
        DataSource dataSource = StoreBeans.dependency(beanFactory, store, "DataSource", DataSource.class, BEAN_SUFFIX);
        ClassLoader classLoader = beanFactory.getBeanClassLoader();
        return FlywayStoreMigration
                .flyway(definition, dataSource, StoreSettings.bind(environment, store),
                        classLoader != null ? classLoader : FlywayStoreFactory.class.getClassLoader());
    }

    /**
     * Returns the migration that runs the store's {@code <prefix>Flyway}, whichever bean it is.
     *
     * @param store the store name
     * @return the migration, or {@code null} when the store has no {@code <prefix>Flyway} bean
     */
    @Override
    public @Nullable StoreMigration migration(String store) {
        String name = StoreNames.beanPrefix(store) + BEAN_SUFFIX;
        if (!beanFactory.containsBean(name)) {
            return null;
        }
        return new FlywayStoreMigration(store, beanFactory.getBean(name, Flyway.class));
    }
}
