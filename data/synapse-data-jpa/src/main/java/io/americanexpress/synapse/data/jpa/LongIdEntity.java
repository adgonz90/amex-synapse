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

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

import org.hibernate.Hibernate;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Persistable;

import io.americanexpress.synapse.data.Identifiable;

/**
 * A JPA entity with a {@code Long} id (column {@code id}) from a database sequence. Hibernate names the sequence after
 * the entity ({@code <entity>_seq}, such as {@code ledger_entry_seq}) and allocates 50 ids per call, so the migration
 * creates it with {@code INCREMENT BY 50}.
 * <p>
 * The entity is new until it has an id. Two entities are equal when they have the same id and the same class,
 * proxies included. An uninitialized proxy passed as the argument of {@code equals} is compared without being loaded,
 * unless the entity class has subclasses; calling {@code equals} or {@code hashCode} on a proxy loads it. An entity
 * without an id is equal only to itself. The hash code is the class's, so it doesn't change when the id is assigned.
 */
@MappedSuperclass
public abstract class LongIdEntity implements Identifiable<Long>, Persistable<Long> {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    @Column(name = "id")
    private @Nullable Long id;

    /** For subclasses. */
    protected LongIdEntity() {
    }

    @Override
    public @Nullable Long getId() {
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

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LongIdEntity that) || Hibernate.getClassLazy(this) != Hibernate.getClassLazy(that)) {
            return false;
        }
        Long own = getId();
        return own != null && own.equals(that.getId());
    }

    @Override
    public int hashCode() {
        return Hibernate.getClassLazy(this).hashCode();
    }
}
