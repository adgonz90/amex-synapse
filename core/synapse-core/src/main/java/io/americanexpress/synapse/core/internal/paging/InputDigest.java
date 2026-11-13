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
package io.americanexpress.synapse.core.internal.paging;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;
import org.springframework.util.ReflectionUtils;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.core.conditional.VersionCondition;
import io.americanexpress.synapse.core.paging.PageQuery;

/**
 * The digest of a request's filters, which a cursor token is bound to: the SHA-256, in lowercase hex, of the input
 * record written canonically as a JSON object keyed by component name, with the plugin's extra entries beside them.
 * It knows no protocol: every API plugin digests the same input alike.
 * <ul>
 * <li>The value is written with a canonical mapper of its own, never the application's: Jackson annotations, mix-ins,
 * modules and inclusion rules can't hide a component or make two different values look alike. Records are written as
 * objects of every component, maps with their keys sorted, sets as arrays sorted by each element's canonical JSON, an
 * {@code Optional} as its value or {@code null}, enums by name, and {@code java.time} values and UUIDs as ISO
 * text.</li>
 * <li>Every component of type {@code PageQuery} is left out, so the next page of the same request has the same digest
 * whatever its cursor, size or sort (the sort is bound separately).</li>
 * <li>Every component whose type is a {@code VersionCondition} is left out, by its type, whatever binds it (an
 * {@code If-Match} header, a gRPC {@code etag} field): a version condition is not a filter.</li>
 * <li>Every other component is included, including those a plugin fills from a header or the request context, so a
 * cursor can't be replayed by another principal or tenant; a component that changes on every request therefore makes
 * every cursor fail.</li>
 * <li>The extra entries are written beside the components. Each key starts with {@code @}, which no record component
 * name can, so an entry never takes a component's place. REST passes {@code "@version"} with a versioned route's
 * parsed version, so a cursor issued in one version is rejected in another.</li>
 * <li>A {@code PageQuery} input, or no input, with no extra entries has the digest of {@code {}}.</li>
 * </ul>
 * A plugin admits, on a route or method that pages, only components this digest can write (records, simple types, and
 * collections of them). A value it still can't write fails the request: it is never left out.
 */
public final class InputDigest {

    /** The first character of every extra entry's key, which no record component name can have. */
    private static final String EXTRA_PREFIX = "@";

    private final JsonMapper canonical;

    /**
     * Creates the digest with its canonical mapper.
     */
    public InputDigest() {
        this.canonical = JsonMapper
                .builder()
                .disable(MapperFeature.USE_ANNOTATIONS)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(SerializationFeature.INDENT_OUTPUT)
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
                .enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED)
                .changeDefaultPropertyInclusion(
                        value -> JsonInclude.Value.construct(JsonInclude.Include.ALWAYS, JsonInclude.Include.ALWAYS))
                .build();
    }

    /**
     * Returns the digest of the input's filters and the extra entries.
     *
     * @param input the bound input, or {@code null} for none; its {@code PageQuery} may carry the client's token or
     *     the raw cursor
     * @param extra the plugin's own entries, each keyed by a name that starts with {@code @}: REST passes
     *     {@code Map.of("@version", "<major.minor.patch>")} on a versioned route and {@code Map.of()} otherwise; gRPC
     *     passes {@code Map.of()}
     * @return the SHA-256 in lowercase hex
     * @throws IllegalArgumentException when an extra entry's key doesn't start with {@code @}
     * @throws tools.jackson.core.JacksonException when a component holds a value the canonical mapper can't write
     */
    public String of(@Nullable Object input, Map<String, ?> extra) {
        Map<String, @Nullable Object> filters = new TreeMap<>();
        extra.forEach((key, value) -> {
            if (!key.startsWith(EXTRA_PREFIX)) {
                throw new IllegalArgumentException("The key of an extra input digest entry must start with '"
                        + EXTRA_PREFIX + "', so it can't take a record component's place, but one was '" + key + "'");
            }
            filters.put(key, normalized(value));
        });
        if (input instanceof Record record && !(input instanceof PageQuery)) {
            for (RecordComponent component : record.getClass().getRecordComponents()) {
                if (component.getType() != PageQuery.class
                        && !VersionCondition.class.isAssignableFrom(component.getType())) {
                    filters.put(component.getName(), normalized(value(component, record)));
                }
            }
        }
        return sha256(canonical.writeValueAsBytes(filters));
    }

    /**
     * The value as plain, ordered data: records and maps as sorted maps, sets as sorted lists, other collections as
     * lists, {@code Optional} unwrapped, enums by name, and {@code java.time} values and UUIDs as text.
     */
    private @Nullable Object normalized(@Nullable Object value) {
        return switch (value) {
            case null -> null;
            case Optional<?> optional -> normalized(optional.orElse(null));
            case Enum<?> constant -> constant.name();
            case UUID uuid -> uuid.toString();
            case Record record -> {
                Map<String, @Nullable Object> components = new TreeMap<>();
                for (RecordComponent component : record.getClass().getRecordComponents()) {
                    components.put(component.getName(), normalized(value(component, record)));
                }
                yield components;
            }
            case Map<?, ?> map -> {
                Map<String, @Nullable Object> entries = new TreeMap<>();
                map.forEach((key, entry) -> entries.put(String.valueOf(key), normalized(entry)));
                yield entries;
            }
            case Set<?> set -> sorted(set);
            case Collection<?> collection -> {
                List<@Nullable Object> elements = new ArrayList<>(collection.size());
                collection.forEach(element -> elements.add(normalized(element)));
                yield elements;
            }
            default -> "java.time".equals(value.getClass().getPackageName()) ? value.toString() : value;
        };
    }

    /** The set's elements, normalized and sorted by their canonical JSON text, whatever the set's iteration order. */
    private List<@Nullable Object> sorted(Set<?> set) {
        List<Element> elements = new ArrayList<>(set.size());
        for (Object element : set) {
            Object normalized = normalized(element);
            elements.add(new Element(canonical.writeValueAsString(normalized), normalized));
        }
        elements.sort(Comparator.comparing(Element::json));
        List<@Nullable Object> values = new ArrayList<>(elements.size());
        elements.forEach(element -> values.add(element.value()));
        return values;
    }

    private static @Nullable Object value(RecordComponent component, Record record) {
        Method accessor = component.getAccessor();
        ReflectionUtils.makeAccessible(accessor);
        return ReflectionUtils.invokeMethod(accessor, record);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** A normalized set element and its canonical JSON text. */
    private record Element(String json, @Nullable Object value) {
    }
}
