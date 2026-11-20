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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.migration.JavaMigration;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.data.autoconfigure.SynapseDataStoreProperties;
import io.americanexpress.synapse.data.internal.store.StoreDefinition;
import io.americanexpress.synapse.data.internal.store.StoreKeyOverride;
import io.americanexpress.synapse.data.internal.store.StoreSettings;

/**
 * Migrates a store with Flyway, and builds each store's {@link Flyway}: on the store's own DataSource, from
 * {@code synapse.data.stores.<name>.migrations.locations} (default {@code classpath:db/migration/<name>}), with the
 * store's {@code migrations.flyway.*} settings passed to Flyway under its own names, and the application's Flyway beans
 * that apply to the store (see {@link FlywayStoreFactory#flyway(String)}). Building opens no connection.
 *
 * <p>
 * Flyway logs the database it migrates at INFO, as {@code Database: <url> (<product>)}, through its own logger
 * ({@code org.flywaydb.core.FlywayExecutor}), and the pool logs each connection it adds, which for some drivers
 * includes the URL and the user name. Flyway's redaction is partial, so Synapse doesn't rely on it: the logged URL shows
 * the host, the database and its other parameters; credential parameters are rejected at startup. Synapse itself never
 * logs the URL.
 */
public final class FlywayStoreMigration implements StoreMigration {

    /**
     * Flyway settings that would replace one of the store's own settings, by their name compared as Boot compares
     * names (lower case, without dashes or underscores), each with the store key it replaces: the connection, and the
     * locations, which are the store's {@code migrations.locations}.
     */
    private static final Map<String, String> STORE_KEYS = Map
            .of("url", "url", "user", "username", "password", "password", "locations", "migrations.locations");

    /**
     * Flyway's {@code initSql}, compared as {@link #STORE_KEYS} are: SQL run on each new connection, which could change
     * the store's role or schema.
     */
    private static final String INIT_SQL = "initsql";

    private static final String UNKNOWN_PROPERTY = "Unknown configuration propert";

    /** Flyway settings whose later name elements are names the user chose, such as a placeholder's. */
    private static final List<String> NAMED_ENTRIES = List.of("placeholders", "jdbcProperties");

    private final String store;

    private final Flyway flyway;

    /**
     * Creates the migration.
     *
     * @param store the store name
     * @param flyway the store's Flyway
     */
    public FlywayStoreMigration(String store, Flyway flyway) {
        this.store = store;
        this.flyway = flyway;
    }

    @Override
    public String store() {
        return store;
    }

    /**
     * Runs Flyway's {@code migrate}.
     *
     * @throws IllegalStateException when Flyway fails; Flyway's exception, which names the failed migration, is the
     *         cause
     */
    @Override
    public void migrate() {
        try {
            flyway.migrate();
        } catch (FlywayException ex) {
            throw new IllegalStateException("The migrations of the store '" + store + "' failed.", ex);
        }
    }

