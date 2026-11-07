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
package io.americanexpress.synapse.data.jpa.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;

import org.hibernate.boot.model.naming.ImplicitNamingStrategy;
import org.hibernate.boot.model.naming.PhysicalNamingStrategy;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.jpa.boot.spi.JpaSettings;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.hibernate.autoconfigure.HibernateProperties;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.hibernate.autoconfigure.HibernateSettings;
import org.springframework.boot.jpa.EntityManagerFactoryBuilder;
import org.springframework.boot.jpa.autoconfigure.JpaProperties;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.support.SQLExceptionTranslator;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypesScanner;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.data.autoconfigure.SynapseDataStoreProperties;
import io.americanexpress.synapse.data.internal.store.FactoryMethods;
import io.americanexpress.synapse.data.internal.store.StoreBeans;
import io.americanexpress.synapse.data.internal.store.StoreFactory;
import io.americanexpress.synapse.data.internal.store.StoreKeyOverride;
import io.americanexpress.synapse.data.internal.store.StoreNames;
import io.americanexpress.synapse.data.internal.store.StoreSettings;
import io.americanexpress.synapse.data.vendor.DataStoreVendor;

/**
 * Builds each JPA store's beans; the store bean definitions name it as their factory and pass the store name. It
 * builds with Boot's own types: {@link JpaProperties} bound at {@code synapse.data.stores.<store>.jpa},
 * {@link HibernateProperties} at {@code synapse.data.stores.<store>.jpa.hibernate}, a
 * {@link HibernateJpaVendorAdapter} and an {@link EntityManagerFactoryBuilder}. Hibernate is told the database product
 * and version, and never reads JDBC metadata or changes the schema, so building the EntityManagerFactory opens no
 * connection. The store's {@code ddl-auto} runs in its {@link JpaSchemaAction} when the store starts, after its
 * migrations.
 * <p>
 * As Boot's JPA configuration does, a store uses the application's {@link PhysicalNamingStrategy},
 * {@link ImplicitNamingStrategy} and {@link HibernatePropertiesCustomizer} beans, with the qualifier rules of the JDBC
 * stores. A naming strategy is the store's own {@code <prefix>PhysicalNamingStrategy} or
 * {@code <prefix>ImplicitNamingStrategy}, else the application's bean of that type qualified with the store's name,
 * else its unqualified bean; among several, the primary one is used, and several without a primary one fail startup.
 * Every customizer that applies runs, as Boot runs them all: the unqualified ones, then those qualified with the
 * store's name, then the store's own {@code <prefix>HibernatePropertiesCustomizer}, each group in {@code @Order} order,
 * so the most specific one, running last, wins on a key several set. A bean with another qualifier is never used.
 */
public final class JpaStoreFactory {

    /** The factory's bean name. */
    public static final String BEAN_NAME = "synapseJpaStoreFactory";

    /**
     * The mapping resource added to every store's persistence unit. Its default entity listener assigns the ids of
     * new {@code IdEntity} subclasses and stamps {@code Auditable} entities.
     */
    static final String MAPPING_RESOURCE = "io/americanexpress/synapse/data/jpa/synapse-orm.xml";

    static final String MANAGED_TYPES_SUFFIX = "PersistenceManagedTypes";

    static final String ENTITY_MANAGER_FACTORY_SUFFIX = "EntityManagerFactory";

    /** The factory method that builds a store's EntityManagerFactory, which its bean definition names. */
    static final String ENTITY_MANAGER_FACTORY_METHOD = "entityManagerFactory";

    static final String TRANSACTION_MANAGER_SUFFIX = "TransactionManager";

    static final String SCHEMA_ACTION_SUFFIX = "SchemaAction";

    private static final String DATA_SOURCE_SUFFIX = "DataSource";

    /** The suffix of a store's own physical naming strategy, which Synapse never defines. */
    private static final String PHYSICAL_NAMING_SUFFIX = "PhysicalNamingStrategy";

    /** The suffix of a store's own implicit naming strategy, which Synapse never defines. */
    private static final String IMPLICIT_NAMING_SUFFIX = "ImplicitNamingStrategy";

    /** The suffix of a store's own Hibernate properties customizer, which Synapse never defines. */
    private static final String CUSTOMIZER_SUFFIX = "HibernatePropertiesCustomizer";

    private static final String FLYWAY = "org.flywaydb.core.Flyway";

    private static final String VALIDATE = "validate";

    private static final String NONE = "none";

    /** Boot's {@code ddl-auto} values. */
    private static final Set<String> ACTIONS = Set.of(NONE, VALIDATE, "update", "create", "create-drop");

    /** The values that change the schema, which a store must allow. */
    private static final Set<String> SCHEMA_CHANGING_ACTIONS = Set.of("update", "create", "create-drop");

