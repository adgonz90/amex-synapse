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
package io.americanexpress.synapse.core.internal.pipeline;

import jakarta.validation.Path;

/**
 * Names a constraint violation's field: the path of the rejected value in the one format every protocol renames to
 * its wire names (BUILD 5.2). Property names are joined with dots, and a list or array element, a scalar one included,
 * adds {@code [index]} ({@code tags[0]}, {@code items[2].sku}). A {@code Set} element has no index. A map key, the
 * caller's data, is never written: a constraint on a key or a whole value names the map ({@code labels}), and one
 * inside a value continues with the value's property ({@code authorsByRank.countryCode}). Jakarta's container element
 * node names ({@code <list element>}, {@code <iterable element>}, {@code <map key>}, {@code <map value>}) are never
 * written.
 */
public final class ViolationPaths {

    private ViolationPaths() {
    }

    /**
     * Returns the field of a violation with this property path.
     *
     * @param path the violation's property path
     * @return the path in Synapse's format; empty for a constraint on the validated object itself
     */
    public static String of(Path path) {
        StringBuilder field = new StringBuilder();
        for (Path.Node node : path) {
            Integer index = node.getIndex();
            if (node.isInIterable() && index != null) {
                field.append('[').append(index).append(']');
            }
            String name = node.getName();
            if (name != null && !name.isEmpty() && !name.startsWith("<")) {
                if (!field.isEmpty()) {
                    field.append('.');
                }
                field.append(name);
            }
        }
        return field.toString();
    }
}
