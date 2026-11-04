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

import java.util.Collection;
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

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.internal.context.ContextBinder;

/**
 * Adds Synapse's baggage fields to Boot's baggage lists. Boot's OpenTelemetry bridge reads the remote and correlation
 * (MDC) baggage fields only from {@code management.tracing.baggage.remote-fields} and
 * {@code management.tracing.baggage.correlation.fields}, so Synapse merges the correlation id field and the context
 * attribute names bound under {@code synapse.api.rest.context.headers} and {@code synapse.api.grpc.context.metadata}
 * into those lists. The user's values come first and are kept; the merged lists are added as the highest-precedence
 * property source, {@value #PROPERTY_SOURCE_NAME}.
 * <p>
 * It fails startup when a context attribute is named {@code correlationId}, and when the correlation id header
 * ({@code synapse.correlation.header}) is, ignoring case, also a remote baggage field (the correlation id field, a
 * context attribute name or an entry of {@code management.tracing.baggage.remote-fields}) or a trace or baggage
 * propagation header ({@code baggage}, {@code traceparent}, {@code tracestate} or a B3 header). Edges hide those
 * headers from untrusted callers, while the correlation header is always accepted.
 */
final class BaggageFieldsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "synapseBaggageFields";

    private static final String REMOTE_FIELDS = "management.tracing.baggage.remote-fields";

    private static final String CORRELATION_HEADER = "synapse.correlation.header";

    private static final List<String> BAGGAGE_LISTS = List
            .of(REMOTE_FIELDS,
                    "management.tracing.baggage.correlation.fields");

    /** The W3C Baggage, W3C Trace Context and B3 headers, which the REST edge hides from untrusted callers. */
    private static final List<String> PROPAGATION_HEADERS = List
            .of("baggage", "traceparent", "tracestate", "b3", "X-B3-TraceId", "X-B3-SpanId", "X-B3-ParentSpanId",
                    "X-B3-Sampled", "X-B3-Flags");

    private static final List<String> ATTRIBUTE_MAPS = List
            .of("synapse.api.rest.context.headers",
                    "synapse.api.grpc.context.metadata");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().remove(PROPERTY_SOURCE_NAME);
        Binder binder = Binder.get(environment);
        String correlationHeader = binder
                .bind(CORRELATION_HEADER, String.class)
                .orElse(SynapseCorrelationProperties.DEFAULT_HEADER);
        if (correlationHeader.equalsIgnoreCase(ContextBinder.CORRELATION_BAGGAGE_FIELD)) {
            throw new SynapseConfigurationException("The correlation id header '" + correlationHeader + "' ("
                    + CORRELATION_HEADER + ") is also the name of the correlation id baggage field '"
                    + ContextBinder.CORRELATION_BAGGAGE_FIELD + "', which is read from the header of the same name.",
                    "Set " + CORRELATION_HEADER + " to another header, such as "
                            + SynapseCorrelationProperties.DEFAULT_HEADER + ".");
        }
        for (String propagation : PROPAGATION_HEADERS) {
            if (correlationHeader.equalsIgnoreCase(propagation)) {
                throw new SynapseConfigurationException("The correlation id header '" + correlationHeader + "' ("
                        + CORRELATION_HEADER + ") is also the trace or baggage propagation header '" + propagation
                        + "', which inbound edges hide from untrusted callers.",
                        "Set " + CORRELATION_HEADER + " to another header, such as "
                                + SynapseCorrelationProperties.DEFAULT_HEADER + ".");
            }
        }
        Set<String> synapseFields = new LinkedHashSet<>();
        synapseFields.add(ContextBinder.CORRELATION_BAGGAGE_FIELD);
        for (String attributes : ATTRIBUTE_MAPS) {
            Set<String> names = binder
                    .bind(attributes, Bindable.mapOf(String.class, String.class))
                    .orElse(Map.of())
                    .keySet();
            if (names.stream().anyMatch(ContextBinder.CORRELATION_BAGGAGE_FIELD::equalsIgnoreCase)) {
                throw new SynapseConfigurationException(
                        "The context attribute name '" + ContextBinder.CORRELATION_BAGGAGE_FIELD + "' under "
                                + attributes + " is reserved for the correlation id.",
                        "Rename the attribute; the correlation id header is set with synapse.correlation.header.");
            }
            rejectCorrelationHeader(correlationHeader, names, attributes);
            synapseFields.addAll(names);
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        for (String list : BAGGAGE_LISTS) {
            List<String> userFields = binder.bind(list, Bindable.listOf(String.class)).orElse(List.of());
            if (list.equals(REMOTE_FIELDS)) {
                rejectCorrelationHeader(correlationHeader, userFields, REMOTE_FIELDS);
            }
            Set<String> fields = new LinkedHashSet<>(userFields);
            fields.addAll(synapseFields);
            merged.put(list, String.join(",", fields));
        }
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, merged));
    }

    /**
     * Rejects a correlation header that is also a remote baggage field. Every remote field is read from the header of
     * the same name, and the edge hides those headers from untrusted callers, while the correlation header is
     * accepted whatever the trust setting.
     */
    private static void rejectCorrelationHeader(String correlationHeader, Collection<String> fields, String key) {
        for (String field : fields) {
            if (field.equalsIgnoreCase(correlationHeader)) {
                throw new SynapseConfigurationException("The correlation id header '" + correlationHeader + "' ("
                        + CORRELATION_HEADER + ") is also the baggage field '" + field + "' under " + key
                        + ". Baggage fields are read from the header of the same name, so the correlation header must "
                        + "not be one.",
                        "Rename one of them: set " + CORRELATION_HEADER
                                + " to another header, or rename the field under " + key + ".");
            }
        }
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
