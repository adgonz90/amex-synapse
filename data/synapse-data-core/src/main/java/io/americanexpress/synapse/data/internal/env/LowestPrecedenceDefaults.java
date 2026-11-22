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
package io.americanexpress.synapse.data.internal.env;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

/**
 * Changes Boot defaults at the lowest precedence: the data modules' environment post-processors add their defaults to
 * Boot's {@code defaultProperties} source, so any value the application sets wins.
 */
public final class LowestPrecedenceDefaults {

    /**
     * The order of the post-processors that add defaults: right after Boot's config data loader, which moves the
     * default properties to the end.
     */
    public static final int ORDER = ConfigDataEnvironmentPostProcessor.ORDER + 1;

    private LowestPrecedenceDefaults() {
    }

    /**
     * Adds defaults to the {@code defaultProperties} source, creating it at the end if needed. A key the application
     * already set as its own default keeps its value.
     *
     * @param environment the environment
     * @param defaults the defaults, by property name
     */
    public static void add(ConfigurableEnvironment environment, Map<String, Object> defaults) {
        // addOrMerge lets a new value replace an existing one, so skip each key the application set as its own default.
        MutablePropertySources sources = environment.getPropertySources();
        PropertySource<?> existing = sources.get(DefaultPropertiesPropertySource.NAME);
        Map<String, Object> added = new HashMap<>();
        defaults.forEach((key, value) -> {
            if (existing == null || !existing.containsProperty(key)) {
                added.put(key, value);
            }
        });
        if (!added.isEmpty()) {
            DefaultPropertiesPropertySource.addOrMerge(added, sources);
        }
    }
}