    /**
     * The prefixes of the {@code jpa.properties} keys that choose or shape a schema action behind the guard's back:
     * Hibernate's {@code hibernate.hbm2ddl.*} (including {@code hibernate.hbm2ddl.auto.<contributor>}, which Hibernate
     * prefers to {@code hibernate.hbm2ddl.auto}) and JPA's schema generation keys (Hibernate 7.4 still reads the
     * deprecated javax ones).
     */
    private static final List<String> RAW_SCHEMA_KEY_PREFIXES = List
            .of("hibernate.hbm2ddl.", "jakarta.persistence.schema-generation.", "javax.persistence.schema-generation.");

    /** Hibernate's legacy schema action key. */
    private static final String HBM2DDL_AUTO_KEY = "hibernate.hbm2ddl.auto";

    /** The root JPA schema actions, which Hibernate's global settings may set to none. */
    private static final Set<String> ROOT_ACTION_KEYS = Set
            .of("jakarta.persistence.schema-generation.database.action",
                    "jakarta.persistence.schema-generation.scripts.action",
                    "javax.persistence.schema-generation.database.action",
                    "javax.persistence.schema-generation.scripts.action");

    /**
     * The Hibernate and JPA keys, in lower case, that would make Hibernate connect through something other than the
     * store's pool, or as someone else, each with the store key it replaces. Hibernate prefers a URL or a data source
     * in the factory's settings to the persistence unit's DataSource, and logs the URL. A multi-tenant connection
     * provider, like a connection provider, is a class that decides where Hibernate gets its connections; Hibernate
     * 7.4's multi-tenant schema and credentials mappers choose the schema and the credentials (they have no jakarta or
     * javax spelling).
     */
    private static final Map<String, String> CONNECTION_KEYS = Map
            .ofEntries(Map.entry("hibernate.connection.url", "url"),
                    Map.entry("hibernate.multi_tenant_connection_provider", "url"),
                    Map.entry("hibernate.multi_tenant.schema_mapper", "url"),
                    Map.entry("hibernate.multi_tenant.credentials_mapper", "username"),
                    Map.entry("hibernate.connection.username", "username"),
                    Map.entry("hibernate.connection.user", "username"),
                    Map.entry("hibernate.connection.password", "password"),
                    Map.entry("hibernate.connection.driver_class", "driver-class-name"),
                    Map.entry("hibernate.connection.provider_class", "url"),
                    Map.entry("hibernate.connection.datasource", "url"),
                    Map.entry("jakarta.persistence.jtadatasource", "url"),
                    Map.entry("jakarta.persistence.nonjtadatasource", "url"),
                    Map.entry("javax.persistence.jtadatasource", "url"),
                    Map.entry("javax.persistence.nonjtadatasource", "url"));

    /**
     * The JPA connection families ({@code url}, {@code user}, {@code password} and {@code driver}): every key in them
     * is rejected, since JPA defines no tuning key there.
     */
    private static final List<String> CONNECTION_KEY_PREFIXES = List
            .of("jakarta.persistence.jdbc.", "javax.persistence.jdbc.");

    /** The store keys that the JPA connection families' keys replace, by the key's last part. */
    private static final Map<String, String> JPA_CONNECTION_KEYS = Map
            .of("url", "url", "user", "username", "password", "password", "driver", "driver-class-name");

    /**
     * The connection keys, in lower case, that Hibernate honours JVM-wide from its global settings (system properties
     * and a class-path {@code hibernate.properties}), each with the store key it replaces: a connection provider (also
     * a multi-tenant one), credentials, and the multi-tenant schema and credentials mappers.
     */
    private static final Map<String, String> GLOBAL_CONNECTION_KEYS = Map
            .ofEntries(Map.entry("hibernate.connection.provider_class", "url"),
                    Map.entry("hibernate.connection.username", "username"),
                    Map.entry("hibernate.connection.user", "username"),
                    Map.entry("hibernate.connection.password", "password"),
                    Map.entry("jakarta.persistence.jdbc.user", "username"),
                    Map.entry("jakarta.persistence.jdbc.password", "password"),
                    Map.entry("javax.persistence.jdbc.user", "username"),
                    Map.entry("javax.persistence.jdbc.password", "password"),
                    Map.entry("hibernate.multi_tenant_connection_provider", "url"),
                    Map.entry("hibernate.multi_tenant.schema_mapper", "url"),
                    Map.entry("hibernate.multi_tenant.credentials_mapper", "username"));

    private final ConfigurableListableBeanFactory beanFactory;

    private final Environment environment;

    private final ResourceLoader resourceLoader;

    private final StoreFactory storeFactory;

    private final Map<String, MetadataKeeper> metadataKeepers = new ConcurrentHashMap<>();

