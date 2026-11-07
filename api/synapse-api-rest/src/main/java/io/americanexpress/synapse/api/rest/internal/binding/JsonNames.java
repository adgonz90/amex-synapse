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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.DeserializationConfig;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.SettableBeanProperty;
import tools.jackson.databind.deser.ValueInstantiator;
import tools.jackson.databind.deser.bean.BeanDeserializerBase;
import tools.jackson.databind.deser.bean.BuilderBasedDeserializer;
import tools.jackson.databind.introspect.BeanPropertyDefinition;
import tools.jackson.databind.introspect.ClassIntrospector;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.util.IgnorePropertiesUtil;

/**
 * The JSON property names of a record's components, as the mapper reads them ({@link #of}) or writes them
 * ({@link #serialized}): its naming strategy applies, {@code @JsonProperty} wins, and a component Jackson never reads
 * from JSON ({@code @JsonIgnore}, {@code @JsonIgnoreProperties}, a configured ignoral or {@code READ_ONLY} access), or
 * never writes, is left out.
 */
public final class JsonNames {

    private JsonNames() {
    }

    /**
     * Maps each property the mapper writes for a type to its JSON property name, in the mapper's property order. For a
     * record the key is the component name.
     *
     * <p>
     * Class-level ignorals and inclusions ({@code @JsonIgnoreProperties} and {@code @JsonIncludeProperties} on the
     * class, or configured for it) and ignored property types ({@code @JsonIgnoreType}, or configured) leave a property
     * out too, as they do when Jackson writes the type.
     *
     * @param mapper the mapper
     * @param type the type
     * @return an unmodifiable map from internal property name to JSON property name
     */
    public static Map<String, String> serialized(JsonMapper mapper, Class<?> type) {
        Map<String, String> names = new LinkedHashMap<>();
        for (BeanPropertyDefinition property : written(mapper, type).properties()) {
            names.put(property.getInternalName(), property.getName());
        }
        return Collections.unmodifiableMap(names);
    }

    /**
     * Returns the properties the mapper may leave out when it writes a type: those whose effective value inclusion
     * (the property's {@code @JsonInclude}, the class's, a configured one, or the mapper's default) is neither
     * {@code ALWAYS} nor {@code NON_NULL}, so a non-null value can still be missing (for example {@code NON_EMPTY}).
     * {@code NON_ABSENT} counts only for reference types such as {@code Optional}; for other types it is
     * {@code NON_NULL}.
     *
     * @param mapper the mapper
     * @param type the type
     * @return the internal names (for a record, the component names) of the properties the mapper may leave out
     */
    public static Set<String> omittable(JsonMapper mapper, Class<?> type) {
        Written written = written(mapper, type);
        SerializationConfig config = mapper.serializationConfig();
        // As Jackson's PropertyBuilder merges them: mapper default, then per-class (annotation, then configured).
        JsonInclude.Value perType = JsonInclude.Value
                .merge(
                        written.description().findPropertyInclusion(JsonInclude.Value.empty()),
                        config.getDefaultPropertyInclusion(type, JsonInclude.Value.empty()));
        JsonInclude.Value defaults = JsonInclude.Value.merge(config.getDefaultPropertyInclusion(), perType);
        Set<String> omittable = new LinkedHashSet<>();
        for (BeanPropertyDefinition property : written.properties()) {
            JsonInclude.Value inclusion = config
                    .getDefaultInclusion(type, property.getRawPrimaryType(), defaults)
                    .withOverrides(property.findInclusion());
            boolean omitted = switch (inclusion.getValueInclusion()) {
                case ALWAYS, NON_NULL, USE_DEFAULTS -> false;
                // NON_ABSENT leaves out an empty reference type (Optional, AtomicReference); for any other type it
                // acts as NON_NULL.
                case NON_ABSENT -> property.getPrimaryType().isReferenceType();
                default -> true;
            };
            if (omitted) {
                omittable.add(property.getInternalName());
            }
        }
        return Collections.unmodifiableSet(omittable);
    }