    /**
     * Builds a store's Flyway, without opening a connection, in this order:
     * <ol>
     * <li>the {@code migrations.flyway.*} keys: Flyway's own names without the {@code flyway.} prefix, in kebab case or
     * camel case ({@code baseline-on-migrate} becomes {@code flyway.baselineOnMigrate}); the names after
     * {@code placeholders.} and {@code jdbc-properties.} are kept as written;</li>
     * <li>the customizers, in the order given ({@code FlywayConfigurationCustomizer::customize} of each bean that
     * applies to the store), so that a customizer replaces what a key set;</li>
     * <li>the callbacks and Java migrations, added to those the keys and customizers set;</li>
     * <li>the store's DataSource and locations, last, so that nothing replaces them.</li>
     * </ol>
     *
     * @param store the store
     * @param dataSource the store's DataSource
     * @param properties the store's settings
     * @param classLoader the class loader Flyway loads migrations and plugins with
     * @param customizers the customizers; each one's {@code toString()} is the name of its bean, which a failure names
     * @param callbacks the callbacks
     * @param javaMigrations the Java migrations
     * @return the Flyway
     * @throws SynapseConfigurationException when a {@code migrations.flyway} key would replace the store's
     *         {@code url}, {@code username}, {@code password} or {@code migrations.locations}, sets {@code init-sql},
     *         two keys are spellings of one Flyway setting, or Flyway rejects a key or its value; the failure names
     *         keys, never values. Also when a customizer sets {@code initSql}, naming the customizer's bean
     */
    public static Flyway flyway(StoreDefinition store, DataSource dataSource, SynapseDataStoreProperties properties,
            ClassLoader classLoader, List<Consumer<FluentConfiguration>> customizers, List<Callback> callbacks,
            List<JavaMigration> javaMigrations) {
        SynapseDataStoreProperties.Migrations migrations = properties.migrations();
        Map<String, String> configuration = new LinkedHashMap<>();
        // Flyway's names back to the keys as written, to name them in failures.
        Map<String, String> writtenKeys = new TreeMap<>();
        new TreeMap<>(migrations.flyway()).forEach((key, value) -> {
            rejectStoreKeyOverride(store.name(), key);
            String name = "flyway." + flywayName(key);
            String other = writtenKeys.putIfAbsent(name, key);
            if (other != null) {
                throw new SynapseConfigurationException("The store '" + store.name() + "' sets "
                        + flywayKey(store.name(), other) + " and " + flywayKey(store.name(), key)
                        + ", which are the same Flyway setting.", "Remove one of them.");
            }
            configuration.put(name, value);
        });
        String[] storeLocations = StoreSettings.migrationLocations(store.name(), migrations).toArray(String[]::new);
        FluentConfiguration fluent = Flyway.configure(classLoader);
        try {
            fluent.configuration(configuration);
        } catch (FlywayException ex) {
            // Flyway's messages may contain a value (such as "Invalid value for flyway.x (...): <value>"), so neither
            // the message nor the exception is kept.
            throw rejected(store.name(), ex, writtenKeys);
        }
        for (Consumer<FluentConfiguration> customizer : customizers) {
            String initSql = fluent.getInitSql();
            customizer.accept(fluent);
            if (!Objects.equals(initSql, fluent.getInitSql())) {
                throw customizedInitSql(store.name(), String.valueOf(customizer));
            }
        }
        if (!callbacks.isEmpty()) {
            List<Callback> all = new ArrayList<>(Arrays.asList(fluent.getCallbacks()));
            all.addAll(callbacks);
            fluent.callbacks(all.toArray(Callback[]::new));
        }
        if (!javaMigrations.isEmpty()) {
            List<JavaMigration> all = new ArrayList<>(Arrays.asList(fluent.getJavaMigrations()));
            all.addAll(javaMigrations);
            fluent.javaMigrations(all.toArray(JavaMigration[]::new));
        }
        return fluent.dataSource(dataSource).locations(storeLocations).load();
    }

    /**
     * Describes a customizer that sets {@code initSql}, as {@code migrations.flyway.init-sql} is described: SQL on each
     * new connection could change the store's role or schema. It names the bean, never the SQL.
     */
    private static SynapseConfigurationException customizedInitSql(String store, String bean) {
        return new SynapseConfigurationException("The FlywayConfigurationCustomizer bean " + bean + " sets initSql "
                + "for the store '" + store + "', SQL that runs on each new connection and could change the role or "
                + "schema the store uses.",
                "Remove initSql from " + bean + ". Name the schema in " + StoreSettings.prefix(store) + ".url.");
    }

    /**
     * Fails when a key would replace one of the store's own settings: make Flyway connect somewhere else, or as
     * someone else, or migrate from elsewhere; or {@code init-sql}, whose SQL could change the store's role or
     * schema. The failure names the key, never its value.
     */
    private static void rejectStoreKeyOverride(String store, String key) {
        if (INIT_SQL.equals(uniform(key))) {
            throw StoreKeyOverride.initSql(store, flywayKey(store, key));
        }
        String storeKey = STORE_KEYS.get(uniform(key));
        if (storeKey != null) {
            throw StoreKeyOverride.failure(store, flywayKey(store, key), storeKey);
        }
    }