    /**
     * Creates the factory.
     *
     * @param beanFactory the bean factory, for the beans a store's JPA beans are built from, and for Hibernate to
     *        create entity listeners and converters as beans
     * @param environment the environment the store settings are bound from
     * @param resourceLoader the resource loader the store packages are scanned with
     * @param storeFactory the factory of the beans every store gets, for each store's vendor and settings
     */
    public JpaStoreFactory(ConfigurableListableBeanFactory beanFactory, Environment environment,
            ResourceLoader resourceLoader, StoreFactory storeFactory) {
        this.beanFactory = beanFactory;
        this.environment = environment;
        this.resourceLoader = resourceLoader;
        this.storeFactory = storeFactory;
    }

    /**
     * Scans a store's package, and the packages below it, for its entities, embeddables, mapped superclasses and
     * converters.
     *
     * @param store the store name
     * @return the store's managed types
     */
    public PersistenceManagedTypes managedTypes(String store) {
        return new PersistenceManagedTypesScanner(resourceLoader).scan(storeFactory.definition(store).basePackage());
    }

    /**
     * Builds a store's EntityManagerFactory, as the persistence unit named after the store, on its
     * {@code <prefix>DataSource} and {@code <prefix>PersistenceManagedTypes}, with {@link #vendorProperties} and the
     * mapping resource {@value #MAPPING_RESOURCE}, whose default entity listener Hibernate creates as a Spring bean,
     * followed by the store's {@code jpa.mapping-resources} (as Boot adds {@code spring.jpa.mapping-resources}).
     *
     * @param store the store name
     * @return the factory bean of the store's EntityManagerFactory
     */
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(String store) {
        JpaProperties jpa = jpaProperties(store);
        List<String> mappingResources = new ArrayList<>();
        mappingResources.add(MAPPING_RESOURCE);
        mappingResources.addAll(jpa.getMappingResources());
        HibernateJpaVendorAdapter adapter = vendorAdapter(jpa);
        Map<String, Object> properties = vendorProperties(store, jpa, adapter);
        DataSource dataSource = StoreBeans
                .dependency(beanFactory, store, DATA_SOURCE_SUFFIX, DataSource.class, ENTITY_MANAGER_FACTORY_SUFFIX);
        PersistenceManagedTypes managedTypes = StoreBeans
                .dependency(beanFactory, store, MANAGED_TYPES_SUFFIX, PersistenceManagedTypes.class,
                        ENTITY_MANAGER_FACTORY_SUFFIX);
        return new EntityManagerFactoryBuilder(adapter, ignored -> properties, null)
                .dataSource(dataSource)
                .managedTypes(managedTypes)
                .mappingResources(mappingResources.toArray(String[]::new))
                .persistenceUnit(store)
                .build();
    }

    /**
     * Builds a store's {@link JpaTransactionManager} on its {@code <prefix>EntityManagerFactory}, customized by Boot's
     * {@link TransactionManagerCustomizers} when there are any (such as {@code spring.transaction.default-timeout}).
     *
     * @param store the store name
     * @return the transaction manager
     */
    public JpaTransactionManager transactionManager(String store) {
        JpaTransactionManager transactionManager = new JpaTransactionManager(StoreBeans
                .dependency(beanFactory, store,
                        ENTITY_MANAGER_FACTORY_SUFFIX, EntityManagerFactory.class, TRANSACTION_MANAGER_SUFFIX));
        beanFactory
                .getBeanProvider(TransactionManagerCustomizers.class)
                .ifAvailable(customizers -> customizers.customize(transactionManager));
        return transactionManager;
    }

    /**
     * Builds a store's {@link JpaSchemaAction}, which runs its {@code ddl-auto} (see {@link #schemaAction(String,
     * SynapseDataStoreProperties, JpaProperties, HibernateProperties, boolean)}) on its
     * {@code <prefix>EntityManagerFactory} when the store starts.
     *
     * @param store the store name
     * @return the schema action
     * @throws SynapseConfigurationException when the store's {@code ddl-auto} or {@code jpa.properties} fail the guard,
     *         or the store sets {@code update} and the application defines the store's EntityManagerFactory
     */
    public JpaSchemaAction schemaActionStep(String store) {
        JpaProperties jpa = jpaProperties(store);
        SynapseDataStoreProperties properties = storeFactory.properties(store);
        String action = schemaAction(store, properties, jpa, hibernateProperties(store), flywayPresent());
        EntityManagerFactory entityManagerFactory = StoreBeans
                .dependency(beanFactory, store, ENTITY_MANAGER_FACTORY_SUFFIX,
                        EntityManagerFactory.class, SCHEMA_ACTION_SUFFIX);
        String locations = String.join(", ", StoreSettings.migrationLocations(store, properties.migrations()));
        MetadataKeeper metadata = metadataKeeper(store);
        String factory = StoreNames.beanPrefix(store) + ENTITY_MANAGER_FACTORY_SUFFIX;
        // Read from the bean definition: the keeper only holds the mapping model of the factory Synapse builds.
        if ("update".equals(action) && !isBuiltBySynapse(factory)) {
            throw new SynapseConfigurationException("The store '" + store
                    + "' sets ddl-auto to update, which Synapse runs only on the EntityManagerFactory it builds, and "
                    + "the application defines " + factory + ".",
                    "Remove the application's " + factory + " bean, or change the schema with a migration in "
                            + locations + ".");
        }
        return new JpaSchemaAction(store, action, entityManagerFactory, metadata, locations);
    }

