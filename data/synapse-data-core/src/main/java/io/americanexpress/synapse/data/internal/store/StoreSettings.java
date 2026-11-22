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

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import io.americanexpress.synapse.data.autoconfigure.SynapseDataStoreProperties;

/**
 * Where a store's settings are, and how they are bound. Public only so that the data modules share it; internal.
 */
public final class StoreSettings {

    /** The prefix of every store's settings. */
    public static final String PREFIX = "synapse.data.stores";

    /** Where a store's migrations are by default, followed by the store name. */
    private static final String DEFAULT_MIGRATION_LOCATION = "classpath:db/migration/";

    private StoreSettings() {
    }

    /**
     * Binds a store's settings.
     *
     * @param environment the environment
     * @param store the store name
     * @return the settings, with the defaults for every key that isn't set
     */
    public static SynapseDataStoreProperties bind(Environment environment, String store) {
        return Binder.get(environment).bindOrCreate(prefix(store), SynapseDataStoreProperties.class);
    }

    /**
     * Returns the prefix of a store's settings.
     *
     * @param store the store name
     * @return {@code synapse.data.stores.<store>}
     */
    public static String prefix(String store) {
        return PREFIX + "." + store;
    }

    /**
     * Returns where a store's migrations are: its {@code migrations.locations}, else
     * {@code classpath:db/migration/<store>}.
     *
     * @param store the store name
     * @param migrations the store's migration settings
     * @return the locations, never empty
     */
    public static List<String> migrationLocations(String store, SynapseDataStoreProperties.Migrations migrations) {
        List<String> locations = migrations.locations();
        return locations == null || locations.isEmpty() ? List.of(DEFAULT_MIGRATION_LOCATION + store) : locations;
    }
}
