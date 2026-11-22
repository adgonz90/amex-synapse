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

import java.net.URL;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.BeanFactoryAnnotationUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.jdbc.autoconfigure.JdbcProperties;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SQLExceptionTranslator;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.data.autoconfigure.SynapseDataStoreProperties;
import io.americanexpress.synapse.data.internal.migration.StoreMigration;
import io.americanexpress.synapse.data.internal.migration.StoreMigrations;
import io.americanexpress.synapse.data.vendor.DataStoreVendor;

/**
 * Builds each store's beans; the store bean definitions name it as their factory and pass the store name. It builds
 * with Boot's own types ({@link DataSourceProperties}, {@link JdbcProperties.Template}) and binds the pool settings
 * the way Boot binds {@code spring.datasource.hikari.*}. Nothing here connects to a database.
 */
public final class StoreFactory {

    /** The factory's bean name. */
    public static final String BEAN_NAME = "synapseDataStoreFactory";

    /**
     * Hikari settings that would replace the store's own connection settings, each with the store key it replaces. A
     * {@code data-source-class-name} makes Hikari connect through that class instead of {@code jdbc-url}. Hikari never
     * reads a {@code data-source-jndi} while {@code jdbc-url} is set, which Synapse always sets, so that key would be
     * silently ignored. A {@code schema} or {@code catalog} would choose another schema or database than the URL
     * names (the schema goes in the URL).
     */
    private static final List<Map.Entry<String, String>> CONNECTION_KEYS = List
            .of(Map.entry("jdbc-url", "url"), Map.entry("username", "username"), Map.entry("password", "password"),
                    Map.entry("data-source-class-name", "url"), Map.entry("data-source-jndi", "url"),
                    Map.entry("schema", "url"), Map.entry("catalog", "url"));

    /** The Hikari setting with SQL that runs on each new connection. */
    private static final String INIT_SQL_KEY = "connection-init-sql";

    /** The Hikari setting with SQL that runs on each connection check. */
    private static final String TEST_QUERY_KEY = "connection-test-query";

    /**
     * Driver properties ({@code hikari.data-source-properties.*}, matched whatever the case) that the driver would use
     * instead of the store's connection settings, each with the store key it replaces.
     */
    private static final Map<String, String> DRIVER_CONNECTION_KEYS = Map
            .of("user", "username", "username", "username", "password", "password", "url", "url");

    private final ConfigurableListableBeanFactory beanFactory;

    private final Environment environment;

    private final ObjectProvider<DataStoreVendor> vendors;

    private final ObjectProvider<SQLExceptionTranslator> exceptionTranslators;

    /**
     * Creates the factory.
     *
     * @param beanFactory the bean factory, for the store definitions, qualified connection details and the beans a
     *        store's beans are built from
     * @param environment the environment the store settings are bound from
     * @param vendors the vendor modules
     * @param exceptionTranslators the SQL exception translator applied to the JDBC templates when there is exactly one
     */
    public StoreFactory(ConfigurableListableBeanFactory beanFactory, Environment environment,
            ObjectProvider<DataStoreVendor> vendors, ObjectProvider<SQLExceptionTranslator> exceptionTranslators) {
        this.beanFactory = beanFactory;
        this.environment = environment;
        this.vendors = vendors;
        this.exceptionTranslators = exceptionTranslators;
    }

    /**
     * Builds a store's pool, without opening a connection: from its qualified {@link JdbcConnectionDetails}, else its
     * {@code url}, {@code username} and {@code password}. The pool is named after the store, then
     * {@code synapse.data.stores.<store>.hikari.*} is bound onto it. An empty password, or qualified details without
     * one, reaches the pool as {@code ""} (Boot gives a database that isn't embedded {@code null}), so the driver never
     * looks for a password of its own.
     *
     * @param store the store name
     * @return the pool
     * @throws SynapseConfigurationException when a connection key is missing, no vendor supports the URL, the vendor
     *         rejects the URL, a {@code hikari} key would replace the store's connection settings, a driver property
     *         the vendor blocks is set, or a resource the vendor forbids is on the class path
     */
    public HikariDataSource dataSource(String store) {
        rejectConnectionOverrides(store);
        StoreConnectionValues connection = connection(store);
        DataStoreVendor vendor = vendor(store, connection.url());
        rejectBlockedDriverProperties(store, vendor);
        ClassLoader classLoader = beanFactory.getBeanClassLoader();
        rejectForbiddenResources(store, vendor,
                classLoader != null ? classLoader : Objects.requireNonNull(ClassUtils.getDefaultClassLoader()));
        DataSourceProperties properties = new DataSourceProperties();
        if (classLoader != null) {
            properties.setBeanClassLoader(classLoader);
        }
        properties.setName(store);
        properties.setUrl(connection.url());
        properties.setUsername(connection.username());
        properties.setPassword(connection.password());
        properties.setDriverClassName(connection.driverClassName());
        try {
            properties.afterPropertiesSet();
        } catch (Exception ex) {
            throw new IllegalStateException("Could not prepare the DataSource of the store '" + store + "'", ex);
        }
        HikariDataSource dataSource = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        if ("".equals(connection.password())) {
            // Boot turns an empty password into null for a database that isn't embedded, and a driver given no
            // password may read one of its own (PostgreSQL's .pgpass, a service file): the store's empty one wins.
            dataSource.setPassword("");
        }
        dataSource.setPoolName(store);
        Binder.get(environment).bind(prefix(store) + ".hikari", Bindable.ofInstance(dataSource));
        return dataSource;
    }