    /**
     * Whether Synapse registered a store's EntityManagerFactory bean: its definition is made by this factory's
     * {@code entityManagerFactory} method, not one the application defines (even one that calls that method). Read
     * through {@link FactoryMethods}, so a definition AOT processing generated counts too.
     */
    private boolean isBuiltBySynapse(String beanName) {
        return FactoryMethods.isMadeBy(beanFactory, beanName, BEAN_NAME, ENTITY_MANAGER_FACTORY_METHOD);
    }

    /**
     * Decides a store's schema action, and guards it (no environment name is consulted):
     * <ul>
     * <li>a {@code hibernate.hbm2ddl.*} or {@code jakarta.persistence.schema-generation.*} key (or a deprecated
     * {@code javax.persistence.schema-generation.*} one), at the root or qualified with a contributor (such as
     * {@code .orm}), fails whatever its value, since it would choose or shape an action behind the guard: in
     * {@code jpa.properties}, and in Hibernate's global settings ({@code org.hibernate.cfg.Environment.getProperties()}:
     * system properties and a class-path {@code hibernate.properties}), which Hibernate merges into every factory's
     * settings and in which a contributor-qualified key would win over the root keys Synapse sets to none. In global
     * settings only, {@code none} is allowed for {@code hibernate.hbm2ddl.auto} (with or without a contributor) and the
     * root JPA database and scripts actions;</li>
     * <li>a connection key in {@code jpa.properties} fails whatever its value, naming the key, never its value: every
     * {@code jakarta.persistence.jdbc.*} and {@code javax.persistence.jdbc.*} key, the JPA data source keys, and
     * Hibernate's {@code hibernate.connection.url}, {@code username}, {@code user}, {@code password},
     * {@code driver_class}, {@code provider_class} and {@code datasource}. Hibernate would connect through them
     * instead of the store's pool, past the vendor and credential checks, and log the URL;</li>
     * <li>in Hibernate's global settings or the JVM's system properties, which Hibernate honours JVM-wide,
     * {@code hibernate.connection.provider_class}, {@code username}, {@code user} and {@code password}, and the JPA
     * {@code user} and {@code password} keys fail, naming the key, never its value;</li>
     * <li>{@code jpa.generate-ddl: true} fails, since Synapse never asks Hibernate to generate DDL;</li>
     * <li>{@code jpa.hibernate.ddl-auto} defaults to {@code validate} when Flyway is on the class path (even with the
     * store's migrations turned off), else {@code none};</li>
     * <li>a value other than Boot's {@code none}, {@code validate}, {@code update}, {@code create} and
     * {@code create-drop} fails;</li>
     * <li>{@code update}, {@code create} and {@code create-drop} fail unless the store sets
     * {@code allow-ddl-auto: true}.</li>
     * </ul>
     *
     * @param store the store name
     * @param properties the store's settings
     * @param jpa the store's JPA settings
     * @param hibernate the store's Hibernate settings
     * @param flywayPresent whether Flyway is on the class path
     * @return the schema action
     * @throws SynapseConfigurationException when a setting fails the guard
     */
    public static String schemaAction(String store, SynapseDataStoreProperties properties, JpaProperties jpa,
            HibernateProperties hibernate, boolean flywayPresent) {
        String prefix = prefix(store);
        // In sorted order, so the failure names the same key on every start.
        for (String key : new TreeSet<>(jpa.getProperties().keySet())) {
            if (isRawSchemaKey(key)) {
                throw new SynapseConfigurationException(
                        "The store '" + store + "' sets " + key + " in " + prefix + ".jpa.properties.",
                        "Use " + prefix + ".jpa.hibernate.ddl-auto instead.");
            }
            String storeKey = replacedConnectionKey(key);
            if (storeKey != null) {
                throw StoreKeyOverride.failure(store, prefix + ".jpa.properties." + key, storeKey);
            }
        }
        rejectGlobalSchemaKeys(store, org.hibernate.cfg.Environment.getProperties());
        rejectGlobalConnectionKeys(store, org.hibernate.cfg.Environment.getProperties(), System.getProperties());
        if (jpa.isGenerateDdl()) {
            throw new SynapseConfigurationException("The store '" + store + "' sets " + prefix
                    + ".jpa.generate-ddl, which Synapse doesn't use to change the schema.",
                    "Use " + prefix + ".jpa.hibernate.ddl-auto instead.");
        }
        String ddlAuto = hibernate.getDdlAuto();
        String action = ddlAuto != null ? ddlAuto : flywayPresent ? VALIDATE : NONE;
        if (!ACTIONS.contains(action)) {
            throw new SynapseConfigurationException("The store '" + store + "' sets ddl-auto to '" + action
                    + "', which is not one of none, validate, update, create, create-drop.",
                    "Use one of these values.");
        }
        if (SCHEMA_CHANGING_ACTIONS.contains(action) && !properties.allowDdlAuto()) {
            throw new SynapseConfigurationException(
                    "The store '" + store + "' sets ddl-auto to " + action + ", which changes the schema at startup.",
                    "Use migrations in db/migration/" + store + ", or allow it for one environment with " + prefix
                            + ".allow-ddl-auto: true in application-local.yaml or a test file.");
        }
        return action;
    }

