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
package io.americanexpress.synapse.logging.log4j2;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.util.ClassUtils;

import io.americanexpress.synapse.core.SynapseConfigurationException;

/**
 * Runs before Boot initializes logging. It fails startup when Logback is on the classpath (Boot would pick Logback
 * and silently ignore Log4j2) and makes the ECS structured format the default for console output. The default is
 * the lowest-precedence property source, so any value the application sets wins.
 */
final class Log4j2DefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String STRUCTURED_FORMAT_PROPERTY = "logging.structured.format.console";

    private static final String LOGBACK_CLASS = "ch.qos.logback.classic.LoggerContext";

    private final @Nullable ClassLoader classLoader;

    Log4j2DefaultsEnvironmentPostProcessor() {
        this(null);
    }

    Log4j2DefaultsEnvironmentPostProcessor(@Nullable ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        ClassLoader loader = classLoader != null ? classLoader : application.getClassLoader();
        if (loader == null) {
            loader = ClassUtils.getDefaultClassLoader();
        }
        if (isPresent(loader, LOGBACK_CLASS)) {
            throw new SynapseConfigurationException(
                    "synapse-logging-log4j2 is on the classpath together with Logback, so Spring Boot would use Logback and ignore Log4j2.",
                    "Exclude spring-boot-starter-logging (and any other Logback dependency) from the service module.");
        }
        // Boot keeps its default properties below every other source, so the ECS default lives there. addOrMerge
        // lets the new value replace an existing one, so skip it when the application set its own default.
        MutablePropertySources sources = environment.getPropertySources();
        PropertySource<?> defaults = sources.get(DefaultPropertiesPropertySource.NAME);
        if (defaults == null || !defaults.containsProperty(STRUCTURED_FORMAT_PROPERTY)) {
            DefaultPropertiesPropertySource
                    .addOrMerge(new HashMap<>(Map.of(STRUCTURED_FORMAT_PROPERTY, "ecs")), sources);
        }
    }

    private static boolean isPresent(@Nullable ClassLoader loader, String className) {
        try {
            if (loader == null) {
                Class.forName(className);
            } else {
                loader.loadClass(className);
            }
            return true;
        } catch (ClassNotFoundException | LinkageError ex) {
            return false;
        }
    }

    /**
     * Runs right after Boot's config data loader, which appends the application's config files and moves the default
     * properties to the end; this source is added after both, so it is the lowest precedence. It is still before
     * logging initializes, because Boot runs all post-processors in an earlier listener than the logging listener.
     *
     * @return one after {@link ConfigDataEnvironmentPostProcessor#ORDER}
     */
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
