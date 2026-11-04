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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import io.americanexpress.synapse.core.internal.context.ContextBinder;

/**
 * Adds Synapse's baggage fields to Boot's baggage lists. Boot's OpenTelemetry bridge reads the remote and correlation
 * (MDC) baggage fields only from {@code management.tracing.baggage.remote-fields} and
 * {@code management.tracing.baggage.correlation.fields}, so Synapse merges the correlation id field and the context
 * attribute names bound under {@code synapse.api.rest.context.headers} and {@code synapse.api.grpc.context.metadata}
 * into those lists. The user's values come first and are kept; the merged lists are added as the highest-precedence
 * property source, {@value #PROPERTY_SOURCE_NAME}.
 */
final class BaggageFieldsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "synapseBaggageFields";

    private static final List<String> BAGGAGE_LISTS = List
            .of("management.tracing.baggage.remote-fields",
                    "management.tracing.baggage.correlation.fields");

    private static final List<String> ATTRIBUTE_MAPS = List
            .of("synapse.api.rest.context.headers",
                    "synapse.api.grpc.context.metadata");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().remove(PROPERTY_SOURCE_NAME);
        Binder binder = Binder.get(environment);
        Set<String> synapseFields = new LinkedHashSet<>();
        synapseFields.add(ContextBinder.CORRELATION_BAGGAGE_FIELD);
        for (String attributes : ATTRIBUTE_MAPS) {
            synapseFields
                    .addAll(binder
                            .bind(attributes, Bindable.mapOf(String.class, String.class))
                            .orElse(Map.of())
                            .keySet());
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        for (String list : BAGGAGE_LISTS) {
            Set<String> fields = new LinkedHashSet<>(
                    binder.bind(list, Bindable.listOf(String.class)).orElse(List.of()));
            fields.addAll(synapseFields);
            merged.put(list, String.join(",", fields));
        }
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, merged));
    }

    /**
     * Runs last, after config data and other post-processors have contributed the user's values.
     *
     * @return {@link Ordered#LOWEST_PRECEDENCE}
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