    /**
     * Fails on the first key of {@link #GLOBAL_CONNECTION_KEYS}, in sorted order, in Hibernate's global settings or the
     * JVM's system properties (also those set after Hibernate read them). No value is ever put in the message.
     *
     * @param store the store whose factory is being built
     * @param sources Hibernate's global settings and the system properties
     */
    private static void rejectGlobalConnectionKeys(String store, Properties... sources) {
        Set<String> keys = new TreeSet<>();
        for (Properties source : sources) {
            keys.addAll(source.stringPropertyNames());
        }
        for (String key : keys) {
            String storeKey = GLOBAL_CONNECTION_KEYS.get(key.trim().toLowerCase(Locale.ROOT));
            if (storeKey != null) {
                throw new SynapseConfigurationException("Hibernate's global settings (system properties or "
                        + "hibernate.properties) set " + key + ", which would replace the " + storeKey
                        + " of the store '" + store + "'.",
                        "Remove " + key + " from the JVM's system properties and hibernate.properties, and set "
                                + prefix(store) + "." + storeKey + " instead.");
            }
        }
    }

    /**
     * Fails on the first schema key, in sorted order, in Hibernate's global settings. The one value allowed is
     * {@code none} for the keys whose {@code none} Synapse's own settings already say: see {@link #isNoneAllowed}. No
     * value is ever put in the message.
     *
     * @param store the store whose factory is being built
     * @param global Hibernate's global settings
     */
    private static void rejectGlobalSchemaKeys(String store, Properties global) {
        for (String key : new TreeSet<>(global.stringPropertyNames())) {
            if (isRawSchemaKey(key)
                    && !(isNoneAllowed(key) && NONE.equalsIgnoreCase(global.getProperty(key, "").trim()))) {
                throw new SynapseConfigurationException("Hibernate's global settings (system properties or "
                        + "hibernate.properties) set " + key + ", which would change the schema of the store '" + store
                        + "' behind its ddl-auto.",
                        "Remove " + key + " from the JVM's system properties and hibernate.properties, and use "
                                + prefix(store) + ".jpa.hibernate.ddl-auto instead.");
            }
        }
    }

    /**
     * Whether {@code none} is harmless for a key in Hibernate's global settings: {@code hibernate.hbm2ddl.auto}, with
     * or without a contributor, and the root JPA database and scripts actions (jakarta and javax). The factory's own
     * root actions override them, and when the store's action runs, Hibernate's schema tooling sets the root JPA
     * action, which it reads before any {@code hbm2ddl.auto} key (7.4.13 {@code SchemaManagementToolCoordinator}).
     * Never for a contributor-qualified JPA action: Hibernate prefers it to the root action the store's action sets,
     * so {@code none} would turn that action into a no-op.
     */
    private static boolean isNoneAllowed(String key) {
        String name = key.trim().toLowerCase(Locale.ROOT);
        return name.equals(HBM2DDL_AUTO_KEY)
                || (name.startsWith(HBM2DDL_AUTO_KEY + ".") && name.length() > HBM2DDL_AUTO_KEY.length() + 1)
                || ROOT_ACTION_KEYS.contains(name);
    }

    private static boolean isRawSchemaKey(String key) {
        String name = key.trim().toLowerCase(Locale.ROOT);
        return RAW_SCHEMA_KEY_PREFIXES.stream().anyMatch(name::startsWith);
    }

    /**
     * Returns the store key that a Hibernate or JPA connection key would replace (see {@link #CONNECTION_KEYS} and
     * {@link #CONNECTION_KEY_PREFIXES}), matched whatever the case, or {@code null} for any other key, such as
     * Hibernate's connection tuning keys ({@code hibernate.connection.provider_disables_autocommit},
     * {@code handling_mode}, {@code isolation}).
     */
    static @Nullable String replacedConnectionKey(String key) {
        String name = key.trim().toLowerCase(Locale.ROOT);
        for (String family : CONNECTION_KEY_PREFIXES) {
            if (name.startsWith(family)) {
                return JPA_CONNECTION_KEYS.getOrDefault(name.substring(family.length()), "url");
            }
        }
        return CONNECTION_KEYS.get(name);
    }

