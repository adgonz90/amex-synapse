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
package io.americanexpress.synapse.data.jdbc.internal;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.data.jdbc.core.convert.DataAccessStrategy;
import org.springframework.data.jdbc.core.convert.JdbcConverter;
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions;
import org.springframework.data.jdbc.core.dialect.JdbcDialect;
import org.springframework.data.jdbc.core.mapping.JdbcMappingContext;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.data.jdbc.repository.config.JdbcRepositoryConfigExtension;
import org.springframework.data.relational.RelationalManagedTypes;
import org.springframework.util.ClassUtils;

import io.americanexpress.synapse.data.DataAccess;
import io.americanexpress.synapse.data.internal.store.IgnoredKeys;
import io.americanexpress.synapse.data.internal.store.StoreDefinition;
import io.americanexpress.synapse.data.internal.store.StoreInfrastructure;
import io.americanexpress.synapse.data.internal.store.StoreRegistrar;
import io.americanexpress.synapse.data.internal.store.StoreRepositoryConfigurationSource;
import io.americanexpress.synapse.data.jdbc.SynapseJdbcTransactionManager;

/**
 * Registers a Spring Data JDBC store's beans: {@code <prefix>TransactionManager}, {@code <prefix>JdbcDialect},
 * {@code <prefix>RelationalManagedTypes}, {@code <prefix>JdbcCustomConversions}, {@code <prefix>JdbcMappingContext},
 * {@code <prefix>JdbcConverter}, {@code <prefix>DataAccessStrategy} and {@code <prefix>JdbcAggregateTemplate}, each
 * built by {@link JdbcStoreFactory}, and the Spring Data JDBC repositories in and below the store's package, which use
 * that aggregate template and transaction manager. A name the registry already has, such as a bean of the store's
 * class, is skipped. Listed in {@code META-INF/spring.factories}.
 */
public final class JdbcStoreInfrastructure implements StoreInfrastructure {

    /** The classes {@link JdbcStoreFactory} builds with, from Spring Data JDBC and Spring Data Relational. */
    private static final String[] REQUIRED_CLASSES = {"org.springframework.data.jdbc.core.JdbcAggregateTemplate",
            "org.springframework.data.relational.RelationalManagedTypes"};

    @Override
    public DataAccess access() {
        return DataAccess.JDBC;
    }

    @Override
    public Class<?> transactionManagerType() {
        return SynapseJdbcTransactionManager.class;
    }

    /**
     * Returns whether Spring Data JDBC is on the class path.
     *
     * @param classLoader the class loader of the application
     * @return {@code true} when every class the module builds with is present
     */
    @Override
    public boolean isAvailable(@Nullable ClassLoader classLoader) {
        for (String className : REQUIRED_CLASSES) {
            if (!ClassUtils.isPresent(className, classLoader)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the Boot keys that JDBC stores ignore: {@code spring.data.jdbc.dialect} (a store's dialect is its
     * vendor's) and {@code spring.dao.exceptiontranslation.enabled} (a store's transaction manager is always a
     * {@code JdbcTransactionManager}).
     *
     * @return the keys
     */
    @Override
    public List<IgnoredKeys> ignoredKeys() {
        return List
                .of(IgnoredKeys
                        .key("spring.dao.exceptiontranslation.enabled",
                                "spring.dao.exceptiontranslation.enabled is set, but Synapse ignores it: each JDBC store's "
                                        + "transaction manager is a JdbcTransactionManager, which translates exceptions. To "
                                        + "change it, define <prefix>TransactionManager in the store's @SynapseDataStore "
                                        + "class."),
                        IgnoredKeys
                                .key("spring.data.jdbc.dialect",
                                        "spring.data.jdbc.dialect is set, but Synapse ignores it: each JDBC store takes its "
                                                + "dialect from its vendor. To change a store's dialect, define "
                                                + "<prefix>JdbcDialect in its @SynapseDataStore class."));
    }

    @Override
    public void register(StoreDefinition store, BeanDefinitionRegistry registry, Environment environment,
            ResourceLoader resourceLoader) {
        register(registry, store, JdbcStoreFactory.TRANSACTION_MANAGER_SUFFIX, SynapseJdbcTransactionManager.class,
                "transactionManager");
        register(registry, store, JdbcStoreFactory.DIALECT_SUFFIX, JdbcDialect.class, "dialect");
        register(registry, store, JdbcStoreFactory.MANAGED_TYPES_SUFFIX, RelationalManagedTypes.class,
                "managedTypes");
        register(registry, store, JdbcStoreFactory.CONVERSIONS_SUFFIX, JdbcCustomConversions.class,
                "customConversions");
        register(registry, store, JdbcStoreFactory.MAPPING_CONTEXT_SUFFIX, JdbcMappingContext.class,
                "mappingContext");
        register(registry, store, JdbcStoreFactory.CONVERTER_SUFFIX, JdbcConverter.class, "converter");
        register(registry, store, JdbcStoreFactory.DATA_ACCESS_STRATEGY_SUFFIX, DataAccessStrategy.class,
                "dataAccessStrategy");
        register(registry, store, JdbcStoreFactory.AGGREGATE_TEMPLATE_SUFFIX, JdbcAggregateTemplate.class,
                "aggregateTemplate");
        // Only the aggregate template: Spring Data JDBC rejects it combined with jdbcOperationsRef or
        // dataAccessStrategyRef, and without it would look up the mapping context, dialect and converter by type.
        Map<String, String> references = Map
                .of("jdbcAggregateOperationsRef", store.beanName(JdbcStoreFactory.AGGREGATE_TEMPLATE_SUFFIX),
                        "transactionManagerRef", store.beanName(JdbcStoreFactory.TRANSACTION_MANAGER_SUFFIX));
        new StoreRepositoryConfigurationSource(store, EnableJdbcRepositories.class, references, environment,
                resourceLoader, registry).registerRepositories(new JdbcRepositoryConfigExtension());
    }

    private static void register(BeanDefinitionRegistry registry, StoreDefinition store, String suffix, Class<?> type,
            String factoryMethod) {
        StoreRegistrar.registerStoreBean(registry, store, suffix, type, JdbcStoreFactory.BEAN_NAME, factoryMethod);
    }
}
