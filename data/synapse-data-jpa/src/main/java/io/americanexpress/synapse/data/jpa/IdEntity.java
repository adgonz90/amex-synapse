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
package io.americanexpress.synapse.data.jpa;

import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

import org.hibernate.Hibernate;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Persistable;

import io.americanexpress.synapse.data.EntityIdGenerator;
import io.americanexpress.synapse.data.Identifiable;

/**
 * A JPA entity with a UUID id (column {@code id}). The id is assigned when the entity is first persisted, by the
 * application's {@link EntityIdGenerator} (UUIDv7 by default), so new rows land at the end of the primary key's
 * index.
 * <p>
 * The entity is new until it has an id, so Spring Data saves a new entity with a single {@code INSERT}. Two entities
 * are equal when they have the same id and the same class, proxies included. An uninitialized proxy passed as the
 * argument of {@code equals} is compared without being loaded, unless the entity class has subclasses; calling
 * {@code equals} or {@code hashCode} on a proxy loads it. An entity without an id is equal only to itself. The hash
 * code is the class's, so it doesn't change when the id is assigned.
 */
@MappedSuperclass
public abstract class IdEntity implements Identifiable<UUID>, Persistable<UUID> {

    @Id
    @Column(name = "id")
    private @Nullable UUID id;

    /** For subclasses. */
    protected IdEntity() {
    }

    @Override
    public @Nullable UUID getId() {
        return id;
    }

    /**
     * Returns whether the entity is new, that is, has no id yet.
     *
     * @return whether the entity has no id
     */
    @Override
    public boolean isNew() {
        return id == null;
    }

    /** Assigns the id; only {@link EntityCallbacks} calls it, before the entity is first persisted. */
    void assignId(UUID id) {
        this.id = id;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof IdEntity that) || Hibernate.getClassLazy(this) != Hibernate.getClassLazy(that)) {
            return false;
        }
        UUID own = getId();
        return own != null && own.equals(that.getId());
    }

    @Override
    public int hashCode() {
        return Hibernate.getClassLazy(this).hashCode();
    }
}
