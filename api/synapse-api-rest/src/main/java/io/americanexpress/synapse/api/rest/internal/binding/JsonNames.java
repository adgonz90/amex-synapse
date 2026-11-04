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
package io.americanexpress.synapse.api.rest.internal.binding;

import java.lang.reflect.RecordComponent;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.DeserializationConfig;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.SettableBeanProperty;
import tools.jackson.databind.deser.bean.BeanDeserializerBase;
import tools.jackson.databind.introspect.BeanPropertyDefinition;
import tools.jackson.databind.introspect.ClassIntrospector;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON property names of a record's components, limited to the properties the mapper actually deserializes: its
 * naming strategy applies, {@code @JsonProperty} wins, and a component Jackson never reads from JSON
 * ({@code @JsonIgnore}, {@code @JsonIgnoreProperties}, a configured ignoral or {@code READ_ONLY} access) is left out.
 */
public final class JsonNames {

    private JsonNames() {
    }

    /**
     * Maps each component of a record that the mapper reads from JSON to its JSON property name, in component order.
     *
     * @param mapper the mapper
     * @param recordType the record type
     * @return an unmodifiable map from component name to JSON property name; empty when the record isn't read
     *         property by property (for example it has its own deserializer)
     */
    public static Map<String, String> of(JsonMapper mapper, Class<?> recordType) {
        Map<String, SettableBeanProperty> properties = creatorProperties(mapper, recordType);
        if (properties == null) {
            return Map.of();
        }
        Map<String, String> names = new LinkedHashMap<>();
        properties.forEach((component, property) -> names.put(component, property.getName()));
        return Collections.unmodifiableMap(names);
    }

    /**
     * Maps each component of a record that the mapper reads from JSON to the creator property Jackson reads it with,
     * in component order. The property carries the contextual deserializer for that component, so annotations on the
     * component ({@code @JsonFormat}, {@code @JsonDeserialize}) apply when it is used.
     *
     * <p>
     * The properties come from Jackson's own deserializer for the record: every ignoral and access rule is already
     * applied, so an ignored or read-only component has no creator property.
     *
     * @param mapper the mapper
     * @param recordType the record type
     * @return the properties by component name, or {@code null} when the record has a deserializer that doesn't read
     *         it property by property (for example {@code @JsonDeserialize(using = ...)} on the record)
     */
    static @Nullable Map<String, SettableBeanProperty> creatorProperties(JsonMapper mapper, Class<?> recordType) {
        JavaType type = mapper.constructType(recordType);
        ValueDeserializer<Object> deserializer = mapper._deserializationContext().findRootValueDeserializer(type);
        if (!(deserializer instanceof BeanDeserializerBase bean)) {
            return null;
        }
        // Aliases make the same property appear more than once; its primary name identifies it.
        Map<String, SettableBeanProperty> byName = new HashMap<>();
        bean.creatorProperties().forEachRemaining(property -> byName.put(property.getName(), property));
        DeserializationConfig config = mapper.deserializationConfig();
        ClassIntrospector introspector = config.classIntrospectorInstance();
        BeanDescription description = introspector
                .introspectForDeserialization(type,
                        introspector.introspectClassAnnotations(type));
        Map<String, String> jsonNameByComponent = new HashMap<>();
        for (BeanPropertyDefinition property : description.findProperties()) {
            jsonNameByComponent.put(property.getInternalName(), property.getName());
        }
        Map<String, SettableBeanProperty> properties = new LinkedHashMap<>();
        for (RecordComponent component : Objects.requireNonNull(recordType.getRecordComponents())) {
            String jsonName = jsonNameByComponent.get(component.getName());
            SettableBeanProperty property = jsonName == null ? null : byName.get(jsonName);
            if (property != null) {
                properties.put(component.getName(), property);
            }
        }
        return Collections.unmodifiableMap(properties);
    }
}
