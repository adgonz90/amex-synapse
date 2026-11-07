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
package io.americanexpress.synapse.api.grpc.autoconfigure;

import java.util.HashMap;
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
 * Sets the Synapse defaults for two of Boot's gRPC server properties: {@code spring.grpc.server.inbound.metadata.max-size}
 * (16KB) and {@code spring.grpc.server.reflection.enabled} (false). The defaults are the lowest-precedence property
 * source, so any value the application sets wins.
 */
final class GrpcDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String METADATA_SIZE_PROPERTY = "spring.grpc.server.inbound.metadata.max-size";

    static final String REFLECTION_PROPERTY = "spring.grpc.server.reflection.enabled";

    private static final Map<String, Object> DEFAULTS = Map
            .of(METADATA_SIZE_PROPERTY, "16KB", REFLECTION_PROPERTY,
                    "false");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // addOrMerge lets the new value replace an existing one, so skip each key the application set its own default for.
        MutablePropertySources sources = environment.getPropertySources();
        PropertySource<?> defaults = sources.get(DefaultPropertiesPropertySource.NAME);
        Map<String, Object> missing = new HashMap<>();
        DEFAULTS.forEach((name, value) -> {
            if (defaults == null || !defaults.containsProperty(name)) {
                missing.put(name, value);
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
}
