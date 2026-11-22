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

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.bind.DataObjectPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName.Form;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.UrlResource;

import io.americanexpress.synapse.core.SynapseConfigurationException;

/**
 * The configuration files that library modules ship: {@code META-INF/synapse/config.yaml} and
 * {@code META-INF/synapse/config-<env>.yaml} (also {@code .properties}), found with
 * {@link ClassLoader#getResources(String)} on those fixed names, one set per classpath root (a jar or a directory).
 */
public final class ModuleConfigFiles {

    /** The start of the name of every module source. */
    public static final String SOURCE_PREFIX = "Synapse module config ";

    private static final String DIRECTORY = "META-INF/synapse/";

    private static final String BASE_NAME = "config";

    private static final String DEFAULT_PROPERTIES = "defaultProperties";

    private static final List<ConfigurationPropertyName> FORBIDDEN = List
            .of(ConfigurationPropertyName.of("spring.profiles"), ConfigurationPropertyName.of("spring.config"),
                    ConfigurationPropertyName.of("synapse.environment"));

    /** The extensions of the service's own files, in Boot's order. */
    private static final List<String> SERVICE_EXTENSIONS = List.of("yaml", "yml", "properties");

    private static final PropertySourceLoader YAML = new YamlPropertySourceLoader();

    private static final PropertySourceLoader PROPERTIES = new PropertiesPropertySourceLoader();

    private ModuleConfigFiles() {
    }

    /**
     * Loads the module files for an environment, after checking that no module ships a root {@code <name>.*} or
     * {@code <name>-<env>.*} file or the same file in both formats, that no module file sets
     * {@code spring.profiles.*}, {@code spring.config.*} or {@code synapse.environment.*}, and that no two modules set
     * the same key. Only local resources are read (files and jar entries); any other URL fails startup and is never opened.
     *
     * @param classLoader the application's class loader
     * @param environment the environment's name
     * @param configNames Boot's config names, for the root file check
     * @return the sources, highest priority first: every module's {@code config-<env>} sources (classpath order),
     *         then every module's {@code config} sources; later documents of a file come before earlier ones, as in
     *         Boot
     * @throws SynapseConfigurationException when a check fails or a file can't be read
     */
    public static List<PropertySource<?>> load(ClassLoader classLoader, String environment, List<String> configNames) {
        List<String> environmentFiles = files(BASE_NAME + "-" + environment);
        List<String> baseFiles = files(BASE_NAME);
        // Roots in classpath order, as far as fixed-name lookups show it: every lookup returns its roots in classpath
        // order, and the base files come first because most modules ship a config.yaml.
        Map<String, Module> found = new LinkedHashMap<>();
        for (String file : concat(baseFiles, environmentFiles)) {
            String path = DIRECTORY + file;
            for (URL url : resources(classLoader, path)) {
                if (!ConfigSources.isLocal(url)) {
                    // Synapse's library code does no remote I/O, and the EPP can't log, so this fails startup.
                    throw new SynapseConfigurationException(
                            ConfigSources.origin(url, path) + " (" + file + ") is not a local file or jar, so Synapse "
                                    + "cannot read it.",
                            "Put the module on the classpath as a jar or directory.");
                }
                found
                        .computeIfAbsent(ConfigSources.root(url, path),
                                key -> new Module(ConfigSources.origin(url, path)))
                        .files()
                        .putIfAbsent(file, url);
            }
        }
        if (found.isEmpty()) {
            return List.of();
        }
        Map<String, Module> modules = withUniqueOrigins(found);
        rejectRootFiles(classLoader, modules, environment, configNames);
        for (Module module : modules.values()) {
            rejectBothFormats(module, environmentFiles);
            rejectBothFormats(module, baseFiles);
        }
        List<PropertySource<?>> environmentSources = new ArrayList<>();
        List<PropertySource<?>> baseSources = new ArrayList<>();
        Map<ConfigurationPropertyName, Owner> owners = new HashMap<>();
        for (Map.Entry<String, Module> entry : modules.entrySet()) {
            String origin = entry.getValue().origin();
            for (Map.Entry<String, URL> file : entry.getValue().files().entrySet()) {
                List<PropertySource<?>> documents = read(file.getKey(), file.getValue(), origin);
                for (PropertySource<?> document : documents) {
                    check(document, entry.getKey(), origin, owners);
                }
                (environmentFiles.contains(file.getKey()) ? environmentSources : baseSources).addAll(documents);
            }
        }
        return concat(environmentSources, baseSources);
    }