    /**
     * Returns the properties Hibernate receives for a store. They are Boot's (from {@code jpa.properties},
     * {@code jpa.hibernate}, the vendor adapter, and the store's naming strategies and Hibernate properties customizer,
     * chosen as the class description says), checked again by the guard once the customizer ran, plus:
     * <ul>
     * <li>{@code jakarta.persistence.database-product-name}: the vendor's product name, from which Hibernate resolves
     * the dialect; or {@code hibernate.dialect}, the vendor's dialect, for a vendor without one. Neither is set when
     * {@code jpa.database-platform}, {@code jpa.database}, or one of these keys in {@code jpa.properties} is;</li>
     * <li>{@code jakarta.persistence.database-product-version}: the store's {@code database-version}, else the
     * vendor's default, and {@code jakarta.persistence.database-major-version} and {@code -minor-version} parsed from
     * it, for Hibernate's schema tool;</li>
     * <li>{@code hibernate.boot.allow_jdbc_metadata_access=false}, so Hibernate never connects while it starts;</li>
     * <li>{@code hibernate.hbm2ddl.auto=none}, {@code jakarta.persistence.schema-generation.database.action=none} and
     * {@code jakarta.persistence.schema-generation.scripts.action=none}, so Hibernate never changes the schema while
     * the factory is built, whatever system properties or {@code hibernate.properties} say: the store's
     * {@link JpaSchemaAction} runs {@code ddl-auto} when the store starts;</li>
     * <li>{@code hibernate.hbm2ddl.halt_on_error=true} for {@code update}, {@code create} and {@code create-drop}, so a
     * failed DDL statement fails startup;</li>
     * <li>{@code hibernate.integrator_provider}: the store's {@link MetadataKeeper}, for {@code ddl-auto: update}.</li>
     * </ul>
     *
     * @param store the store name
     * @return the properties
     * @throws SynapseConfigurationException when the store's {@code ddl-auto} or {@code jpa.properties} fail the guard
     *         (see {@link #schemaAction(String, SynapseDataStoreProperties, JpaProperties, HibernateProperties,
     *         boolean)})
     */
    public Map<String, Object> vendorProperties(String store) {
        JpaProperties jpa = jpaProperties(store);
        return vendorProperties(store, jpa, vendorAdapter(jpa));
    }

    private Map<String, Object> vendorProperties(String store, JpaProperties jpa, HibernateJpaVendorAdapter adapter) {
        HibernateProperties hibernate = hibernateProperties(store);
        // The guard, before Hibernate sees any setting; the action itself runs in the store's JpaSchemaAction.
        String action = schemaAction(store, storeFactory.properties(store), jpa, hibernate, flywayPresent());
        // Spring adds the adapter's properties to those it is given, for any key they don't set.
        Map<String, Object> properties = new LinkedHashMap<>(adapter.getJpaPropertyMap());
        // Boot's own properties, as the customizers find them, so the guard can tell what they set.
        Map<String, Object> beforeCustomizers = new HashMap<>();
        List<HibernatePropertiesCustomizer> customizers = new ArrayList<>();
        customizers.add(beforeCustomizers::putAll);
        customizers.addAll(customizers(store));
        Map<String, Object> customized = hibernate
                .determineHibernateProperties(jpa.getProperties(),
                        new HibernateSettings().ddlAuto(() -> NONE).hibernatePropertiesCustomizers(customizers));
        // The guard again, on what the customizers set.
        rejectCustomizedKeys(store, beforeCustomizers, customized);
        properties.putAll(customized);
        DataStoreVendor vendor = storeFactory.vendor(store);
        // A dialect from jpa.database-platform, jpa.database or jpa.properties, or a product name from jpa.properties,
        // is the application's choice.
        if (!properties.containsKey(AvailableSettings.DIALECT)
                && !properties.containsKey(AvailableSettings.JAKARTA_HBM2DDL_DB_NAME)) {
            String productName = vendor.databaseProductName();
            if (StringUtils.hasText(productName)) {
                // Hibernate resolves the dialect from the name, as from a connection, and doesn't warn that it was set.
                properties.put(AvailableSettings.JAKARTA_HBM2DDL_DB_NAME, productName);
            } else {
                properties.put(AvailableSettings.DIALECT, vendor.hibernateDialect());
            }
        }
        String databaseVersion = storeFactory.properties(store).databaseVersion();
        String version = StringUtils.hasText(databaseVersion) ? databaseVersion : vendor.defaultDatabaseVersion();
        properties.put(AvailableSettings.JAKARTA_HBM2DDL_DB_VERSION, version);
        putMajorAndMinorVersion(properties, version);
        properties.put(AvailableSettings.ALLOW_METADATA_ON_BOOT, "false");
        // Hibernate must never run an action while the factory is built. Boot's determineHibernateProperties leaves
        // hbm2ddl.auto out for none, and Hibernate prefers the JPA keys, which it also reads from system properties
        // and hibernate.properties; the factory's own settings win over those.
        properties.put(AvailableSettings.HBM2DDL_AUTO, NONE);
        properties.put(AvailableSettings.JAKARTA_HBM2DDL_DATABASE_ACTION, NONE);
        properties.put(AvailableSettings.JAKARTA_HBM2DDL_SCRIPTS_ACTION, NONE);
        if (SCHEMA_CHANGING_ACTIONS.contains(action)) {
            // Hibernate reads it from the factory's settings when the store's JpaSchemaAction runs.
            properties.put(AvailableSettings.HBM2DDL_HALT_ON_ERROR, "true");
        }
        properties.put(JpaSettings.INTEGRATOR_PROVIDER, metadataKeeper(store));
        return properties;
    }

