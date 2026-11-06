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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.ConfigurationPropertyState;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

import io.americanexpress.synapse.core.SynapseConfigurationException;

/**
 * The environment of a run: the one active profile.
 *
 * @param name the environment
 * @param setBy where it was set: {@code spring.profiles.active=<raw value>, <label of the highest source holding it>}
 *        (the raw value is the source's own, so {@code ${ENV}} stays as written), or
 *        {@code SpringApplication additional profiles}
 */
public record EnvironmentName(String name, String setBy) {

    private static final String ACTIVE = "spring.profiles.active";

    private static final String DEFAULT = "spring.profiles.default";

    private static final ConfigurationPropertyName ACTIVE_NAME = ConfigurationPropertyName.of(ACTIVE);

    private static final String ADDITIONAL_PROFILES = "SpringApplication additional profiles";

    private static final String PLACEHOLDER_PREFIX = "${";

    private static final char ESCAPE = '\\';

    private static final String NO_ENVIRONMENT_ACTION = "Set exactly one environment, for example ENV=e2 when "
            + "application.yaml maps spring.profiles.active: ${ENV}. Run locally with -Dspring-boot.run.profiles=local "
            + "or an IDE run configuration; Synapse's test slices activate test; other Spring Boot "
            + "tests declare @ActiveProfiles(\"test\").";

    private static final String SEVERAL_ACTION = "Keep one environment in spring.profiles.active and move feature "
            + "profiles to spring.profiles.include or a profile group.";

    /**
     * Resolves the environment: {@code spring.profiles.active} (placeholders resolved, values trimmed, blank values
     * dropped) followed by the application's additional profiles, without duplicates. {@code spring.profiles.default},
     * {@code spring.profiles.include} and profile groups never count. There is no default environment: the raw value
     * of {@code spring.profiles.active} in the highest source that holds it may not contain a placeholder with a
     * default, such as {@code ${ENV:local}}, even when the variable is set.
     *
     * @param environment the environment, after Boot has loaded the config data
     * @param additionalProfiles {@code SpringApplication.getAdditionalProfiles()}
     * @return the environment
     * @throws SynapseConfigurationException when {@code spring.profiles.active} falls back to a default, or there is
     *         no environment, or more than one
     */
    public static EnvironmentName resolve(ConfigurableEnvironment environment, Set<String> additionalProfiles) {
        Holder holder = holder(environment);
        if (holder != null && holder.raw() instanceof String raw) {
            String variable = variableWithDefault(raw);
            if (variable != null) {
                throw new SynapseConfigurationException(ACTIVE + " is '" + raw + "', which falls back to a default "
                        + "environment when " + variable + " has no value.",
                        "Remove the default, for example " + ACTIVE + ": ${" + variable + "}, and set " + variable
                                + " on the platform. Synapse uses no default environment.");
            }
        }
        Binder binder = Binder.get(environment);
        List<String> active = names(binder.bind(ACTIVE, Bindable.listOf(String.class)).orElse(List.of()));
        Set<String> names = new LinkedHashSet<>(active);
        names.addAll(names(additionalProfiles));
        if (names.isEmpty()) {
            List<String> defaults = names(binder.bind(DEFAULT, Bindable.listOf(String.class)).orElse(List.of()));
            String description = "No environment is set: spring.profiles.active has no value.";
            if (!defaults.isEmpty()) {
                description += " spring.profiles.default (" + String.join(", ", defaults) + ") does not count.";
            }
            throw new SynapseConfigurationException(description, NO_ENVIRONMENT_ACTION);
        }
        if (names.size() > 1) {
            throw new SynapseConfigurationException("Exactly one environment must be active, but there are "
                    + names.size() + ": " + String.join(", ", names) + ".", SEVERAL_ACTION);
        }
        String name = names.iterator().next();
        return new EnvironmentName(name, active.isEmpty() ? ADDITIONAL_PROFILES : setBy(holder, active));
    }

    /**
     * Returns the variable of the first placeholder in a raw value that has a default, such as {@code ENV} in
     * {@code ${ENV:local}}, {@code ${ENV:${FALLBACK}}} or {@code ${REGION}-${ENV:local}}. An escaped placeholder
     * ({@code \${…}}) and an unclosed one are not placeholders.
     *
     * @param raw a raw value, as written in its source
     * @return the variable, or {@code null} when no placeholder has a default
     */
    static @Nullable String variableWithDefault(String raw) {
        int start = raw.indexOf(PLACEHOLDER_PREFIX);
        while (start >= 0) {
            if (start > 0 && raw.charAt(start - 1) == ESCAPE) {
                start = raw.indexOf(PLACEHOLDER_PREFIX, start + 1);
                continue;
            }
            // Spring's placeholder parser also nests plain braces, so both count.
            int depth = 1;
            int separator = -1;
            int index = start + PLACEHOLDER_PREFIX.length();
            for (; index < raw.length() && depth > 0; index++) {
                char c = raw.charAt(index);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                } else if (c == ':' && depth == 1 && separator < 0) {
                    separator = index;
                }
            }
            if (depth > 0) {
                return null;
            }
            if (separator >= 0) {
                return raw.substring(start + PLACEHOLDER_PREFIX.length(), separator);
            }
            // A placeholder without a default may hold one in its name, such as ${${X:a}}.
            start = raw.indexOf(PLACEHOLDER_PREFIX, start + 1);
        }
        return null;
    }

    private static List<String> names(Collection<String> values) {
        List<String> names = new ArrayList<>();
        for (String value : values) {
            String name = value.trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Finds the highest source that holds {@code spring.profiles.active}, as the binder does, with its raw value. An
     * indexed list ({@code spring.profiles.active[0]}) has no single raw value.
     */
    private static @Nullable Holder holder(ConfigurableEnvironment environment) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (ConfigurationPropertySources.isAttachedConfigurationPropertySource(source)) {
                continue;
            }
            for (ConfigurationPropertySource configurationSource : ConfigurationPropertySources.from(source)) {
                if (configurationSource == null) {
                    continue;
                }
                ConfigurationProperty property = configurationSource.getConfigurationProperty(ACTIVE_NAME);
                if (property != null) {
                    return new Holder(property.getValue(), ConfigSources.label(source));
                }
                if (configurationSource.containsDescendantOf(ACTIVE_NAME) == ConfigurationPropertyState.PRESENT) {
                    return new Holder(null, ConfigSources.label(source));
                }
            }
        }
        return null;
    }

    /** Where {@code spring.profiles.active} was set: its raw value, or the bound names for an indexed list. */
    private static String setBy(@Nullable Holder holder, List<String> active) {
        if (holder == null) {
            return ACTIVE + "=" + String.join(",", active);
        }
        Object raw = holder.raw();
        return ACTIVE + "=" + (raw != null ? raw : String.join(",", active)) + ", " + holder.label();
    }

    /**
     * The highest source that holds {@code spring.profiles.active}.
     *
     * @param raw its raw value, or {@code null} for an indexed list
     * @param label the source's label
     */
    private record Holder(@Nullable Object raw, String label) {
    }
}
