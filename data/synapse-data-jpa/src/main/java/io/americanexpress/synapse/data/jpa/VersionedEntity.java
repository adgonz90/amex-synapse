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
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.data.Versioned;

/**
 * A JPA entity with a UUID id (see {@link IdEntity}) and a version (column {@code version}) for optimistic locking: a
 * write that finds another version than the one it read fails with {@code STALE_VERSION}.
 * <p>
 * The entity is new until Hibernate gives it a version, when it is first persisted.
 */
@MappedSuperclass
public abstract class VersionedEntity extends IdEntity implements Versioned {

    @Version
    @Column(name = "version")
    private @Nullable Long version;

    /** For subclasses. */
    protected VersionedEntity() {
    }

    @Override
    public @Nullable Long getVersion() {
        return version;
    }

    /**
     * Returns whether the entity is new, that is, has no version yet.
     *
     * @return whether the entity has no version
     */
    @Override
    public boolean isNew() {
        return version == null;
    }
}
