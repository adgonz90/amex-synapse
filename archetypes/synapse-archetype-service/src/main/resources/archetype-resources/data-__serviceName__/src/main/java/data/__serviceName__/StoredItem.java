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
package ${package}.data.${serviceName};

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.data.jpa.AuditedEntity;

/**
 * An item as the ${serviceName} store keeps it (table {@code item}). {@link AuditedEntity} gives it the rest: a UUIDv7
 * id assigned when it is first saved, a {@code version} for optimistic locking (0 when it is added, plus 1 on every
 * change), and the audit columns {@code created_at}, {@code created_by}, {@code modified_at} and {@code modified_by},
 * which Synapse stamps on every save.
 *
 * <p>
 * The entity never leaves the service's data layer: the operations map it to the contract's {@code Item} record.
 */
@Entity
@Table(name = "item")
public class StoredItem extends AuditedEntity {

    /** Unique in the store: V1's constraint {@code item_name_key} enforces it, not JPA. */
    @Column(name = "name", nullable = false)
    private @Nullable String name;

    @Column(name = "description", length = 1000)
    private @Nullable String description;

    /** For JPA. */
    protected StoredItem() {
    }

    /**
     * Creates an item that is not saved yet.
     *
     * @param name the name, unique in the store
     * @param description the description, or {@code null} for none
     */
    public StoredItem(String name, @Nullable String description) {
        this.name = name;
        this.description = description;
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
     * Returns the description.
     *
     * @return the description, or {@code null} when it has none
     */
    public @Nullable String getDescription() {
        return description;
    }
}
