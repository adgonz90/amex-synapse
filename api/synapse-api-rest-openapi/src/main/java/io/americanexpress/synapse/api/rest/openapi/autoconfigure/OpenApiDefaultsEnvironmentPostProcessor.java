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
package io.americanexpress.synapse.api.rest.openapi.autoconfigure;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

/**
 * Sets the Synapse defaults for springdoc: the API docs off, in OpenAPI 3.1 when an environment turns them on
 * ({@code springdoc.api-docs.enabled=false}, {@code springdoc.api-docs.version=openapi_3_1}), and Swagger UI off
 * ({@code springdoc.swagger-ui.enabled=false}). The document describes every route to anyone who can reach the service,
 * so, like every exposure, it is off until an environment's file turns it on. The defaults are the lowest-precedence
 * property source, so any value the application sets wins.
 */
final class OpenApiDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** The defaults, by springdoc property. */
    static final Map<String, Object> DEFAULTS = defaults();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // addOrMerge lets a new value replace an existing one, so skip each key the application set its own default for.
        MutablePropertySources sources = environment.getPropertySources();
        PropertySource<?> existing = sources.get(DefaultPropertiesPropertySource.NAME);
        Map<String, Object> missing = new LinkedHashMap<>();
        DEFAULTS.forEach((key, value) -> {
            if (existing == null || !existing.containsProperty(key)) {
                missing.put(key, value);
            }
        });
        if (!missing.isEmpty()) {
            DefaultPropertiesPropertySource.addOrMerge(missing, sources);
        }
    }

    /**
     * Runs right after Boot's config data loader, which moves the default properties to the end of the sources.
     *
     * @return one after {@link ConfigDataEnvironmentPostProcessor#ORDER}
     */
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    private static Map<String, Object> defaults() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("springdoc.swagger-ui.enabled", "false");
        defaults.put("springdoc.api-docs.enabled", "false");
        defaults.put("springdoc.api-docs.version", "openapi_3_1");
        return Collections.unmodifiableMap(defaults);
    }
}
