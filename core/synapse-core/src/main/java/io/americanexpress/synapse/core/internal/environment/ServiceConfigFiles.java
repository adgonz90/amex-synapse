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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertyState;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import io.americanexpress.synapse.core.SynapseConfigurationException;

/**
 * The service's own configuration files: {@code <name>.yaml} and {@code <name>-<env>.yaml} (also {@code .yml} and
 * {@code .properties}), where {@code <name>} is Boot's {@code spring.config.name}.
 */
public final class ServiceConfigFiles {

    private static final String CONFIG_NAME = "spring.config.name";

    private static final String DEFAULT_CONFIG_NAME = "application";

    private static final String ON_PROFILE = "spring.config.activate.on-profile";

    private static final ConfigurationPropertyName ON_PROFILE_NAME = ConfigurationPropertyName.of(ON_PROFILE);

    /** The extensions Boot loads, in Boot's order. */
    private static final List<String> EXTENSIONS = List.of("yaml", "yml", "properties");

    /** Boot's default classpath locations, relative to the classpath root. */
    private static final List<String> CLASSPATH_DIRECTORIES = List.of("", "config/");

    private static final List<PropertySourceLoader> LOADERS = List
            .of(new PropertiesPropertySourceLoader(), new YamlPropertySourceLoader());

    private ServiceConfigFiles() {
    }

