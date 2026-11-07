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

/**
 * Migrates one store's schema. The store's lifecycle calls it when the store starts, never at bean creation. Flyway is
 * the one implementation ({@link FlywayStoreMigration}); the seam keeps the lifecycle independent of the tool.
 */
public interface StoreMigration {

    /**
     * Returns the store this migration belongs to.
     *
     * @return the store name
     */
    String store();

    /**
     * Brings the store's schema up to date. This connects to the database.
     *
     * @throws IllegalStateException when the migration fails; the message names the store
     */
    void migrate();
}