    /**
     * Adds module sources to an environment, after removing the module sources of an earlier run: below the lowest
     * {@code Config resource} source; without one, right above {@code defaultProperties}; without either, last.
     *
     * @param sources the environment's sources
     * @param moduleSources the module sources, highest priority first
     */
    public static void addTo(MutablePropertySources sources, List<PropertySource<?>> moduleSources) {
        for (String name : sources.stream().map(PropertySource::getName).toList()) {
            if (name.startsWith(SOURCE_PREFIX)) {
                sources.remove(name);
            }
        }
        String lowestConfigResource = null;
        for (PropertySource<?> source : sources) {
            if (source.getName().startsWith(ConfigSources.CONFIG_RESOURCE_PREFIX)) {
                lowestConfigResource = source.getName();
            }
        }
        String previous = lowestConfigResource;
        for (PropertySource<?> source : moduleSources) {
            if (previous != null) {
                sources.addAfter(previous, source);
                previous = source.getName();
            } else if (sources.contains(DEFAULT_PROPERTIES)) {
                sources.addBefore(DEFAULT_PROPERTIES, source);
            } else {
                sources.addLast(source);
            }
        }
    }

    /**
     * Tells whether a source was loaded from a module's {@code config-<env>} file.
     *
     * @param sourceName the source's name
     * @return whether it is a module environment source
     */
    public static boolean isEnvironmentSource(String sourceName) {
        return sourceName.startsWith(SOURCE_PREFIX + "[" + BASE_NAME + "-");
    }

    /** The module file names for a base name, in reading order: {@code .yaml}, then {@code .properties}. */
    private static List<String> files(String baseName) {
        return List.of(baseName + ".yaml", baseName + ".properties");
    }

    private static List<URL> resources(ClassLoader classLoader, String path) {
        try {
            return Collections.list(classLoader.getResources(path));
        } catch (IOException ex) {
            // The description names the file. The cause keeps only the failure's type and stack trace, never its
            // message, which can name a path.
            throw new SynapseConfigurationException("Synapse could not look up " + path + " on the classpath.",
                    "Check that the classpath's jars and directories are readable.", ConfigFileFailures.sanitized(ex));
        }
    }

    /** Fails when a root that ships module files also ships a {@code <name>.*} or {@code <name>-<env>.*} file. */
    private static void rejectRootFiles(ClassLoader classLoader, Map<String, Module> modules, String environment,
            List<String> configNames) {
        for (String configName : configNames) {
            for (String baseName : List.of(configName, configName + "-" + environment)) {
                for (String extension : SERVICE_EXTENSIONS) {
                    String file = baseName + "." + extension;
                    for (URL url : resources(classLoader, file)) {
                        Module module = modules.get(ConfigSources.root(url, file));
                        if (module != null) {
                            throw new SynapseConfigurationException(
                                    module.origin() + " ships " + DIRECTORY + " and also " + file
                                            + " at its root, which can hide the service's own file.",
                                    "Move the module's settings to " + DIRECTORY + "config.yaml or config-<env>.yaml "
                                            + "and remove " + file + ".");
                        }
                    }
                }
            }
        }
    }

    private static void rejectBothFormats(Module module, List<String> files) {
        if (module.files().keySet().containsAll(files)) {
            throw new SynapseConfigurationException(
                    module.origin() + " ships both " + files.get(0) + " and " + files.get(1) + ".",
                    "Keep one of them.");
        }
    }

    /**
     * The modules, each named by a unique origin. Two roots with the same origin, such as two jars with one file name
     * in different directories, are named by their full location instead, so their sources keep distinct names and
     * every message tells them apart.
     */
    private static Map<String, Module> withUniqueOrigins(Map<String, Module> found) {
        Map<String, Module> modules = new LinkedHashMap<>();
        for (Map.Entry<String, Module> entry : found.entrySet()) {
            Module module = entry.getValue();
            long sameOrigin = found.values().stream().filter(other -> other.origin().equals(module.origin())).count();
            modules
                    .put(entry.getKey(),
                            sameOrigin > 1
                                    ? new Module(ConfigSources.location(entry.getKey()), module.files())
                                    : module);
        }
        return modules;
    }

