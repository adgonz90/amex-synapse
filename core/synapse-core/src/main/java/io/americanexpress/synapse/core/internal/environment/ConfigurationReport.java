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
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.RandomValuePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

/**
 * The startup configuration report: the environment, where it was set, and every property source, highest priority
 * first, by label and origin. It never prints a resolved property value; the first line names where the environment
 * was set. Labels and origins come from {@link ConfigSources}, which removes the credentials, query and fragment of
 * any URL.
 */
public final class ConfigurationReport {

    /** The spaces between the longest label that has an origin and the origins. */
    private static final int ORIGIN_GAP = 2;

    private ConfigurationReport() {
    }

    /**
     * Renders the report, one line per entry, separated by {@code \n}:
     *
     * <pre>
     * Synapse environment: e2 (spring.profiles.active=${ENV}, application.yaml)
     * Configuration, highest priority first:
     *   1. command line arguments
     *   2. application-e2.yaml  service-books-1.0.jar
     * </pre>
     *
     * Labels that have an origin are padded to the longest such label plus two spaces. Boot's
     * {@code configurationProperties} view, the {@code random} source, stub sources and enumerable sources without a
     * property are skipped.
     *
     * @param environment the environment, after startup
     * @param name the environment's name and where it was set
     * @return the report, without a trailing line break
     */
    public static String render(ConfigurableEnvironment environment, EnvironmentName name) {
        List<Entry> entries = new ArrayList<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!skipped(source)) {
                entries.add(new Entry(ConfigSources.label(source), ConfigSources.origin(source)));
            }
        }
        int width = 0;
        for (Entry entry : entries) {
            if (entry.origin() != null) {
                width = Math.max(width, entry.label().length() + ORIGIN_GAP);
            }
        }
        StringBuilder report = new StringBuilder()
                .append("Synapse environment: ")
                .append(name.name())
                .append(" (")
                .append(name.setBy())
                .append(")\nConfiguration, highest priority first:");
        int number = 1;
        for (Entry entry : entries) {
            report.append("\n  ").append(number++).append(". ").append(entry.label());
            String origin = entry.origin();
            if (origin != null) {
                report.repeat(' ', width - entry.label().length()).append(origin);
            }
        }
        return report.toString();
    }

    private static boolean skipped(PropertySource<?> source) {
        return ConfigurationPropertySources.isAttachedConfigurationPropertySource(source)
                || source instanceof RandomValuePropertySource || source instanceof PropertySource.StubPropertySource
                || source instanceof EnumerablePropertySource<?> enumerable
                        && enumerable.getPropertyNames().length == 0;
    }

    private record Entry(String label, @Nullable String origin) {
    }
}
