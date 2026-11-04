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
package io.americanexpress.synapse.api.rest.internal.paging;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.core.SynapseConfigurationException;

/**
 * A route's sort allow-list: the wire names a caller may send in the sort parameter, each mapped to the
 * {@code SortOrder.property} the operation receives. Wire names are case-sensitive.
 */
public final class SortKeys {

    /** A wire name: letters, digits, {@code _} and {@code .} (a leading {@code -} on the wire means descending). */
    private static final Pattern WIRE_NAME = Pattern.compile("[A-Za-z0-9_.]+");

    private final Map<String, String> wireToProperty;

    private SortKeys(Map<String, String> wireToProperty) {
        this.wireToProperty = Collections.unmodifiableMap(wireToProperty);
    }

    /**
     * Returns the keys of an enum. A constant's wire name is the value of its {@code @JsonProperty} when it has one,
     * otherwise its name in lowerCamel ({@code PUBLISHED_AT} is {@code publishedAt}); the operation receives the
     * constant's {@link Enum#name()}.
     *
     * @param keys the enum
     * @return the keys, in constant order
     * @throws SynapseConfigurationException when the enum has no constants, a wire name is not valid, or two
     *         constants have the same wire name
     */
    public static SortKeys of(Class<? extends Enum<?>> keys) {
        Map<String, String> wireToProperty = new LinkedHashMap<>();
        for (Enum<?> constant : keys.getEnumConstants()) {
            add(wireToProperty, wireName(keys, constant), constant.name());
        }
        return checked(wireToProperty);
    }

    /**
     * Returns keys whose wire names are also the names the operation receives.
     *
     * @param names the keys
     * @return the keys, in the given order
     * @throws SynapseConfigurationException when there are no names, a name is not valid, or a name is given twice
     */
    public static SortKeys of(List<String> names) {
        Map<String, String> wireToProperty = new LinkedHashMap<>();
        for (String name : names) {
            add(wireToProperty, name, name);
        }
        return checked(wireToProperty);
    }

    /**
     * Returns every wire name mapped to the property the operation receives, in declaration order.
     *
     * @return the mapping, which iterates in declaration order
     */
    public Map<String, String> wireToProperty() {
        return wireToProperty;
    }

    /**
     * Returns the property for a wire name.
     *
     * @param wireName the name the caller sent
     * @return the property, or {@code null} when the name is not allowed
     */
    public @Nullable String property(String wireName) {
        return wireToProperty.get(wireName);
    }

    /**
     * Returns the allowed wire names, separated by {@code ", "}.
     *
     * @return the allowed names
     */
    public String allowed() {
        return String.join(", ", wireToProperty.keySet());
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof SortKeys keys && wireToProperty.equals(keys.wireToProperty);
    }

    @Override
    public int hashCode() {
        return wireToProperty.hashCode();
    }

    @Override
    public String toString() {
        return "SortKeys" + wireToProperty;
    }

    private static void add(Map<String, String> wireToProperty, String wireName, String property) {
        if (!WIRE_NAME.matcher(wireName).matches()) {
            throw new SynapseConfigurationException(
                    "the sort key '" + wireName + "' is not a valid wire name: use letters, digits, '_' and '.'",
                    "Rename the key in .sortable(...), or give the enum constant a @JsonProperty name made of "
                            + "letters, digits, '_' and '.'.");
        }
        String previous = wireToProperty.putIfAbsent(wireName, property);
        if (previous != null) {
            throw new SynapseConfigurationException(
                    "two sort keys have the wire name " + wireName + ": " + previous + " and " + property,
                    "Give each sort key its own wire name.");
        }
    }

    private static SortKeys checked(Map<String, String> wireToProperty) {
        if (wireToProperty.isEmpty()) {
            throw new SynapseConfigurationException(".sortable(...) names no sort keys",
                    "Pass at least one key to .sortable, or remove it so that the route takes no sort parameter.");
        }
        return new SortKeys(wireToProperty);
    }

    private static String wireName(Class<? extends Enum<?>> keys, Enum<?> constant) {
        JsonProperty property;
        try {
            property = keys.getField(constant.name()).getAnnotation(JsonProperty.class);
        } catch (NoSuchFieldException ex) {
            throw new IllegalStateException("No field for the enum constant " + constant.name(), ex);
        }
        if (property != null && !property.value().isEmpty()) {
            return property.value();
        }
        return lowerCamel(constant.name());
    }

    /**
     * Converts a constant name to lowerCamel: {@code PUBLISHED_AT} is {@code publishedAt}. A part that has a
     * lower-case letter keeps its case, so {@code publishedAt} stays {@code publishedAt}.
     */
    private static String lowerCamel(String name) {
        StringBuilder camel = new StringBuilder(name.length());
        for (String part : name.split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            String word = part.chars().anyMatch(Character::isLowerCase) ? part : part.toLowerCase(Locale.ROOT);
            char first = word.charAt(0);
            camel.append(camel.isEmpty() ? Character.toLowerCase(first) : Character.toUpperCase(first));
            camel.append(word, 1, word.length());
        }
        return camel.toString();
    }
}
