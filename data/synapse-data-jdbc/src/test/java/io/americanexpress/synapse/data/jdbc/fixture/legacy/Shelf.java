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
package io.americanexpress.synapse.data.jdbc.fixture.legacy;

import java.util.HashSet;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Sequence;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A shelf of the legacy store (table {@code shelf}): its id comes from the database sequence {@code shelf_seq}, and
 * its slots are a relation (table {@code slot}, with the back reference {@code shelf}).
 */
@Table
public class Shelf {

    @Id
    @Sequence("SHELF_SEQ")
    private @Nullable Long id;

    private @Nullable String name;

    private Set<Slot> slots = new HashSet<>();

    /** For Spring Data. */
    Shelf() {
    }

    /**
     * Creates a shelf.
     *
     * @param name the name
     * @param slots the labels of its slots
     */
    public Shelf(String name, String... slots) {
        this.name = name;
        for (String label : slots) {
            this.slots.add(new Slot(label));
        }
    }

    /**
     * Returns the id.
     *
     * @return the id, or {@code null} before the shelf is first saved
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

    /**
     * Returns the slots.
     *
     * @return the slots
     */
    public Set<Slot> getSlots() {
        return slots;
    }
}
