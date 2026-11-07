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

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Boot keys that the data stores' startup report warns about when the application sets them: a prefix, such as
 * {@code spring.datasource}, or one key. The report gives each key to the most specific entry it matches, and names
 * keys, never their values. Data-core lists its own, and each access module its stores' through
 * {@link StoreInfrastructure#ignoredKeys()}; internal.
 *
 * @param name the prefix or the key, such as {@code spring.datasource} or {@code spring.data.jdbc.dialect}
 * @param warning the whole WARN line for a key; {@code null} for a prefix, whose line lists the keys that are set
 * @param quietValue for a key, the value (compared ignoring case and surrounding white space) for which no WARN is
 *        logged, such as Synapse's own default; {@code null} to warn whatever the value
 */
public record IgnoredKeys(String name, @Nullable String warning, @Nullable String quietValue) {

    /**
     * Keys under a prefix that the stores don't use. The WARN lists them: {@code <prefix>.* is set (<key>, <key>), but
     * Synapse data stores don't use it. Configure each store under synapse.data.stores.<name>.}
     *
     * @param prefix the prefix, such as {@code spring.flyway}
     * @return the entry
     */
    public static IgnoredKeys prefix(String prefix) {
        return new IgnoredKeys(prefix, null, null);
    }

    /**
     * One key (or the keys below it), with its own WARN line.
     *
     * @param key the key, such as {@code spring.data.jdbc.dialect}
     * @param warning the WARN line, which never contains a value
     * @return the entry
     */
    public static IgnoredKeys key(String key, String warning) {
        return new IgnoredKeys(key, warning, null);
    }

    /**
     * One key with its own WARN line, logged unless the key's value is the quiet one. The value is compared, never
     * logged.
     *
     * @param key the key, such as {@code spring.jpa.open-in-view}
     * @param warning the WARN line, which never contains a value
     * @param quietValue the value for which no WARN is logged, such as {@code false}
     * @return the entry
     */
    public static IgnoredKeys key(String key, String warning, String quietValue) {
        return new IgnoredKeys(key, warning, quietValue);
    }

    /**
     * Whether a value of the key is the quiet one.
     *
     * @param value the key's value in the environment, or {@code null}
     * @return {@code true} when no WARN is logged for it
     */
    boolean isQuiet(@Nullable String value) {
        String quiet = this.quietValue;
        return quiet != null && value != null && quiet.equalsIgnoreCase(value.trim());
    }

    /**
     * Returns the WARN line for the keys of this entry that are set.
     *
     * @param keys the keys that are set, in their canonical form, sorted
     * @return the line
     */
    String warning(List<String> keys) {
        String warning = this.warning;
        if (warning != null) {
            return warning;
        }
        return name + ".* is set (" + String.join(", ", keys) + "), but Synapse data stores don't use it. Configure "
                + "each store under synapse.data.stores.<name>.";
    }
}