    /**
     * Reads a module file with Boot's loader for its extension. The connection doesn't use the JDK's jar cache, so no
     * jar file stays open after the read. In a multi-document file, Boot's loader numbers every document, from
     * {@code <name> (document #0)}.
     */
    private static List<PropertySource<?>> read(String file, URL url, String origin) {
        UrlResource resource = new UrlResource(url);
        resource.setUseCaches(false);
        PropertySourceLoader loader = file.endsWith(".yaml") ? YAML : PROPERTIES;
        List<PropertySource<?>> documents;
        try {
            documents = new ArrayList<>(loader.load(SOURCE_PREFIX + "[" + file + "] " + origin, resource));
        } catch (IOException | RuntimeException ex) {
            // The description names the file and quotes none of it. The parser's message quotes the file, so the cause
            // keeps only the failure's type, position and stack trace, never file content.
            throw new SynapseConfigurationException("Synapse could not read " + file + " (" + origin + ").",
                    "Check that the file is readable and valid YAML or properties.", ConfigFileFailures.sanitized(ex));
        }
        // Boot gives later documents of a file the higher priority.
        Collections.reverse(documents);
        return documents;
    }

    /** Fails on a forbidden key, or on a key that another module already sets. */
    private static void check(PropertySource<?> document, String root, String origin,
            Map<ConfigurationPropertyName, Owner> owners) {
        if (!(ConfigurationPropertySource.from(document) instanceof IterableConfigurationPropertySource source)) {
            return;
        }
        String file = ConfigSources.label(document);
        for (ConfigurationPropertyName name : source) {
            ConfigurationPropertyName key = beforeFirstNumericIndex(name);
            // The full name is compared, so spring[profiles].active is spring.profiles.active, as Boot binds it.
            for (ConfigurationPropertyName forbidden : FORBIDDEN) {
                if (forbidden.equals(name) || forbidden.isAncestorOf(name)) {
                    throw new SynapseConfigurationException(
                            origin + " (" + file + ") sets " + dashed(key) + ". Module files may not set "
                                    + "spring.profiles.*, spring.config.* or synapse.environment.*.",
                            "Remove it from the module; the service decides the run.");
                }
            }
            // ConfigurationPropertyName's equals and hashCode are canonical, so base-url and baseUrl are one key.
            Owner owner = owners.putIfAbsent(key, new Owner(dashed(key), root, origin, file));
            if (owner != null && !owner.root().equals(root)) {
                throw new SynapseConfigurationException(
                        owner.key() + " is set by two modules: " + owner.origin() + " (" + owner.file() + ") and "
                                + origin + " (" + file + ").",
                        "Set it in one module only. The service's application files can override any module value.");
            }
        }
    }

    /**
     * A name cut before its first numeric index, so {@code hosts[0]}, {@code hosts[1]} and a comma-separated
     * {@code hosts} are all {@code hosts}: Boot binds a list from one source only. Map keys, such as
     * {@code levels[com.a]}, are not cut: Boot merges maps across sources.
     */
    private static ConfigurationPropertyName beforeFirstNumericIndex(ConfigurationPropertyName name) {
        for (int i = 0; i < name.getNumberOfElements(); i++) {
            if (name.isNumericIndex(i)) {
                return name.chop(i);
            }
        }
        return name;
    }

    /**
     * A name in Boot's dashed form, as people write it: {@code baseUrl} is {@code base-url}. Indexed elements keep
     * their brackets, so the message shows the key as the file has it, such as {@code spring[profiles].active}.
     */
    private static String dashed(ConfigurationPropertyName name) {
        StringBuilder dashed = new StringBuilder();
        for (int i = 0; i < name.getNumberOfElements(); i++) {
            String element = name.getElement(i, Form.ORIGINAL);
            if (name.chop(i + 1).isLastElementIndexed()) {
                dashed.append('[').append(element).append(']');
            } else {
                if (!dashed.isEmpty()) {
                    dashed.append('.');
                }
                dashed.append(DataObjectPropertyName.toDashedForm(element));
            }
        }
        return dashed.toString();
    }

    private static <T> List<T> concat(List<? extends T> first, List<? extends T> second) {
        List<T> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    /** A classpath root that ships module files: its origin and its files by name, in reading order. */
    private record Module(String origin, Map<String, URL> files) {

        Module(String origin) {
            this(origin, new LinkedHashMap<>());
        }
    }

    /** The module that set a key first, and that module's spelling of the key in Boot's dashed form. */
    private record Owner(String key, String root, String origin, String file) {
    }
}
