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
package io.americanexpress.synapse.data.internal.migration;

import org.jspecify.annotations.Nullable;

/**
 * Finds a store's {@link StoreMigration}. The data auto-configuration defines one bean of this type while a migration
 * tool is on the class path, so code that must load without the tool (such as the store factory) asks for it by this
 * type instead of naming the tool's classes.
 */
public interface StoreMigrations {

    /**
     * Returns a store's migration.
     *
     * @param store the store name
     * @return the migration, or {@code null} when the store has none
     */
    @Nullable
    StoreMigration migration(String store);
}
