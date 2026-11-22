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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.sql.DataSource;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.ManagedTypes;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.data.jdbc.core.convert.DataAccessStrategy;
import org.springframework.data.jdbc.core.convert.IdGeneratingEntityCallback;
import org.springframework.data.jdbc.core.convert.JdbcConverter;
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions;
import org.springframework.data.jdbc.core.convert.QueryMappingConfiguration;
import org.springframework.data.jdbc.core.convert.RelationResolver;
import org.springframework.data.jdbc.core.dialect.JdbcDialect;
import org.springframework.data.jdbc.core.mapping.JdbcMappingContext;
import org.springframework.data.jdbc.repository.config.JdbcConfiguration;
import org.springframework.data.mapping.callback.EntityCallbacks;
import org.springframework.data.relational.RelationalManagedTypes;
import org.springframework.data.relational.core.mapping.NamingStrategy;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.util.ClassUtils;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.data.DataAccess;
import io.americanexpress.synapse.data.internal.store.BeanQualifiers;
import io.americanexpress.synapse.data.internal.store.FactoryMethods;
import io.americanexpress.synapse.data.internal.store.StoreBeans;
import io.americanexpress.synapse.data.internal.store.StoreDefinition;
import io.americanexpress.synapse.data.internal.store.StoreFactory;
import io.americanexpress.synapse.data.internal.store.StoreNames;
import io.americanexpress.synapse.data.jdbc.SynapseJdbcTransactionManager;
import io.americanexpress.synapse.data.vendor.DataStoreVendor;

/**
 * Builds each Spring Data JDBC store's beans; the store bean definitions name it as their factory and pass the store
 * name. It builds them as Spring Data JDBC's own configuration does ({@link JdbcConfiguration}), from the store's
 * {@code <prefix>DataSource} and {@code <prefix>NamedParameterJdbcTemplate}. The dialect is the store vendor's
 * ({@link DataStoreVendor#jdbcDialect()}), never resolved over a connection, so building a store's beans opens none.
 * <p>
 * As Boot's Spring Data JDBC configuration does, a store uses the application's {@link NamingStrategy} and
 * {@link JdbcCustomConversions} beans: a store's own {@code <prefix>NamingStrategy} or
 * {@code <prefix>JdbcCustomConversions}, else the application's bean of that type qualified with the store's name,
 * else its unqualified bean of that type (other stores' own beans don't count, and a bean with another qualifier is
 * never used), else Spring Data's default. Among several, the primary one is used; several without a primary one fail
 * startup. An unqualified {@code JdbcCustomConversions} holds one dialect's store conversions, so startup fails when it
 * would apply to JDBC stores whose dialects differ.
 */
public final class JdbcStoreFactory {

    /** The factory's bean name. */
    public static final String BEAN_NAME = "synapseJdbcStoreFactory";

    static final String TRANSACTION_MANAGER_SUFFIX = "TransactionManager";

    static final String DIALECT_SUFFIX = "JdbcDialect";

    static final String MANAGED_TYPES_SUFFIX = "RelationalManagedTypes";

    static final String CONVERSIONS_SUFFIX = "JdbcCustomConversions";

    static final String MAPPING_CONTEXT_SUFFIX = "JdbcMappingContext";

    static final String CONVERTER_SUFFIX = "JdbcConverter";

    static final String DATA_ACCESS_STRATEGY_SUFFIX = "DataAccessStrategy";

    static final String AGGREGATE_TEMPLATE_SUFFIX = "JdbcAggregateTemplate";

    /** The suffix of a store's own naming strategy, which Synapse never defines. */
    static final String NAMING_STRATEGY_SUFFIX = "NamingStrategy";

    private static final String DATA_SOURCE_SUFFIX = "DataSource";

    private static final String OPERATIONS_SUFFIX = "NamedParameterJdbcTemplate";

    /** The static field of a Spring Data JDBC dialect class that holds its instance. */
    private static final String INSTANCE = "INSTANCE";

    private final ConfigurableListableBeanFactory beanFactory;

    private final ApplicationContext applicationContext;

    private final StoreFactory storeFactory;

    /**
     * Creates the factory.
     *
     * @param beanFactory the bean factory, for the beans a store's JDBC beans are built from
     * @param applicationContext the application context, which the aggregate templates publish their events to and
     *        take their entity callbacks from
     * @param storeFactory the factory of the beans every store gets, for each store's vendor and definition
     */
    public JdbcStoreFactory(ConfigurableListableBeanFactory beanFactory, ApplicationContext applicationContext,
            StoreFactory storeFactory) {
        this.beanFactory = beanFactory;
        this.applicationContext = applicationContext;
        this.storeFactory = storeFactory;
    }