    /**
     * Returns Boot's config names.
     *
     * @param environment the environment
     * @return {@code spring.config.name} (values trimmed, blank values dropped), or {@code application} when it is not
     *         set
     */
    public static List<String> configNames(ConfigurableEnvironment environment) {
        List<String> names = new ArrayList<>();
        for (String value : Binder
                .get(environment)
                .bind(CONFIG_NAME, Bindable.listOf(String.class))
                .orElse(List.of())) {
            String name = value.trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names.isEmpty() ? List.of(DEFAULT_CONFIG_NAME) : names;
    }

    /**
     * Tells whether a source is a {@code Config resource} that Boot loaded from {@code <configName>-<env>.yaml},
     * {@code .yml} or {@code .properties}, in any location. The match is case-sensitive.
     *
     * @param sourceName the source's name
     * @param configName the config name
     * @param environment the environment
     * @return whether the source was loaded from the environment's file
     */
    public static boolean isEnvironmentFile(String sourceName, String configName, String environment) {
        return isConfigResource(sourceName, configName + "-" + environment);
    }

    /**
     * Tells whether a source is a {@code Config resource} that Boot loaded from {@code <configName>.yaml},
     * {@code .yml} or {@code .properties}, in any location. The match is case-sensitive.
     *
     * @param sourceName the source's name
     * @param configName the config name
     * @return whether the source was loaded from the base file
     */
    public static boolean isBaseFile(String sourceName, String configName) {
        return isConfigResource(sourceName, configName);
    }

    private static boolean isConfigResource(String sourceName, String baseName) {
        return sourceName.startsWith(ConfigSources.CONFIG_RESOURCE_PREFIX) && Pattern
                .compile("[\\[/\\\\]" + Pattern.quote(baseName + ".") + "(?:yaml|yml|properties)\\]")
                .matcher(sourceName)
                .find();
    }

    /**
     * Checks that the service has a file for the environment: Boot loaded {@code <configName>-<env>.*} from any
     * location, or the file is on the classpath ({@code classpath:/} or {@code classpath:/config/}), even empty.
     *
     * @param environment the environment, after Boot has loaded the config data
     * @param name the environment's name
     * @param classLoader the application's class loader
     * @throws SynapseConfigurationException when the service has no file for the environment
     */
    public static void requireEnvironmentFile(ConfigurableEnvironment environment, String name,
            ClassLoader classLoader) {
        List<String> configNames = configNames(environment);
        for (PropertySource<?> source : environment.getPropertySources()) {
            for (String configName : configNames) {
                if (isEnvironmentFile(source.getName(), configName, name)) {
                    return;
                }
            }
        }
        for (String configName : configNames) {
            if (!classpathFiles(classLoader, configName + "-" + name).isEmpty()) {
                return;
            }
        }
        String file = configNames.get(0) + "-" + name;
        throw new SynapseConfigurationException("The environment '" + name + "' has no file in the service: none of "
                + file + ".yaml, " + file + ".yml, " + file + ".properties was loaded or found on the classpath. "
                + "Environment names are case-sensitive.",
                "Add " + file + ".yaml to the service (it may be empty), or set the environment to the name of an "
                        + "existing file.");
    }

    /**
     * Fails when a config document sets {@code spring.config.activate.on-profile}. It re-reads, with Boot's loaders,
     * every local resource (a file or an entry of a local jar) behind a {@code Config resource} source and the
     * classpath {@code <configName>.*} and {@code <configName>-<env>.*} files, because Boot never adds an inactive
     * document to the environment. Remote resources, such as an {@code https:} import or a {@code jar:https:} entry,
     * are not fetched again.
     *
     * @param environment the environment, after Boot has loaded the config data
     * @param name the environment's name
     * @param classLoader the application's class loader
     * @throws SynapseConfigurationException naming the first document that sets the key, or a file that can't be read
     */
    public static void rejectOnProfileDocuments(ConfigurableEnvironment environment, String name,
            ClassLoader classLoader) {
        List<String> configNames = configNames(environment);
        Map<String, Resource> resources = new LinkedHashMap<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source.getName().startsWith(ConfigSources.CONFIG_RESOURCE_PREFIX)) {
                Resource resource = ConfigSources.resource(source);
                if (resource != null) {
                    addLocal(resources, resource);
                }
            }
        }
        for (String configName : configNames) {
            for (String baseName : List.of(configName, configName + "-" + name)) {
                for (String path : classpathFiles(classLoader, baseName)) {
                    // Read through the class loader, as Boot does, so no cached jar file stays open.
                    addLocal(resources, new ClassPathResource(path, classLoader));
                }
            }
        }
        for (Resource resource : resources.values()) {
            rejectOnProfile(resource, configNames);
        }
    }

    /** The paths of the files {@code <baseName>.{yaml,yml,properties}} at the classpath root and in {@code config/}. */
    private static List<String> classpathFiles(ClassLoader classLoader, String baseName) {
        List<String> files = new ArrayList<>();
        for (String directory : CLASSPATH_DIRECTORIES) {
            for (String extension : EXTENSIONS) {
                String path = directory + baseName + "." + extension;
                if (classLoader.getResource(path) != null) {
                    files.add(path);
                }
            }
        }
        return files;
    }

    /**
     * Adds a resource, once per URL, when it is local: a file or an entry of a local jar. Any other resource, such as
     * an {@code https:} import or an entry of a remote jar, is never fetched a second time: Synapse's library code does
     * no remote I/O.
     */
    private static void addLocal(Map<String, Resource> resources, Resource resource) {
        URL url;
        try {
            url = resource.getURL();
        } catch (IOException ex) {
            return;
        }
        if (ConfigSources.isLocal(url)) {
            resources.putIfAbsent(url.toExternalForm(), resource);
        }
    }

    private static void rejectOnProfile(Resource resource, List<String> configNames) {
        String fileName = resource.getFilename();
        if (fileName == null) {
            return;
        }
        PropertySourceLoader loader = loader(fileName);
        if (loader == null) {
            return;
        }
        List<PropertySource<?>> documents;
        try {
            documents = loader.load(fileName, resource);
        } catch (IOException | RuntimeException ex) {
            // The description names the file and quotes none of it. The parser's message quotes the file, so the cause
            // keeps only the failure's type, position and stack trace, never file content.
            throw new SynapseConfigurationException(
                    "Synapse could not read " + fileName + " to check it for " + ON_PROFILE + ".",
                    "Check that the file is readable and valid YAML or properties.", ConfigFileFailures.sanitized(ex));
        }
        for (PropertySource<?> document : documents) {
            ConfigurationPropertySource source = ConfigurationPropertySource.from(document);
            if (source != null && (source.getConfigurationProperty(ON_PROFILE_NAME) != null
                    || source.containsDescendantOf(ON_PROFILE_NAME) == ConfigurationPropertyState.PRESENT)) {
                throw new SynapseConfigurationException(document.getName() + " sets " + ON_PROFILE + ".",
                        "Move the document's settings to " + configName(fileName, configNames) + "-<env>.yaml. "
                                + "Synapse uses one file per environment and no on-profile documents.");
            }
        }
    }

    private static @Nullable PropertySourceLoader loader(String fileName) {
        String lowerCase = fileName.toLowerCase(Locale.ROOT);
        for (PropertySourceLoader loader : LOADERS) {
            for (String extension : loader.getFileExtensions()) {
                if (lowerCase.endsWith("." + extension)) {
                    return loader;
                }
            }
        }
        return null;
    }

    /**
     * The config name a file belongs to: the longest one its name starts with (so {@code app-extra.yaml} belongs to
     * {@code app-extra}, not {@code app}); for a file outside Boot's naming, its name without the extension.
     */
    private static String configName(String fileName, List<String> configNames) {
        String match = null;
        for (String configName : configNames) {
            if ((fileName.startsWith(configName + ".") || fileName.startsWith(configName + "-"))
                    && (match == null || configName.length() > match.length())) {
                match = configName;
            }
        }
        if (match != null) {
            return match;
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
