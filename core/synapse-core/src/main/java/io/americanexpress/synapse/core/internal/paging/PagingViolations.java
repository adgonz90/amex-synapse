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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.americanexpress.synapse.core.paging.SortOrder;

/**
 * The texts of the paging violations, and the checks of a sort's keys, which every API plugin shares so that the
 * protocols report the same problems in the same words. Each plugin reads its own wire format (REST's {@code sort}
 * parameter, gRPC's {@code order_by} field) and names the parameter or field.
 */
public final class PagingViolations {

    /** A value the caller may not send. */
    public static final String INVALID = "is not a valid value";

    /** A sort on a route or method that declares no sort keys. */
    public static final String NOT_SUPPORTED = "is not supported";

    private PagingViolations() {
    }

    /**
     * Returns the text of a value under its minimum.
     *
     * @param min the least value allowed
     * @return the text
     */
    public static String atLeast(long min) {
        return "must be greater than or equal to " + min;
    }

    /**
     * Returns the text of a value over its maximum.
     *
     * @param max the greatest value allowed
     * @return the text
     */
    public static String atMost(long max) {
        return "must be less than or equal to " + max;
    }

    /**
     * Returns the text of a cursor sent with an offset.
     *
     * @param other the name of the offset parameter or field, as the caller sent it
     * @return the text
     */
    public static String notCombinedWith(String other) {
        return "must not be combined with " + other;
    }

    /**
     * Returns the text of a page token that carries an offset past the cap: the token of a page whose next offset
     * passes it, or one issued under a higher cap.
     *
     * @param maxOffset {@code synapse.paging.max-offset}
     * @return the text
     */
    public static String offsetLimitReached(long maxOffset) {
        return "has reached the offset limit (" + maxOffset + "); narrow the query or use keyset paging";
    }

    /**
     * Checks the keys of one sort against an allow-list, in the order the caller sent them. Each distinct problem is
     * kept once, in the order first found; the sort is empty when there is a problem.
     */
    public static final class SortReading {

        private final Map<String, String> wireToProperty;

        private final Set<String> problems = new LinkedHashSet<>();

        private final Set<String> seen = new HashSet<>();

        private final List<SortOrder> sort = new ArrayList<>();

        /**
         * Starts reading a sort.
         *
         * @param wireToProperty the allowed wire names, in declaration order, each mapped to the property the
         *        operation receives
         */
        public SortReading(Map<String, String> wireToProperty) {
            this.wireToProperty = wireToProperty;
        }

        /** Records an element the plugin's tokenizer can't read, such as an empty one. */
        public void invalid() {
            problems.add(INVALID);
        }

        /**
         * Records one key.
         *
         * @param wireName the key as the caller sent it
         * @param descending whether the caller asked for a descending order
         */
        public void key(String wireName, boolean descending) {
            String property = wireToProperty.get(wireName);
            if (property == null) {
                problems
                        .add("has a key that is not allowed (allowed: " + String.join(", ", wireToProperty.keySet())
                                + ")");
            } else if (!seen.add(wireName)) {
                problems.add("names a key more than once");
            } else {
                sort.add(new SortOrder(property, descending ? SortOrder.Direction.DESC : SortOrder.Direction.ASC));
            }
        }

        /**
         * Returns the problems found, each once, in the order first found.
         *
         * @return the problems; empty when the sort is valid
         */
        public Set<String> problems() {
            return Collections.unmodifiableSet(problems);
        }

        /**
         * Returns the sort.
         *
         * @return the orders in the caller's order; empty when there is a problem
         */
        public List<SortOrder> sort() {
            return problems.isEmpty() ? List.copyOf(sort) : List.of();
        }
    }
}