    /**
     * Returns the Hibernate customizers of a store, applied as Boot's JPA configuration applies them: the
     * {@link PhysicalNamingStrategy} and {@link ImplicitNamingStrategy} (which replace those of
     * {@code jpa.hibernate.naming}), then every {@link HibernatePropertiesCustomizer} that applies. Each naming
     * strategy is the store's own {@code <prefix>PhysicalNamingStrategy} or {@code <prefix>ImplicitNamingStrategy}
     * when it defines one, else the application's bean of that type for the store, chosen as for the JDBC stores
     * ({@link StoreBeans#applicationBean}): qualified with the store's name, else unqualified, the primary one among
     * several. The customizers are the unqualified ones, then those qualified with the store's name, then the store's
     * own {@code <prefix>HibernatePropertiesCustomizer}, each group in {@code @Order} order
     * ({@link StoreBeans#applicationBeans}): a later one replaces what an earlier one set, so the most specific wins.
     * A bean with another qualifier is never used.
     *
     * @throws SynapseConfigurationException when several naming strategies of a type apply, and not exactly one is
     *         primary
     */
    private List<HibernatePropertiesCustomizer> customizers(String store) {
        PhysicalNamingStrategy physical = storeBean(store, PhysicalNamingStrategy.class, PHYSICAL_NAMING_SUFFIX);
        ImplicitNamingStrategy implicit = storeBean(store, ImplicitNamingStrategy.class, IMPLICIT_NAMING_SUFFIX);
        List<HibernatePropertiesCustomizer> customizers = new ArrayList<>();
        if (physical != null || implicit != null) {
            customizers.add(properties -> {
                if (physical != null) {
                    properties.put(AvailableSettings.PHYSICAL_NAMING_STRATEGY, physical);
                }
                if (implicit != null) {
                    properties.put(AvailableSettings.IMPLICIT_NAMING_STRATEGY, implicit);
                }
            });
        }
        // A later customizer replaces what an earlier one set, so the most specific runs last.
        customizers
                .addAll(StoreBeans
                        .applicationBeans(beanFactory, store, HibernatePropertiesCustomizer.class, CUSTOMIZER_SUFFIX));
        if (beanFactory.containsBean(StoreNames.beanPrefix(store) + CUSTOMIZER_SUFFIX)) {
            customizers
                    .add(StoreBeans
                            .dependency(beanFactory, store, CUSTOMIZER_SUFFIX, HibernatePropertiesCustomizer.class,
                                    ENTITY_MANAGER_FACTORY_SUFFIX));
        }
        return customizers;
    }

    private <T> @Nullable T storeBean(String store, Class<T> type, String suffix) {
        if (beanFactory.containsBean(StoreNames.beanPrefix(store) + suffix)) {
            return StoreBeans.dependency(beanFactory, store, suffix, type, ENTITY_MANAGER_FACTORY_SUFFIX);
        }
        return StoreBeans.applicationBean(beanFactory, store, type, suffix);
    }

    /**
     * Runs the schema-key and connection-key guards again on the properties the customizers set (added, or gave
     * another value), so a {@link HibernatePropertiesCustomizer} can't set what {@code jpa.properties} may not. Boot's
     * own properties were checked before (such as the {@code hibernate.hbm2ddl.auto} it derives from
     * {@code ddl-auto}, which Synapse then sets to none). No value is ever put in the message.
     *
     * @param store the store name
     * @param before the properties before the customizers ran
     * @param after the properties after they ran
     */
    private static void rejectCustomizedKeys(String store, Map<String, Object> before, Map<String, Object> after) {
        String prefix = prefix(store);
        for (String key : new TreeSet<>(after.keySet())) {
            if (Objects.equals(before.get(key), after.get(key))) {
                continue;
            }
            if (isRawSchemaKey(key)) {
                throw new SynapseConfigurationException("A HibernatePropertiesCustomizer sets " + key
                        + " for the store '" + store + "', which would change its schema behind its ddl-auto.",
                        "Remove " + key + " from the customizer, and use " + prefix
                                + ".jpa.hibernate.ddl-auto instead.");
            }
            String storeKey = replacedConnectionKey(key);
            if (storeKey != null) {
                throw new SynapseConfigurationException("A HibernatePropertiesCustomizer sets " + key
                        + " for the store '" + store + "', which would replace its " + storeKey + ".",
                        "Remove " + key + " from the customizer, and set " + prefix + "." + storeKey + " instead.");
            }
        }
    }

