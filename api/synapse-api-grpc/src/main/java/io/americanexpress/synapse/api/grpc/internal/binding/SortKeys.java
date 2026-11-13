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
package io.americanexpress.synapse.api.grpc.internal.binding;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.core.SynapseConfigurationException;

/**
 * A method's sort allow-list from {@code .sortable(keys)}: the names a caller may send in the request's
 * {@code order_by} field, each mapped to the {@code SortOrder.property} the operation receives. A constant's wire name
 * is its {@link Enum#name() name} in lower case ({@code PUBLISHED_YEAR} is {@code published_year}), the snake_case
 * field names of AIP-132; {@code @JsonProperty} is not read, as gRPC has no JSON names. The property is the constant's
 * name, as over REST, so a cursor's scope is the same on both protocols. Wire names are case-sensitive.
 */
public final class SortKeys {

    /** A wire name: lower-case letters, digits and {@code _}. */
    private static final Pattern WIRE_NAME = Pattern.compile("[a-z0-9_]+");

    private final Map<String, String> wireToProperty;

    private SortKeys(Map<String, String> wireToProperty) {
        this.wireToProperty = Collections.unmodifiableMap(wireToProperty);
    }

    /**
     * Returns the keys of an enum.
     *
     * @param keys the enum
     * @return the keys, in constant order
     * @throws SynapseConfigurationException when the enum has no constants, a constant's lower-case name is not a
     *         wire name (letters, digits and {@code _}), or two constants have the same lower-case name
     */
    public static SortKeys of(Class<? extends Enum<?>> keys) {
        String enumName = keys.getSimpleName();
        Enum<?>[] constants = keys.getEnumConstants();
        if (constants.length == 0) {
            throw new SynapseConfigurationException(".sortable(" + enumName + ".class) names no sort keys",
                    "Pass an enum with at least one constant, or remove .sortable(...) so that the method takes no "
                            + "order_by.");
        }
        Map<String, String> wireToProperty = new LinkedHashMap<>();
        for (Enum<?> constant : constants) {
            String wireName = constant.name().toLowerCase(Locale.ROOT);
            if (!WIRE_NAME.matcher(wireName).matches()) {
                throw new SynapseConfigurationException("the sort key " + enumName + "." + constant.name()
                        + " is not a valid order_by name: in lower case, a constant's name must be made of letters, "
                        + "digits and '_'", "Rename the constant, or sort by another enum.");
            }
            String previous = wireToProperty.putIfAbsent(wireName, constant.name());
            if (previous != null) {
                throw new SynapseConfigurationException("the sort keys " + enumName + "." + previous + " and "
                        + enumName + "." + constant.name() + " have the same order_by name " + wireName,
                        "Give each constant a name that differs from the others in lower case.");
            }
        }
        return new SortKeys(wireToProperty);
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

    /**
     * Returns every wire name mapped to the property the operation receives, in constant order.
     *
     * @return the mapping
     */
    public Map<String, String> wireToProperty() {
        return wireToProperty;
    }

    @Override
    public String toString() {
        return "SortKeys" + wireToProperty;
    }
}
