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
package io.americanexpress.synapse.data.autoconfigure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroupsPostProcessor;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.data.SynapseDataStore;
import io.americanexpress.synapse.data.internal.health.StoreHealthIndicator;
import io.americanexpress.synapse.data.internal.health.StoreReadinessGroups;
import io.americanexpress.synapse.data.internal.store.StoreDefinition;
import io.americanexpress.synapse.data.internal.store.StoreSettings;

/**
 * The health of every {@link SynapseDataStore}, while Boot's health support is on the class path and the {@code db}
 * indicator is enabled ({@code management.health.db.enabled}). Synapse defines {@code dbHealthContributor}, a composite
 * with one {@link StoreHealthIndicator} per store named after the store ({@code db/books}), so Boot's DataSource
 * contributor backs off. Every store is checked, whatever its {@code <prefix>DataSource}; the busy-pool check applies
 * when it is, or wraps, a Hikari pool. Without a store, Boot's contributor reports the application's own DataSource as
 * usual.
 * <p>
 * Readiness reflects a store only when it sets {@code synapse.data.stores.<name>.health.readiness=true}: the
 * {@code synapseStoreReadinessGroups} post-processor then adds {@code db/<name>} to the {@code readiness} group.
 */
@AutoConfiguration(afterName = "org.springframework.boot.health.autoconfigure.actuate.endpoint.AvailabilityProbesAutoConfiguration", beforeName = "org.springframework.boot.jdbc.autoconfigure.health.DataSourceHealthContributorAutoConfiguration")
@ConditionalOnClass({HealthContributor.class, HikariDataSource.class})
@ConditionalOnEnabledHealthIndicator("db")
@ConditionalOnBean(StoreDefinition.class)
public final class SynapseDataHealthAutoConfiguration {

    /** The name Boot's DataSource contributor backs off for. */
    private static final String CONTRIBUTOR = "dbHealthContributor";

    /** The suffix of a store's DataSource bean. */
    private static final String DATA_SOURCE_SUFFIX = "DataSource";

    SynapseDataHealthAutoConfiguration() {
    }

    /**
     * The stores' health: one indicator per store, named after the store, each waiting at most the store's
     * {@code health.timeout}. Define a bean named {@code dbHealthContributor} or {@code dbHealthIndicator} to replace
     * it.
     *
     * @param beanFactory the bean factory holding the stores
     * @param environment the environment the stores' settings are bound from
     * @return the composite contributor
     * @throws SynapseConfigurationException when a store's {@code health.timeout} is zero or negative
     */
    @Bean(CONTRIBUTOR)
    @ConditionalOnMissingBean(name = {"dbHealthIndicator", CONTRIBUTOR})
    static HealthContributor dbHealthContributor(ListableBeanFactory beanFactory, Environment environment) {
        Map<String, HealthContributor> indicators = new LinkedHashMap<>();
        for (StoreDefinition store : stores(beanFactory)) {
            Duration timeout = StoreSettings.bind(environment, store.name()).health().timeout();
            if (timeout.isNegative() || timeout.isZero()) {
                String key = StoreSettings.prefix(store.name()) + ".health.timeout";
                throw new SynapseConfigurationException(
                        "The store '" + store.name() + "' sets " + key
                                + " to a duration that is not positive, so its health check could never pass.",
                        "Set " + key + " to a positive duration, or remove it to wait 1s.");
            }
            DataSource dataSource = beanFactory.getBean(store.beanName(DATA_SOURCE_SUFFIX), DataSource.class);
            indicators.put(store.name(), new StoreHealthIndicator(store.name(), dataSource, timeout));
        }
        return CompositeHealthContributor.fromMap(indicators);
    }

    /**
     * Adds the stores that opt in to the {@code readiness} group, while Boot's actuator is on the class path. It keeps
     * the groups' additional paths, which only the actuator maps.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.web.AdditionalPathsMapper")
    static class ReadinessConfiguration {

        /**
         * The post-processor that adds {@code db/<name>} to the {@code readiness} group for each store with
         * {@code health.readiness=true}. It has no order, so it runs after Boot's probe post-processor. Define a bean
         * with the same name to replace it.
         *
         * @param beanFactory the bean factory holding the stores
         * @param environment the environment the stores' settings are bound from
         * @return the post-processor
         */
        @Bean(StoreReadinessGroups.BEAN_NAME)
        @ConditionalOnMissingBean(name = StoreReadinessGroups.BEAN_NAME)
        static HealthEndpointGroupsPostProcessor synapseStoreReadinessGroups(ListableBeanFactory beanFactory,
                Environment environment) {
            List<String> members = new ArrayList<>();
            for (StoreDefinition store : stores(beanFactory)) {
                if (StoreSettings.bind(environment, store.name()).health().readiness()) {
                    members.add("db/" + store.name());
                }
            }
            return new StoreReadinessGroups(members);
        }
    }

    private static Collection<StoreDefinition> stores(ListableBeanFactory beanFactory) {
        return beanFactory.getBeansOfType(StoreDefinition.class, false, false).values();
    }
}