    /**
     * Builds a store's {@link JdbcTemplate} on its {@code <prefix>DataSource}, with
     * {@code synapse.data.stores.<store>.jdbc.template.*} applied as Boot applies {@code spring.jdbc.template.*}.
     *
     * @param store the store name
     * @return the template
     */
    public JdbcTemplate jdbcTemplate(String store) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(
                StoreBeans.dependency(beanFactory, store, "DataSource", DataSource.class, "JdbcTemplate"));
        JdbcProperties.Template template = new JdbcProperties.Template();
        Binder.get(environment).bind(prefix(store) + ".jdbc.template", Bindable.ofInstance(template));
        jdbcTemplate.setIgnoreWarnings(template.isIgnoreWarnings());
        jdbcTemplate.setFetchSize(template.getFetchSize());
        jdbcTemplate.setMaxRows(template.getMaxRows());
        Duration queryTimeout = template.getQueryTimeout();
        if (queryTimeout != null) {
            jdbcTemplate.setQueryTimeout((int) queryTimeout.toSeconds());
        }
        jdbcTemplate.setSkipResultsProcessing(template.isSkipResultsProcessing());
        jdbcTemplate.setSkipUndeclaredResults(template.isSkipUndeclaredResults());
        jdbcTemplate.setResultsMapCaseInsensitive(template.isResultsMapCaseInsensitive());
        exceptionTranslators.ifUnique(jdbcTemplate::setExceptionTranslator);
        return jdbcTemplate;
    }

    /**
     * Builds a store's {@link NamedParameterJdbcTemplate} on its {@code <prefix>JdbcTemplate}.
     *
     * @param store the store name
     * @return the template
     */
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(String store) {
        // JdbcOperations, so a store class may replace <prefix>JdbcTemplate with any implementation.
        return new NamedParameterJdbcTemplate(
                StoreBeans
                        .dependency(beanFactory, store, "JdbcTemplate", JdbcOperations.class,
                                "NamedParameterJdbcTemplate"));
    }

    /**
     * Builds a store's {@link JdbcClient} on its {@code <prefix>NamedParameterJdbcTemplate}.
     *
     * @param store the store name
     * @return the client
     */
    public JdbcClient jdbcClient(String store) {
        return JdbcClient
                .create(StoreBeans
                        .dependency(beanFactory, store, "NamedParameterJdbcTemplate", NamedParameterJdbcTemplate.class,
                                "JdbcClient"));
    }

    /**
     * Builds a store's {@link StoreLifecycle}: the store's migration (from the {@link StoreMigrations} bean, present
     * while a migration tool is on the class path), whether {@code synapse.data.stores.<store>.migrations.enabled}, and
     * the {@link StoreStartStep} beans. Nothing connects until the lifecycle starts.
     *
     * @param store the store name
     * @return the lifecycle
     * @throws SynapseConfigurationException when no migration tool is present, but the store's migrations are enabled
     *         and it sets {@code migrations.locations} or a {@code migrations.flyway} key
     */
    public StoreLifecycle lifecycle(String store) {
        SynapseDataStoreProperties.Migrations settings = properties(store).migrations();
        StoreMigrations migrations = beanFactory.getBeanProvider(StoreMigrations.class).getIfAvailable();
        if (migrations == null && settings.enabled()
                && (settings.locations() != null || !settings.flyway().isEmpty())) {
            throw new SynapseConfigurationException(
                    "The store '" + store + "' configures migrations, but no migration tool is on the class path.",
                    "Add org.flywaydb:flyway-core, or set " + prefix(store) + ".migrations.enabled: false.");
        }
        StoreMigration migration = migrations == null ? null : migrations.migration(store);
        return new StoreLifecycle(definition(store), migration, settings.enabled(),
                beanFactory.getBeanProvider(StoreStartStep.class));
    }

    /**
     * Returns the vendor that supports a store's URL: the first in order whose {@link DataStoreVendor#supports}
     * accepts it.
     *
     * @param store the store name
     * @return the vendor
     * @throws SynapseConfigurationException when a connection key is missing, or no vendor supports the URL
     */
    public DataStoreVendor vendor(String store) {
        return vendor(store, connection(store).url());
    }

    /**
     * Binds a store's settings.
     *
     * @param store the store name
     * @return the settings
     */
    public SynapseDataStoreProperties properties(String store) {
        return StoreSettings.bind(environment, store);
    }

    /**
     * Returns a store's definition, the bean {@code <prefix>StoreDefinition}.
     *
     * @param store the store name
     * @return the definition
     */
    public StoreDefinition definition(String store) {
        return beanFactory.getBean(StoreNames.beanPrefix(store) + StoreDefinition.BEAN_SUFFIX, StoreDefinition.class);
    }

    /**
     * Resolves where a store connects, from its qualified connection details or else its properties, and checks that a
     * vendor supports the URL. The one place that decides a store's connection.
     */
    StoreConnectionValues connection(String store) {
        JdbcConnectionDetails details = connectionDetails(store);
        if (details != null) {
            String url = details.getJdbcUrl();
            // The details derive the driver from the URL, so they are only asked once a vendor supports it.
            checkUrl(store, vendor(store, url), url);
            // Details without a password get the empty one, so the driver never looks for one of its own.
            return new StoreConnectionValues(url, details.getUsername(),
                    Objects.requireNonNullElse(details.getPassword(), ""), details.getDriverClassName());
        }
        SynapseDataStoreProperties properties = properties(store);
        String url = properties.url();
        if (!StringUtils.hasText(url)) {
            throw noUrl(store);
        }
        if (JdbcUrls.hasCredentials(url)) {
            throw new SynapseConfigurationException("The store '" + store + "' has credentials in " + prefix(store)
                    + ".url, and drivers and pools log the URL.",
                    "Remove them from the URL and set " + prefix(store) + ".username and " + prefix(store)
                            + ".password instead (from environment variables).");
        }
        String username = properties.username();
        if (!StringUtils.hasText(username)) {
            throw noCredential(store, "username");
        }
        String password = properties.password();
        if (password == null) {
            throw noCredential(store, "password");
        }
        checkUrl(store, vendor(store, url), url);
        return new StoreConnectionValues(url, username, password, properties.driverClassName());
    }

    /** Fails when the vendor finds a problem with the URL, such as that it names no database; never quotes it. */
    private static void checkUrl(String store, DataStoreVendor vendor, String url) {
        String problem = vendor.urlProblem(url);
        if (problem != null) {
            String form = vendor.urlForm();
            throw new SynapseConfigurationException("The store '" + store + "' has a url that " + problem + ".",
                    form != null
                            ? "Set " + prefix(store) + ".url in the form " + form + "."
                            : "Fix " + prefix(store) + ".url.");
        }
    }

    /**
     * Rejects the driver properties the vendor blocks ({@link DataStoreVendor#blockedDriverProperties()}), whatever
     * their case: they would choose where, as whom or in which schema the store connects, or hold a secret. The
     * failure names the key, never its value.
     */
    private void rejectBlockedDriverProperties(String store, DataStoreVendor vendor) {
        Set<String> blocked = new HashSet<>();
        vendor.blockedDriverProperties().forEach(name -> blocked.add(name.toLowerCase(Locale.ROOT)));
        String key = prefix(store) + ".hikari.data-source-properties";
        Map<String, Object> driverProperties = Binder
                .get(environment)
                .bind(key, Bindable.mapOf(String.class, Object.class))
                .orElse(Map.of());
        for (String driverKey : new TreeSet<>(driverProperties.keySet())) {
            if (blocked.contains(driverKey.toLowerCase(Locale.ROOT))) {
                throw new SynapseConfigurationException("The store '" + store + "' sets " + key + "." + driverKey
                        + ", a driver property that would change where, as whom or in which schema the store "
                        + "connects, or that holds a secret.",
                        "Remove it, name the host, port, database and schema in " + prefix(store) + ".url, and set "
                                + prefix(store) + ".username and " + prefix(store) + ".password.");
            }
        }
    }

    /**
     * Fails when a resource the vendor's driver reads behind the store's settings is on the class path
     * ({@link DataStoreVendor#forbiddenResources()}). The failure names where it is, never what it says.
     */
    private static void rejectForbiddenResources(String store, DataStoreVendor vendor, ClassLoader classLoader) {
        for (String resource : vendor.forbiddenResources()) {
            URL location = classLoader.getResource(resource);
            if (location != null) {
                throw new SynapseConfigurationException("The class path has " + resource + " (" + location
                        + "), which the " + vendor.name() + " driver reads behind the settings of the store '" + store
                        + "'.", "Remove it, and configure the store under " + prefix(store) + ".");
            }
        }
    }

    private DataStoreVendor vendor(String store, String url) {
        return vendors
                .orderedStream()
                .filter(vendor -> vendor.supports(url))
                .findFirst()
                .orElseThrow(() -> new SynapseConfigurationException(
                        "No Synapse vendor module supports the store '" + store + "' (" + JdbcUrls.scheme(url) + ").",
                        "Add the vendor module for this database, such as synapse-data-postgres."));
    }

    /**
     * Returns the {@link JdbcConnectionDetails} qualified with the store name. The lookup reads qualifiers, so it also
     * finds details that are not default candidates, such as those a test registers for one store.
     */
    private @Nullable JdbcConnectionDetails connectionDetails(String store) {
        Map<String, JdbcConnectionDetails> details = BeanFactoryAnnotationUtils
                .qualifiedBeansOfType(beanFactory, JdbcConnectionDetails.class, store);
        if (details.size() > 1) {
            throw new NoUniqueBeanDefinitionException(JdbcConnectionDetails.class, details.keySet());
        }
        return details.isEmpty() ? null : details.values().iterator().next();
    }

    /**
     * Rejects the {@code hikari.*} settings that would make the pool connect somewhere else, or as someone else, than
     * the store's {@code url}, {@code username} and {@code password} say: they would skip the vendor and missing-key
     * checks. {@code connection-init-sql} and {@code connection-test-query} are rejected too, since their SQL could
     * change the store's role or schema.
     * Other Hikari settings, including {@code credentials-provider-class-name} (for example for IAM tokens),
     * stay allowed. The failure names the key, never its value.
     */
    private void rejectConnectionOverrides(String store) {
        Binder binder = Binder.get(environment);
        String hikari = prefix(store) + ".hikari.";
        for (Map.Entry<String, String> connectionKey : CONNECTION_KEYS) {
            String key = hikari + connectionKey.getKey();
            if (binder.bind(key, Bindable.of(String.class)).isBound()) {
                throw StoreKeyOverride.failure(store, key, connectionKey.getValue());
            }
        }
        if (binder.bind(hikari + INIT_SQL_KEY, Bindable.of(String.class)).isBound()) {
            throw StoreKeyOverride.initSql(store, hikari + INIT_SQL_KEY);
        }
        if (binder.bind(hikari + TEST_QUERY_KEY, Bindable.of(String.class)).isBound()) {
            throw StoreKeyOverride.testQuery(store, hikari + TEST_QUERY_KEY);
        }
        Map<String, Object> driverProperties = binder
                .bind(hikari + "data-source-properties", Bindable.mapOf(String.class, Object.class))
                .orElse(Map.of());
        for (String driverKey : driverProperties.keySet()) {
            String storeKey = DRIVER_CONNECTION_KEYS.get(driverKey.toLowerCase(Locale.ROOT));
            if (storeKey != null) {
                throw StoreKeyOverride.failure(store, hikari + "data-source-properties." + driverKey, storeKey);
            }
        }
    }

    private static SynapseConfigurationException noUrl(String store) {
        return new SynapseConfigurationException("The store '" + store + "' has no url.",
                "Set " + prefix(store) + ".url in the data module's config-<env>.yaml. Keys that say where to "
                        + "connect have no default.");
    }

    private static SynapseConfigurationException noCredential(String store, String key) {
        return new SynapseConfigurationException("The store '" + store + "' has no " + key + ".",
                "Set " + prefix(store) + "." + key + ", from an environment variable such as "
                        + StoreNames.environmentVariable(store, key) + ".");
    }

    private static String prefix(String store) {
        return StoreSettings.prefix(store);
    }
}