    /**
     * Builds a store's {@link SynapseJdbcTransactionManager} on its {@code <prefix>DataSource}, customized by Boot's
     * {@link TransactionManagerCustomizers} when there are any (such as {@code spring.transaction.default-timeout}).
     *
     * @param store the store name
     * @return the transaction manager
     */
    public SynapseJdbcTransactionManager transactionManager(String store) {
        SynapseJdbcTransactionManager transactionManager = new SynapseJdbcTransactionManager(
                StoreBeans
                        .dependency(beanFactory, store, DATA_SOURCE_SUFFIX, DataSource.class,
                                TRANSACTION_MANAGER_SUFFIX));
        beanFactory
                .getBeanProvider(TransactionManagerCustomizers.class)
                .ifAvailable(customizers -> customizers.customize(transactionManager));
        return transactionManager;
    }

    /**
     * Returns a store's dialect: the {@code INSTANCE} of the class its vendor names. No connection is opened.
     *
     * @param store the store name
     * @return the dialect
     * @throws IllegalStateException when the vendor's class can't be loaded or has no public static {@code INSTANCE}
     *         that is a {@link JdbcDialect}
     */
    public JdbcDialect dialect(String store) {
        DataStoreVendor vendor = storeFactory.vendor(store);
        String className = vendor.jdbcDialect();
        Object dialect;
        try {
            Field field = ClassUtils.forName(className, beanFactory.getBeanClassLoader()).getField(INSTANCE);
            dialect = Modifier.isStatic(field.getModifiers()) ? field.get(null) : null;
        } catch (ReflectiveOperationException | LinkageError ex) {
            throw invalidDialect(vendor, className, ex);
        }
        if (dialect instanceof JdbcDialect jdbcDialect) {
            return jdbcDialect;
        }
        throw invalidDialect(vendor, className, null);
    }

    /**
     * Returns a store's managed types: the classes annotated with Spring Data's {@code @Table} in and below the store's
     * package, scanned when the mapping context is initialized.
     *
     * @param store the store name
     * @return the managed types
     */
    public RelationalManagedTypes managedTypes(String store) {
        String basePackage = storeFactory.definition(store).basePackage();
        return RelationalManagedTypes
                .from(ManagedTypes.fromSupplier(() -> JdbcConfiguration.scanForEntities(basePackage)));
    }

    /**
     * Returns a store's conversions: the application's {@link JdbcCustomConversions}, which replace the store
     * conversions of every store's dialect as Boot's would, else those Spring Data builds for the store's
     * {@code <prefix>JdbcDialect}, with no converters of the application's. An unqualified application bean holds one
     * dialect's store conversions, so it must not apply to JDBC stores whose dialects differ.
     *
     * @param store the store name
     * @return the conversions
     * @throws SynapseConfigurationException when the application defines several, none of them primary, or when its
     *         unqualified bean would apply to JDBC stores whose dialects differ
     */
    public JdbcCustomConversions customConversions(String store) {
        String application = StoreBeans
                .applicationBeanName(beanFactory, store, JdbcCustomConversions.class, CONVERSIONS_SUFFIX);
        if (application != null) {
            if (BeanQualifiers.qualifier(beanFactory, application) == null) {
                checkOneDialect(application);
            }
            return beanFactory.getBean(application, JdbcCustomConversions.class);
        }
        return JdbcConfiguration
                .createCustomConversions(
                        StoreBeans
                                .dependency(beanFactory, store, DIALECT_SUFFIX, JdbcDialect.class, CONVERSIONS_SUFFIX),
                        List.of());
    }

