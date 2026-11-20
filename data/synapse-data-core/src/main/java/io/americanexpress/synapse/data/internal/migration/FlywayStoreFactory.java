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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.migration.JavaMigration;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

import io.americanexpress.synapse.data.internal.store.BeanQualifiers;
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
     * Builds a store's Flyway on its {@code <prefix>DataSource}, without opening a connection, with the application's
     * Flyway beans that apply to the store, as Boot's Flyway auto-configuration applies them to its Flyway:
     * <ul>
     * <li>Boot's {@code FlywayConfigurationCustomizer}s, when spring-boot-flyway is on the class path: the unqualified
     * ones, then those qualified with the store's name, then the store's own
     * {@code <prefix>FlywayConfigurationCustomizer}, each group in {@code @Order} order;</li>
     * <li>{@link Callback}s and {@link JavaMigration}s qualified with the store's name, in {@code @Order} order; while
     * the service has a single store, the unqualified ones before them. With several stores an unqualified one applies
     * to none, and the data stores' startup report names it ({@link #unusedBeans}).</li>
     * </ul>
     * A bean with another qualifier is never used.
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
        ClassLoader beanClassLoader = beanFactory.getBeanClassLoader();
        ClassLoader classLoader = beanClassLoader != null ? beanClassLoader : FlywayStoreFactory.class.getClassLoader();
        // The type is named only in BootFlywayCustomizers, which is used only when spring-boot-flyway is present.
        List<Consumer<FluentConfiguration>> customizers = ClassUtils.isPresent(BootFlywayCustomizers.TYPE, classLoader)
                ? BootFlywayCustomizers.forStore(beanFactory, store, BEAN_SUFFIX)
                : List.of();
        return FlywayStoreMigration
                .flyway(definition, dataSource, StoreSettings.bind(environment, store), classLoader, customizers,
                        storeBeans(store, Callback.class), storeBeans(store, JavaMigration.class));
    }

    /**
     * Returns a store's beans of a type that has no store-own bean: qualified with the store's name, and the unqualified
     * ones too while there is a single store.
     */
    private <T> List<T> storeBeans(String store, Class<T> type) {
        // No store has a <prefix>Flyway bean of these types, so the suffix leaves no application bean out.
        if (storeCount(beanFactory) == 1) {
            return StoreBeans.applicationBeans(beanFactory, store, type, BEAN_SUFFIX);
        }
        return StoreBeans.qualifiedApplicationBeans(beanFactory, store, type, BEAN_SUFFIX);
    }

    /**
     * Describes each {@link Callback} and {@link JavaMigration} bean that no store's Flyway uses, without creating any
     * bean: with several stores, an unqualified one; with any number, one whose qualifier names no store. The data
     * stores' startup report logs each one as a WARN.
     *
     * @param beanFactory the bean factory
     * @return one line per unused bean, sorted by type then name
     */
    public static List<String> unusedBeans(ConfigurableListableBeanFactory beanFactory) {
        Set<String> stores = new TreeSet<>();
        for (String name : beanFactory.getBeanNamesForType(StoreDefinition.class, false, false)) {
            stores.add(beanFactory.getBean(name, StoreDefinition.class).name());
        }
        List<String> unused = new ArrayList<>();
        if (stores.isEmpty()) {
            return unused;
        }
        for (Class<?> type : List.of(Callback.class, JavaMigration.class)) {
            String simpleName = type.getSimpleName();
            for (String name : new TreeSet<>(Arrays.asList(beanFactory.getBeanNamesForType(type, true, false)))) {
                String qualifier = BeanQualifiers.qualifier(beanFactory, name);
                String bean = "The Flyway " + simpleName + " bean " + name + " is not used by any store: ";
                if (qualifier == null && stores.size() > 1) {
                    unused
                            .add(bean + "with several stores, a " + simpleName + " applies only to the store its "
                                    + "@Qualifier names, such as @Qualifier(\"" + stores.iterator().next() + "\").");
                } else if (qualifier != null && !stores.contains(qualifier)) {
                    unused
                            .add(bean + (BeanQualifiers.ANOTHER_QUALIFIER.equals(qualifier)
                                    ? "its qualifier isn't a @Qualifier, so it names no store."
                                    : "its qualifier '" + qualifier + "' names no store."));
                }
            }
        }
        return unused;
    }

    private static int storeCount(ConfigurableListableBeanFactory beanFactory) {
        return beanFactory.getBeanNamesForType(StoreDefinition.class, false, false).length;
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
