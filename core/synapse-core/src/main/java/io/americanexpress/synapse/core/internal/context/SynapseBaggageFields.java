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
package io.americanexpress.synapse.core.internal.context;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;

/**
 * The Synapse baggage fields: the correlation id field and the context attribute names of every inbound edge. Core
 * merges them into Boot's baggage lists, and the edges keep their values away from caller-supplied baggage.
 */
public final class SynapseBaggageFields {

    /**
     * The properties that map context attribute names to inbound headers or metadata keys, one per edge: REST, then
     * gRPC.
     */
    public static final List<String> ATTRIBUTE_MAPS = List
            .of("synapse.api.rest.context.headers", "synapse.api.grpc.context.metadata");

    private SynapseBaggageFields() {
    }

    /**
     * Returns the Synapse baggage fields: {@value ContextBinder#CORRELATION_BAGGAGE_FIELD} first, then the attribute
     * names bound under each of {@link #ATTRIBUTE_MAPS}, in that order and without duplicates.
     *
     * @param binder the binder of the environment
     * @return the field names, in order
     */
    public static Set<String> of(Binder binder) {
        Set<String> fields = new LinkedHashSet<>();
        fields.add(ContextBinder.CORRELATION_BAGGAGE_FIELD);
        for (String attributes : ATTRIBUTE_MAPS) {
            fields.addAll(attributeNames(binder, attributes));
        }
        return fields;
    }

    /**
     * Returns the attribute names bound under one of {@link #ATTRIBUTE_MAPS}.
     *
     * @param binder the binder of the environment
     * @param attributes the property name of the map
     * @return the attribute names, empty when the map is unset
     */
    public static Set<String> attributeNames(Binder binder, String attributes) {
        return binder.bind(attributes, Bindable.mapOf(String.class, String.class)).orElse(Map.of()).keySet();
    }
}
