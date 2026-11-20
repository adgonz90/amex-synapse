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
import java.util.List;
import java.util.function.Consumer;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;

import io.americanexpress.synapse.data.internal.store.StoreBeans;
import io.americanexpress.synapse.data.internal.store.StoreNames;

/**
 * Looks up Boot's {@link FlywayConfigurationCustomizer} beans for a store. It is the only class that names the type,
 * which spring-boot-flyway provides: {@link FlywayStoreFactory} uses it only after checking that the type is on the
 * class path, so the stores migrate without spring-boot-flyway too.
 */
final class BootFlywayCustomizers {

    /** The customizer type, checked by name before this class is used. */
    static final String TYPE = "org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer";

    /** The suffix of a store's own customizer, {@code <prefix>FlywayConfigurationCustomizer}. */
    static final String SUFFIX = "FlywayConfigurationCustomizer";

    private BootFlywayCustomizers() {
    }

    /**
     * Returns the customizers of a store, applied as Boot's JPA and Flyway configurations apply customizers: the
     * unqualified ones, then those qualified with the store's name, then the store's own
     * {@code <prefix>FlywayConfigurationCustomizer}, each group in {@code @Order} order
     * ({@link StoreBeans#applicationBeans}), so the most specific one runs last. A bean with another qualifier is never
     * used. Each customizer's {@code toString()} is its bean name.
     *
     * @param beanFactory the bean factory
     * @param store the store name
     * @param dependentSuffix the suffix of the bean being built, for the store's own customizer's dependency
     * @return the customizers, in the order to apply them
     */
    static List<Consumer<FluentConfiguration>> forStore(ConfigurableListableBeanFactory beanFactory, String store,
            String dependentSuffix) {
        List<Consumer<FluentConfiguration>> customizers = new ArrayList<>();
        StoreBeans
                .applicationBeansByName(beanFactory, store, FlywayConfigurationCustomizer.class, SUFFIX)
                .forEach((name, customizer) -> customizers.add(new Named(name, customizer)));
        String own = StoreNames.beanPrefix(store) + SUFFIX;
        if (beanFactory.containsBean(own)) {
            customizers
                    .add(new Named(own, StoreBeans
                            .dependency(beanFactory, store, SUFFIX, FlywayConfigurationCustomizer.class,
                                    dependentSuffix)));
        }
        return customizers;
    }

    /** A customizer bean, named for failures. */
    private record Named(String name, FlywayConfigurationCustomizer customizer)
            implements
                Consumer<FluentConfiguration> {

        @Override
        public void accept(FluentConfiguration configuration) {
            customizer.customize(configuration);
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