    /**
     * Fails when an unqualified application conversions bean would apply to JDBC stores whose dialects differ: every
     * JDBC store whose {@code <prefix>JdbcCustomConversions} is Synapse's and chooses that bean. A store's dialect is
     * its vendor's, or the type of the {@code <prefix>JdbcDialect} its store class defines; no bean is created.
     */
    private void checkOneDialect(String conversions) {
        Map<String, String> dialects = new TreeMap<>();
        for (String name : beanFactory.getBeanNamesForType(StoreDefinition.class, false, false)) {
            StoreDefinition store = beanFactory.getBean(name, StoreDefinition.class);
            if (store.access() == DataAccess.JDBC
                    && isSynapses(store.beanName(CONVERSIONS_SUFFIX), "customConversions")
                    && conversions
                            .equals(StoreBeans
                                    .applicationBeanName(beanFactory, store.name(), JdbcCustomConversions.class,
                                            CONVERSIONS_SUFFIX))) {
                dialects.put(store.name(), dialectName(store));
            }
        }
        if (new HashSet<>(dialects.values()).size() > 1) {
            List<String> stores = new ArrayList<>();
            dialects.forEach((store, dialect) -> stores.add(store + " (" + dialect + ")"));
            throw new SynapseConfigurationException("The JdbcCustomConversions bean " + conversions
                    + " would apply to JDBC stores whose dialects differ: " + String.join(", ", stores) + ".",
                    "Qualify it with the store it is for, such as @Qualifier(\"" + dialects.keySet().iterator().next()
                            + "\"), or define each store's <prefix>JdbcCustomConversions in its @SynapseDataStore "
                            + "class.");
        }
    }

    /** Returns the class name of a store's dialect, without creating it. */
    private String dialectName(StoreDefinition store) {
        String dialect = store.beanName(DIALECT_SUFFIX);
        if (isSynapses(dialect, "dialect")) {
            return storeFactory.vendor(store.name()).jdbcDialect();
        }
        Class<?> type = beanFactory.getType(dialect, false);
        return type == null ? dialect : ClassUtils.getUserClass(type).getName();
    }

    /**
     * Whether a store bean is the one Synapse registers: made by this factory's method, not replaced. Read through
     * {@link FactoryMethods}, so a definition AOT processing generated counts too.
     */
    private boolean isSynapses(String beanName, String factoryMethod) {
        return FactoryMethods.isMadeBy(beanFactory, beanName, BEAN_NAME, factoryMethod);
    }

    /**
     * Builds a store's {@link JdbcMappingContext} on its {@code <prefix>RelationalManagedTypes} and
     * {@code <prefix>JdbcCustomConversions}, with the store's naming strategy: its {@code <prefix>NamingStrategy},
     * else the application's {@link NamingStrategy}, else Spring Data's default.
     *
     * @param store the store name
     * @return the mapping context
     * @throws SynapseConfigurationException when the application defines several naming strategies, none of them
     *         primary, and the store none of its own
     */
    public JdbcMappingContext mappingContext(String store) {
        RelationalManagedTypes managedTypes = StoreBeans
                .dependency(beanFactory, store, MANAGED_TYPES_SUFFIX, RelationalManagedTypes.class,
                        MAPPING_CONTEXT_SUFFIX);
        JdbcCustomConversions conversions = StoreBeans
                .dependency(beanFactory, store, CONVERSIONS_SUFFIX, JdbcCustomConversions.class,
                        MAPPING_CONTEXT_SUFFIX);
        return JdbcConfiguration.createMappingContext(managedTypes, conversions, namingStrategy(store));
    }

    /**
     * Builds a store's {@link JdbcConverter} on its {@code <prefix>JdbcMappingContext},
     * {@code <prefix>NamedParameterJdbcTemplate}, {@code <prefix>JdbcCustomConversions} and {@code <prefix>JdbcDialect}.
     * It resolves relations through the store's {@code <prefix>DataAccessStrategy}, looked up on first use, since that
     * strategy is built on this converter (as Spring Data's configuration does with a lazy resolver).
     *
     * @param store the store name
     * @return the converter
     */
    public JdbcConverter converter(String store) {
        JdbcMappingContext mappingContext = StoreBeans
                .dependency(beanFactory, store, MAPPING_CONTEXT_SUFFIX, JdbcMappingContext.class,
                        CONVERTER_SUFFIX);
        NamedParameterJdbcOperations operations = StoreBeans
                .dependency(beanFactory, store, OPERATIONS_SUFFIX,
                        NamedParameterJdbcOperations.class, CONVERTER_SUFFIX);
        String strategy = StoreNames.beanPrefix(store) + DATA_ACCESS_STRATEGY_SUFFIX;
        RelationResolver relations = (identifier, path) -> beanFactory
                .getBean(strategy, DataAccessStrategy.class)
                .findAllByPath(identifier, path);
        return JdbcConfiguration
                .createConverter(mappingContext, operations, relations,
                        StoreBeans
                                .dependency(beanFactory, store, CONVERSIONS_SUFFIX, JdbcCustomConversions.class,
                                        CONVERTER_SUFFIX),
                        StoreBeans.dependency(beanFactory, store, DIALECT_SUFFIX, JdbcDialect.class, CONVERTER_SUFFIX));
    }