    /** The properties Jackson writes for a type, with the introspection they come from. */
    private record Written(BeanDescription description, List<BeanPropertyDefinition> properties) {
    }

    private static Written written(JsonMapper mapper, Class<?> type) {
        SerializationConfig config = mapper.serializationConfig();
        JavaType javaType = mapper.constructType(type);
        ClassIntrospector introspector = config.classIntrospectorInstance();
        BeanDescription description = introspector
                .introspectForSerialization(javaType, introspector.introspectClassAnnotations(javaType));
        // Class-level ignorals, inclusions and ignored types are applied by the serializer factory, not by the
        // introspection.
        JsonIgnoreProperties.Value ignorals = config.getDefaultPropertyIgnorals(type, description.getClassInfo());
        Set<String> ignored = ignorals == null ? Set.of() : ignorals.findIgnoredForSerialization();
        JsonIncludeProperties.Value inclusions = config.getDefaultPropertyInclusions(type, description.getClassInfo());
        @Nullable
        Set<String> included = inclusions == null ? null : inclusions.getIncluded();
        List<BeanPropertyDefinition> properties = new ArrayList<>();
        for (BeanPropertyDefinition property : description.findProperties()) {
            // As the serializer factory does: a property without an accessor (WRITE_ONLY) is never written.
            if (property.getAccessor() != null
                    && !IgnorePropertiesUtil.shouldIgnore(property.getName(), ignored, included)
                    && !ignoredType(config, introspector, property.getRawPrimaryType())) {
                properties.add(property);
            }
        }
        return new Written(description, List.copyOf(properties));
    }

    /** Whether Jackson never writes properties of a type: {@code @JsonIgnoreType}, or configured as ignored. */
    private static boolean ignoredType(SerializationConfig config, ClassIntrospector introspector, Class<?> type) {
        Boolean ignored = config.getConfigOverride(type).getIsIgnoredType();
        if (ignored == null) {
            ignored = config
                    .getAnnotationIntrospector()
                    .isIgnorableType(config, introspector.introspectClassAnnotations(config.constructType(type)));
        }
        return Boolean.TRUE.equals(ignored);
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
     * Tells whether the mapper reads a record from a JSON object property by property, even when it reads none of its
     * components (they are all read-only, for example). It doesn't for a record with its own deserializer, one it reads
     * through a delegating creator ({@code @JsonValue}, or {@code @JsonCreator(mode = DELEGATING)} on a constructor or
     * a factory, even next to a properties creator), or one it reads with a builder
     * ({@code @JsonDeserialize(builder = ...)}).
     *
     * @param mapper the mapper
     * @param recordType the record type
     * @return {@code true} when {@link #of} names the components the mapper reads from an object
     */
    public static boolean readsByProperty(JsonMapper mapper, Class<?> recordType) {
        BeanDeserializerBase bean = beanDeserializer(mapper, mapper.constructType(recordType));
        if (bean == null || bean instanceof BuilderBasedDeserializer) {
            return false;
        }
        // A delegating creator leaves the creator properties empty, as all-read-only components do, and wins over a
        // properties creator: the instantiator tells them apart.
        ValueInstantiator instantiator = bean.getValueInstantiator();
        return !instantiator.canCreateUsingDelegate()
                && (instantiator.canCreateFromObjectWith() || instantiator.canCreateUsingDefault());
    }

    /** Jackson's deserializer for the type, when it is a bean deserializer; {@code null} for any other. */
    private static @Nullable BeanDeserializerBase beanDeserializer(JsonMapper mapper, JavaType type) {
        ValueDeserializer<Object> deserializer = mapper._deserializationContext().findRootValueDeserializer(type);
        return deserializer instanceof BeanDeserializerBase bean ? bean : null;
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
        BeanDeserializerBase bean = beanDeserializer(mapper, type);
        if (bean == null) {
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