    /**
     * Gives Hibernate the major and minor version as well, parsed from the version as Hibernate's own boot does
     * ({@code JdbcEnvironmentInitiator}). Hibernate's schema tool resolves its dialect again from the product name, and
     * reads only these two keys (7.4.13 {@code HibernateSchemaManagementTool.resolveJdbcContext}); without them, its
     * dialect has no version, and Hibernate warns that the version is unsupported (HHH000511). Like the product
     * version, they replace a key the application sets in {@code jpa.properties}; a version that isn't numeric sets
     * neither.
     *
     * @param properties the properties Hibernate receives
     * @param version the database version, such as {@code 2.4} or {@code 15}
     */
    static void putMajorAndMinorVersion(Map<String, Object> properties, String version) {
        String[] parts = StringUtils.delimitedListToStringArray(version.trim(), ".");
        String major;
        String minor;
        try {
            major = String.valueOf(Integer.parseInt(parts[0]));
            minor = parts.length > 1 ? String.valueOf(Integer.parseInt(parts[1])) : "0";
        } catch (NumberFormatException ex) {
            return;
        }
        properties.put(AvailableSettings.JAKARTA_HBM2DDL_DB_MAJOR_VERSION, major);
        properties.put(AvailableSettings.JAKARTA_HBM2DDL_DB_MINOR_VERSION, minor);
    }

    private MetadataKeeper metadataKeeper(String store) {
        return metadataKeepers.computeIfAbsent(store, MetadataKeeper::new);
    }

    private boolean flywayPresent() {
        return ClassUtils.isPresent(FLYWAY, resourceLoader.getClassLoader());
    }

    private HibernateProperties hibernateProperties(String store) {
        HibernateProperties hibernate = new HibernateProperties();
        Binder.get(environment).bind(prefix(store) + ".jpa.hibernate", Bindable.ofInstance(hibernate));
        return hibernate;
    }

    /**
     * Builds the vendor adapter as Boot's {@code JpaBaseConfiguration} does, except that it never generates DDL. It
     * gets the bean factory, so Hibernate creates entity listeners and attribute converters as Spring beans.
     */
    private HibernateJpaVendorAdapter vendorAdapter(JpaProperties jpa) {
        HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
        adapter.setShowSql(jpa.isShowSql());
        if (jpa.getDatabase() != null) {
            adapter.setDatabase(jpa.getDatabase());
        }
        if (jpa.getDatabasePlatform() != null) {
            adapter.setDatabasePlatform(jpa.getDatabasePlatform());
        }
        adapter.setGenerateDdl(false);
        adapter.setBeanFactory(beanFactory);
        beanFactory
                .getBeanProvider(SQLExceptionTranslator.class)
                .ifUnique(adapter.getJpaDialect()::setJdbcExceptionTranslator);
        return adapter;
    }

    /**
     * Binds a store's JPA settings. {@code jpa.open-in-view} and {@code jpa.bootstrap} bind onto Boot's type but no
     * store could honour them, so they fail instead of being silently ignored: open-in-view is Boot's web setting for
     * its single EntityManagerFactory, and Synapse builds each store's factory itself, before the store starts.
     */
    private JpaProperties jpaProperties(String store) {
        JpaProperties jpa = new JpaProperties();
        Binder binder = Binder.get(environment);
        String prefix = prefix(store) + ".jpa";
        binder.bind(prefix, Bindable.ofInstance(jpa));
        if (jpa.getOpenInView() != null) {
            throw new SynapseConfigurationException("The store '" + store + "' sets " + prefix
                    + ".open-in-view, but open-in-view is not a store setting.",
                    "Remove it. Boot's spring.jpa.open-in-view applies to a single or primary store, and Synapse "
                            + "turns it off by default.");
        }
        if (binder.bind(prefix + ".bootstrap", Bindable.of(String.class)).isBound()) {
            throw new SynapseConfigurationException("The store '" + store + "' sets " + prefix
                    + ".bootstrap, but Synapse builds each store's EntityManagerFactory itself, before the store "
                    + "starts.", "Remove it.");
        }
        return jpa;
    }

    private static String prefix(String store) {
        return StoreSettings.prefix(store);
    }
}
