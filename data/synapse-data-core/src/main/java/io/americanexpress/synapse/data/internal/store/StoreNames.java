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
package io.americanexpress.synapse.data.internal.store;

import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.source.ConfigurationPropertyName;

/**
 * Store names: lowercase letters, digits and dashes, starting with a letter ({@code books}, {@code legacy-orders}).
 * The name is the qualifier as written; bean names start with its lowerCamel form ({@code legacyOrders}).
 */
public final class StoreNames {

    private static final Pattern VALID = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");

    private StoreNames() {
    }

    /**
     * Returns whether a name is a valid store name.
     *
     * @param name the name
     * @return {@code true} when it is valid
     */
    public static boolean isValid(String name) {
        return VALID.matcher(name).matches();
    }

    /**
     * Returns the prefix of a store's bean names: the name in lowerCamel form.
     *
     * @param name a valid store name
     * @return the prefix, such as {@code legacyOrders} for {@code legacy-orders}
     */
    public static String beanPrefix(String name) {
        StringBuilder prefix = new StringBuilder(name.length());
        boolean upper = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '-') {
                upper = true;
            } else {
                prefix.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return prefix.toString();
    }

    /**
     * Returns the environment variable that sets one of a store's keys, the way Boot maps it.
     *
     * @param name a valid store name
     * @param key the key below the store, such as {@code password}
     * @return the variable, such as {@code SYNAPSE_DATA_STORES_LEGACYORDERS_PASSWORD}
     */
    public static String environmentVariable(String name, String key) {
        return ("SYNAPSE_DATA_STORES_" + name + "_" + key.replace('.', '_')).replace("-", "").toUpperCase(Locale.ROOT);
    }

    /**
     * Returns whether two store names bind the same properties: Boot ignores dashes and case when it matches a key,
     * so {@code legacy-orders} and {@code legacyorders} are the same store.
     *
     * @param first a valid store name
     * @param second another valid store name
     * @return {@code true} when they bind the same properties
     */
    public static boolean sameStore(String first, String second) {
        return ConfigurationPropertyName.of(first).equals(ConfigurationPropertyName.of(second));
    }
}