    /**
     * Builds a store's {@link DataAccessStrategy} on its {@code <prefix>NamedParameterJdbcTemplate},
     * {@code <prefix>JdbcConverter} and {@code <prefix>JdbcDialect}, with the application's
     * {@link QueryMappingConfiguration} when it defines one.
     *
     * @param store the store name
     * @return the data access strategy
     */
    public DataAccessStrategy dataAccessStrategy(String store) {
        return JdbcConfiguration
                .createDataAccessStrategy(
                        StoreBeans
                                .dependency(beanFactory, store, OPERATIONS_SUFFIX, NamedParameterJdbcOperations.class,
                                        DATA_ACCESS_STRATEGY_SUFFIX),
                        StoreBeans
                                .dependency(beanFactory, store, CONVERTER_SUFFIX, JdbcConverter.class,
                                        DATA_ACCESS_STRATEGY_SUFFIX),
                        beanFactory.getBeanProvider(QueryMappingConfiguration.class).getIfAvailable(),
                        StoreBeans
                                .dependency(beanFactory, store, DIALECT_SUFFIX, JdbcDialect.class,
                                        DATA_ACCESS_STRATEGY_SUFFIX));
    }

    /**
     * Builds a store's {@link JdbcAggregateTemplate} on its {@code <prefix>JdbcMappingContext},
     * {@code <prefix>JdbcConverter} and {@code <prefix>DataAccessStrategy}. It runs the application's entity callbacks
     * (such as Synapse's auditing) and publishes Spring Data's events. The store's repositories use it.
     * <p>
     * It also runs Spring Data's {@link IdGeneratingEntityCallback} on the store's mapping context, dialect and
     * {@code <prefix>NamedParameterJdbcTemplate}, so an {@code @Sequence} id comes from the store's own database. That
     * callback belongs to this template only, not to the context: a context's entity callbacks run for every store's
     * aggregates.
     *
     * @param store the store name
     * @return the aggregate template
     */
    public JdbcAggregateTemplate aggregateTemplate(String store) {
        JdbcMappingContext mappingContext = StoreBeans
                .dependency(beanFactory, store, MAPPING_CONTEXT_SUFFIX, JdbcMappingContext.class,
                        AGGREGATE_TEMPLATE_SUFFIX);
        JdbcAggregateTemplate template = new JdbcAggregateTemplate(applicationContext, mappingContext,
                StoreBeans
                        .dependency(beanFactory, store, CONVERTER_SUFFIX, JdbcConverter.class,
                                AGGREGATE_TEMPLATE_SUFFIX),
                StoreBeans
                        .dependency(beanFactory, store, DATA_ACCESS_STRATEGY_SUFFIX, DataAccessStrategy.class,
                                AGGREGATE_TEMPLATE_SUFFIX));
        EntityCallbacks callbacks = EntityCallbacks.create(applicationContext);
        callbacks
                .addEntityCallback(new IdGeneratingEntityCallback(mappingContext,
                        StoreBeans
                                .dependency(beanFactory, store, DIALECT_SUFFIX, JdbcDialect.class,
                                        AGGREGATE_TEMPLATE_SUFFIX),
                        StoreBeans
                                .dependency(beanFactory, store, OPERATIONS_SUFFIX, NamedParameterJdbcOperations.class,
                                        AGGREGATE_TEMPLATE_SUFFIX)));
        template.setEntityCallbacks(callbacks);
        return template;
    }

    private @Nullable NamingStrategy namingStrategy(String store) {
        if (beanFactory.containsBean(StoreNames.beanPrefix(store) + NAMING_STRATEGY_SUFFIX)) {
            return StoreBeans
                    .dependency(beanFactory, store, NAMING_STRATEGY_SUFFIX, NamingStrategy.class,
                            MAPPING_CONTEXT_SUFFIX);
        }
        return StoreBeans.applicationBean(beanFactory, store, NamingStrategy.class, NAMING_STRATEGY_SUFFIX);
    }

    private static IllegalStateException invalidDialect(DataStoreVendor vendor, String className,
            @Nullable Throwable cause) {
        return new IllegalStateException("The vendor '" + vendor.name() + "' names the JDBC dialect " + className
                + ", which is not a class with a public static " + INSTANCE + " field that holds a "
                + JdbcDialect.class.getName() + ".", cause);
    }
}
