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
package io.americanexpress.synapse.data.mixed.fixture.legacy;

import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * An item of the legacy store (table {@code legacy_item}), a Spring Data JDBC aggregate with an id the database
 * generates.
 */
@Table
public class LegacyItem {

    @Id
    private @Nullable Long id;

    private @Nullable String name;

    /** For Spring Data. */
    LegacyItem() {
    }

    /**
     * Creates an item.
     *
     * @param name the name
     */
    public LegacyItem(String name) {
        this.name = name;
    }

    /**
     * Returns the id.
     *
     * @return the id, or {@code null} before the item is first saved
     */
    public @Nullable Long getId() {
        return id;
    }

    /**
     * Returns the name.
     *
     * @return the name
     */
    public @Nullable String getName() {
        return name;
    }
}