    /**
     * Turns a Flyway configuration failure into one that names the store's keys as written, without Flyway's message
     * and without a cause, since Flyway's message may contain a value:
     * <ul>
     * <li>names Flyway doesn't know: the first such key, which is how environment variable forms fail (Flyway's
     * camel-case names can't be written in one: {@code ..._FLYWAY_BASELINEONMIGRATE} binds as
     * {@code baselineonmigrate});</li>
     * <li>a failure that names one of the keys, such as an invalid value: the first such key;</li>
     * <li>any other failure: every key.</li>
     * </ul>
     *
     * @param store the store name
     * @param ex Flyway's failure
     * @param writtenKeys the keys as written, by Flyway's name ({@code flyway.baselineOnMigrate})
     * @return the failure to throw
     */
    static SynapseConfigurationException rejected(String store, FlywayException ex, Map<String, String> writtenKeys) {
        String message = Objects.requireNonNullElse(ex.getMessage(), "");
        if (message.startsWith(UNKNOWN_PROPERTY)) {
            // "Unknown configuration property: flyway.a. Please check ...", or "properties: flyway.a,flyway.b. ..."
            int start = message.indexOf(':') + 1;
            int end = message.indexOf(". Please", start);
            String names = end < 0 ? message.substring(start) : message.substring(start, end);
            SortedSet<String> unknown = new TreeSet<>();
            for (String name : names.split(",")) {
                String key = writtenKeys.get(name.trim());
                if (key != null) {
                    unknown.add(key);
                }
            }
            if (!unknown.isEmpty()) {
                return new SynapseConfigurationException(
                        "The store '" + store + "' sets " + flywayKey(store, unknown.first())
                                + ", which is not a Flyway setting.",
                        "Write Flyway settings with Flyway's own names, such as baseline-on-migrate, in a config file. "
                                + "Environment variable forms of Flyway keys are not supported.");
            }
        }
        SortedSet<String> named = new TreeSet<>();
        writtenKeys.forEach((name, key) -> {
            if (Pattern.compile(Pattern.quote(name) + "(?![A-Za-z0-9_.\\-])").matcher(message).find()) {
                named.add(key);
            }
        });
        if (!named.isEmpty()) {
            String key = flywayKey(store, named.first());
            return new SynapseConfigurationException(
                    "The store '" + store + "' sets " + key + " to a value Flyway rejects.",
                    "Fix the value of " + key + ". Flyway's message isn't shown, since it may contain the value.");
        }
        SortedSet<String> all = new TreeSet<>(writtenKeys.values());
        List<String> keys = all.stream().map(key -> flywayKey(store, key)).toList();
        return new SynapseConfigurationException(
                "Flyway rejects the migrations.flyway settings of the store '" + store + "': " + String.join(", ", keys)
                        + ".",
                "Check these settings against Flyway's documentation. Flyway's message isn't shown, since it may "
                        + "contain a value.");
    }

    private static String flywayKey(String store, String key) {
        return StoreSettings.prefix(store) + ".migrations.flyway." + key;
    }

    private static String uniform(String key) {
        return key.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
    }

    /**
     * Turns a key into Flyway's name: each dashed element into camel case, up to the end of a named entry's prefix.
     */
    private static String flywayName(String key) {
        String[] elements = key.split("\\.", -1);
        StringBuilder name = new StringBuilder(key.length());
        boolean asWritten = false;
        for (int i = 0; i < elements.length; i++) {
            if (i > 0) {
                name.append('.');
            }
            String element = asWritten ? elements[i] : camelCase(elements[i]);
            name.append(element);
            asWritten = asWritten || NAMED_ENTRIES.contains(element);
        }
        return name.toString();
    }

    private static String camelCase(String element) {
        StringBuilder camel = new StringBuilder(element.length());
        boolean upper = false;
        for (int i = 0; i < element.length(); i++) {
            char c = element.charAt(i);
            if (c == '-') {
                upper = camel.length() > 0;
            } else {
                camel.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return camel.toString();
    }
}
