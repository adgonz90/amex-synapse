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
package io.americanexpress.synapse.data.jpa.fixture.ownrepository;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import org.jspecify.annotations.Nullable;

/**
 * An entity of the store, so a Spring Data repository of it is valid.
 */
@Entity
public class Shelf {

    @Id
    private @Nullable Long id;

    /** For JPA. */
    protected Shelf() {
    }

    /**
     * Returns the id.
     *
     * @return the id
     */
    public @Nullable Long getId() {
        return id;
    }
}
