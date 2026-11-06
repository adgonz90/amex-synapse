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
package io.americanexpress.synapse.core.internal.environment;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName.Form;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

import io.americanexpress.synapse.core.SynapseConfigurationException;

/**
 * Keeps the set of clients and stores fixed per build under AOT: AOT processing creates their beans at build time,
 * so an environment file may set values for a client or store, but may not add one. A name is the third element under
 * {@code synapse.client} (except {@code defaults}) or the fourth under {@code synapse.data.stores}, compared
 * canonically ({@code data-books} is {@code dataBooks}). Environment files are the module {@code config-<env>} files
 * and the service's {@code <name>-<env>} files; environment-independent files are the module {@code config} files and
 * the service's {@code <name>} files. Any other source, such as an environment variable, is ignored.
 */
public final class AotEnvironmentCheck {

    private static final ConfigurationPropertyName CLIENTS = ConfigurationPropertyName.of("synapse.client");

    private static final ConfigurationPropertyName CLIENT_DEFAULTS = ConfigurationPropertyName
            .of("synapse.client.defaults");

    private static final ConfigurationPropertyName STORES = ConfigurationPropertyName.of("synapse.data.stores");

    private AotEnvironmentCheck() {
    }

    /**
     * Fails when an environment file sets a client or store name that no environment-independent file declares.
     *
     * @param environment the environment, with the service's and the modules' files loaded
     * @param name the environment's name
     * @param configNames Boot's config names
     * @throws SynapseConfigurationException naming the first environment file, in priority order, that adds a name
     */
    public static void verify(ConfigurableEnvironment environment, String name, List<String> configNames) {
        Set<ConfigurationPropertyName> declared = new HashSet<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (isBaseFile(source.getName(), configNames)) {
                for (ConfigurationPropertyName key : keys(source)) {
                    ConfigurationPropertyName named = named(key);
                    if (named != null) {
                        declared.add(named);
                    }
                }
            }
        }
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (isEnvironmentFile(source.getName(), name, configNames)) {
                for (ConfigurationPropertyName key : keys(source)) {
                    ConfigurationPropertyName named = named(key);
                    // ConfigurationPropertyName's equals and hashCode are canonical, so data-books is dataBooks.
                    if (named != null && !declared.contains(named)) {
                        throw added(source, named);
                    }
                }
            }
        }
    }

    private static boolean isBaseFile(String sourceName, List<String> configNames) {
        if (sourceName.startsWith(ModuleConfigFiles.SOURCE_PREFIX)) {
            return !ModuleConfigFiles.isEnvironmentSource(sourceName);
        }
        return configNames.stream().anyMatch(configName -> ServiceConfigFiles.isBaseFile(sourceName, configName));
    }

    private static boolean isEnvironmentFile(String sourceName, String name, List<String> configNames) {
        if (sourceName.startsWith(ModuleConfigFiles.SOURCE_PREFIX)) {
            return ModuleConfigFiles.isEnvironmentSource(sourceName);
        }
        return configNames
                .stream()
                .anyMatch(configName -> ServiceConfigFiles.isEnvironmentFile(sourceName, configName, name));
    }

    private static Iterable<ConfigurationPropertyName> keys(PropertySource<?> source) {
        return ConfigurationPropertySource.from(source) instanceof IterableConfigurationPropertySource iterable
                ? iterable
                : List.of();
    }

    /** The client or store a key belongs to, such as {@code synapse.client.accounts}, or {@code null} for none. */
    private static @Nullable ConfigurationPropertyName named(ConfigurationPropertyName key) {
        if (CLIENTS.isAncestorOf(key)) {
            ConfigurationPropertyName client = key.chop(CLIENTS.getNumberOfElements() + 1);
            return client.equals(CLIENT_DEFAULTS) ? null : client;
        }
        if (STORES.isAncestorOf(key)) {
            return key.chop(STORES.getNumberOfElements() + 1);
        }
        return null;
    }

    private static SynapseConfigurationException added(PropertySource<?> source, ConfigurationPropertyName named) {
        boolean client = CLIENTS.isParentOf(named);
        String name = named.getLastElement(Form.ORIGINAL);
        String origin = ConfigSources.origin(source);
        String file = ConfigSources.label(source) + (origin != null ? " (" + origin + ")" : "");
        return new SynapseConfigurationException(
                file + " adds the " + (client ? "client" : "store") + " '" + name + "', which no "
                        + "environment-independent file declares. In AOT mode the set of clients and stores is fixed "
                        + "at build time.",
                "Declare " + (client ? CLIENTS : STORES) + "." + name + " in config.yaml or application.yaml, and set "
                        + "only its values per environment.");
    }
}
