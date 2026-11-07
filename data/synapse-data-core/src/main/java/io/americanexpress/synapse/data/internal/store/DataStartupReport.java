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

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.support.SpringFactoriesLoader;

import io.americanexpress.synapse.data.DataAccess;

/**
 * Reports the data stores once the application has started, on the logger {@code io.americanexpress.synapse.data}:
 * one INFO line with each store's pool size and the most connections one instance opens, and one WARN per ignored
 * Boot prefix or key that the application sets. Data-core's are {@code spring.datasource}, {@code spring.flyway} and
 * {@code spring.jdbc.template}; each access module adds its stores' ({@link StoreInfrastructure#ignoredKeys()}) when a
 * store uses it. A WARN names the keys, never their values; it reads every enumerable property source except Boot's
 * default properties, and gives each key to the most specific prefix or key it matches. A store whose
 * {@code <prefix>DataSource} is not a Hikari pool, such as one its store class replaces, is listed without a pool
 * size and left out of the budget. Nothing is reported when the service has no store.
 */
public final class DataStartupReport implements ApplicationListener<ApplicationStartedEvent> {

    private static final Logger logger = LoggerFactory.getLogger("io.americanexpress.synapse.data");

    /** The Boot keys that every store ignores. */
    private static final List<IgnoredKeys> IGNORED = List
            .of(IgnoredKeys.prefix("spring.datasource"), IgnoredKeys.prefix("spring.flyway"),
                    IgnoredKeys.prefix("spring.jdbc.template"));

    private final ApplicationContext context;

    /**
     * Creates the report.
     *
     * @param context the application context the stores are in; the report ignores other contexts' events
     */
    public DataStartupReport(ApplicationContext context) {
        this.context = context;
    }

    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        ConfigurableApplicationContext started = event.getApplicationContext();
        if (started != context) {
            return;
        }
        List<StoreDefinition> stores = List.copyOf(started.getBeansOfType(StoreDefinition.class).values());
        if (stores.isEmpty()) {
            return;
        }
        logger.info("{}", budget(started, stores));
        ConfigurableEnvironment environment = started.getEnvironment();
        keys(environment, ignored(started, stores)).forEach((ignored, keys) -> {
            // The value is only compared, never logged.
            if (!keys.isEmpty() && !ignored.isQuiet(environment.getProperty(ignored.name()))) {
                logger.warn("{}", ignored.warning(List.copyOf(keys)));
            }
        });
    }

    /** Returns data-core's ignored keys, then those of each access module that a store uses. */
    private static List<IgnoredKeys> ignored(ConfigurableApplicationContext context, List<StoreDefinition> stores) {
        Set<DataAccess> used = EnumSet.noneOf(DataAccess.class);
        stores.forEach(store -> used.add(store.access()));
        List<IgnoredKeys> ignored = new ArrayList<>(IGNORED);
        ClassLoader classLoader = context.getClassLoader();
        List<StoreInfrastructure> infrastructures = SpringFactoriesLoader
                .forDefaultResourceLocation(classLoader)
                .load(StoreInfrastructure.class);
        for (StoreInfrastructure infrastructure : StoreRegistrar.available(infrastructures, classLoader)) {
            if (used.contains(infrastructure.access())) {
                ignored.addAll(infrastructure.ignoredKeys());
            }
        }
        return ignored;
    }

    private static String budget(ApplicationContext context, List<StoreDefinition> stores) {
        List<String> entries = new ArrayList<>();
        List<String> uncounted = new ArrayList<>();
        int connections = 0;
        for (StoreDefinition store : stores) {
            Integer pool = poolSize(context, store);
            if (pool == null) {
                entries.add(store.name() + " (own DataSource)");
                uncounted.add(store.name());
            } else {
                entries.add(store.name() + " (pool " + pool + ")");
                connections += pool;
            }
        }
        StringBuilder line = new StringBuilder("Synapse data stores: ").append(String.join(", ", entries));
        if (uncounted.size() < stores.size()) {
            line.append("; at most ").append(connections).append(" connections per instance");
            if (!uncounted.isEmpty()) {
                line.append(", not counting ").append(String.join(", ", uncounted));
            }
        }
        return line.append('.').toString();
    }

    /**
     * Returns the most connections a store's pool opens, or {@code null} when its {@code <prefix>DataSource} is not a
     * Hikari pool and doesn't wrap one.
     */
    private static @Nullable Integer poolSize(ApplicationContext context, StoreDefinition store) {
        String name = store.beanName("DataSource");
        if (!context.containsBean(name)) {
            return null;
        }
        Object bean = context.getBean(name);
        if (bean instanceof HikariDataSource pool) {
            return pool.getMaximumPoolSize();
        }
        if (bean instanceof DataSource dataSource) {
            try {
                if (dataSource.isWrapperFor(HikariDataSource.class)) {
                    return dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
                }
            } catch (SQLException ex) {
                // Not a pool the report can read.
            }
        }
        return null;
    }

    /**
     * Returns, for each ignored prefix or key in order, the keys the application sets that it is the most specific
     * match for, each once in its canonical form, sorted. One walk of the property sources serves them all.
     */
    private static Map<IgnoredKeys, Set<String>> keys(ConfigurableEnvironment environment,
            List<IgnoredKeys> ignored) {
        Map<IgnoredKeys, ConfigurationPropertyName> names = new LinkedHashMap<>();
        Map<IgnoredKeys, Set<String>> keys = new LinkedHashMap<>();
        for (IgnoredKeys entry : ignored) {
            names.put(entry, ConfigurationPropertyName.of(entry.name()));
            keys.put(entry, new TreeSet<>());
        }
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (DefaultPropertiesPropertySource.NAME.equals(source.getName())) {
                continue;
            }
            if (ConfigurationPropertySource.from(source) instanceof IterableConfigurationPropertySource iterable) {
                for (ConfigurationPropertyName key : iterable) {
                    IgnoredKeys match = mostSpecific(names, key);
                    Set<String> matched = match == null ? null : keys.get(match);
                    if (matched != null) {
                        matched.add(key.toString());
                    }
                }
            }
        }
        return keys;
    }

    /** Returns the prefix or key with the most elements that is the key or one of its ancestors. */
    private static @Nullable IgnoredKeys mostSpecific(Map<IgnoredKeys, ConfigurationPropertyName> names,
            ConfigurationPropertyName key) {
        IgnoredKeys match = null;
        int elements = -1;
        for (Map.Entry<IgnoredKeys, ConfigurationPropertyName> entry : names.entrySet()) {
            ConfigurationPropertyName name = entry.getValue();
            if ((name.equals(key) || name.isAncestorOf(key)) && name.getNumberOfElements() > elements) {
                match = entry.getKey();
                elements = name.getNumberOfElements();
            }
        }
        return match;
    }
}
