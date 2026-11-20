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
package io.americanexpress.synapse.core.autoconfigure;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.ConfigurationPropertyState;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.util.ClassUtils;

/**
 * Sets the Synapse default for {@code management.endpoint.health.group.readiness.include} ({@code readinessState},
 * the one member of Boot's own readiness probe) when the application configures the readiness group. Setting any key
 * of the {@code readiness} group, such as an {@code additional-path} of {@code server:/health}, defines the group, and
 * a group without {@code include} has every indicator; with this default it keeps the probe's member, and Synapse's
 * data stores that opt in are added to it. The default is the lowest-precedence property source, so an
 * {@code include} the application sets, in any form (a list, indexed keys or a comma-separated value) and in any
 * file, replaces it.
 * <p>
 * Without a key of the group, nothing is set, so Boot keeps its own probe group, which never shows its components and
 * uses Boot's default status order and HTTP mapping. A key counts in any property source and in any form, an
 * environment variable such as {@code MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_ADDITIONALPATH} included, except
 * {@code include} itself. Keys added after environment post-processing (an {@code ApplicationContextInitializer}'s
 * source, {@code @DynamicPropertySource}) are not seen.
 * <p>
 * It is set only when Boot's probes are there to define {@code readinessState}: Boot's
 * {@code AvailabilityProbesAutoConfiguration} is on the class path, and {@code management.endpoint.health.probes.enabled}
 * is unset or {@code true}, as Boot's condition reads it. It runs after Synapse's module files are added, so a key
 * that a module's {@code META-INF/synapse/config.yaml} sets counts too.
 */
final class HealthDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String INCLUDE_PROPERTY = "management.endpoint.health.group.readiness.include";

    static final String PROBES_PROPERTY = "management.endpoint.health.probes.enabled";

    static final String PROBES_AUTO_CONFIGURATION = "org.springframework.boot.health.autoconfigure.actuate.endpoint."
            + "AvailabilityProbesAutoConfiguration";

    private static final ConfigurationPropertyName READINESS = ConfigurationPropertyName
            .of("management.endpoint.health.group.readiness");

    private static final ConfigurationPropertyName INCLUDE = ConfigurationPropertyName.of(INCLUDE_PROPERTY);

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!ClassUtils.isPresent(PROBES_AUTO_CONFIGURATION, application.getClassLoader())) {
            return;
        }
        // Boot's @ConditionalOnBooleanProperty(matchIfMissing = true): any value but "true", ignoring case, is off.
        String probes = environment.getProperty(PROBES_PROPERTY);
        if (probes != null && !"true".equalsIgnoreCase(probes)) {
            return;
        }
        if (!configuresTheGroup(environment)) {
            return;
        }
        // addOrMerge lets the new value replace an existing one, so skip it when the application set its own default,
        // in any form: a scalar within one source would win over indexed keys (include[0]).
        MutablePropertySources sources = environment.getPropertySources();
        PropertySource<?> defaults = sources.get(DefaultPropertiesPropertySource.NAME);
        if (defaults == null || !hasInclude(defaults)) {
            DefaultPropertiesPropertySource
                    .addOrMerge(new HashMap<>(Map.of(INCLUDE_PROPERTY, "readinessState")), sources);
        }
    }

    /**
     * Whether a source sets a key of the readiness group other than {@code include}: a key Boot binds into the group,
     * so the group is defined.
     */
    private static boolean configuresTheGroup(ConfigurableEnvironment environment) {
        for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
            if (source instanceof IterableConfigurationPropertySource iterable) {
                if (iterable
                        .stream()
                        .anyMatch(name -> READINESS.isAncestorOf(name)
                                && !INCLUDE.equals(name.chop(INCLUDE.getNumberOfElements())))) {
                    return true;
                }
            } else if (source.containsDescendantOf(READINESS) == ConfigurationPropertyState.PRESENT) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasInclude(PropertySource<?> defaults) {
        ConfigurationPropertySource source = ConfigurationPropertySource.from(defaults);
        return source != null && (source.getConfigurationProperty(INCLUDE) != null
                || source.containsDescendantOf(INCLUDE) == ConfigurationPropertyState.PRESENT);
    }

    /**
     * Runs after Boot's config data loader, which moves the default properties to the end of the sources, and after
     * {@link SynapseEnvironmentPostProcessor}, which adds the modules' configuration files.
     *
     * @return two after {@link ConfigDataEnvironmentPostProcessor#ORDER}
     */
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 2;
    }
}
