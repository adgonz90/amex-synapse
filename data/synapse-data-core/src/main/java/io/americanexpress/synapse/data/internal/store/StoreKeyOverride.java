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

import io.americanexpress.synapse.core.SynapseConfigurationException;

/**
 * The failures for a pass-through key (under {@code hikari.*} or {@code migrations.flyway.*}) that would replace one
 * of the store's own settings, or run SQL on each new connection. Public only so that the migration package shares
 * it; internal.
 */
public final class StoreKeyOverride {

    private StoreKeyOverride() {
    }

    /**
     * Describes a key that would replace a store setting. It names the key, never its value.
     *
     * @param store the store name
     * @param key the full key, such as {@code synapse.data.stores.books.hikari.jdbc-url}
     * @param storeKey the store setting it would replace, relative to the store's prefix, such as {@code url}
     * @return the failure
     */
    public static SynapseConfigurationException failure(String store, String key, String storeKey) {
        String prefix = StoreSettings.prefix(store);
        return new SynapseConfigurationException(
                "The store '" + store + "' sets " + key + ", which would replace its " + storeKey + ".",
                "Remove " + key + " and set " + prefix + "." + storeKey + " instead.");
    }

    /**
     * Describes a key that sets SQL run on each new connection (such as {@code hikari.connection-init-sql}), which
     * could change the role or schema the store uses ({@code SET ROLE}, {@code SET search_path}). It names the key,
     * never its value.
     *
     * @param store the store name
     * @param key the full key, such as {@code synapse.data.stores.books.hikari.connection-init-sql}
     * @return the failure
     */
    public static SynapseConfigurationException initSql(String store, String key) {
        return connectionSql(store, key, "on each new connection");
    }

    /**
     * Describes a key that sets SQL run on each connection check ({@code hikari.connection-test-query}), where a
     * {@code SET ROLE} would change the role the store uses as well. It names the key, never its value.
     *
     * @param store the store name
     * @param key the full key, such as {@code synapse.data.stores.books.hikari.connection-test-query}
     * @return the failure
     */
    public static SynapseConfigurationException testQuery(String store, String key) {
        return connectionSql(store, key, "on each connection check");
    }

    private static SynapseConfigurationException connectionSql(String store, String key, String when) {
        return new SynapseConfigurationException("The store '" + store + "' sets " + key + ", SQL that runs " + when
                + " and could change the role or schema the store uses.",
                "Remove " + key + ". Name the schema in " + StoreSettings.prefix(store) + ".url.");
    }
}
