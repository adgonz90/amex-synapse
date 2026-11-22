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

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.data.Auditable;

/**
 * A versioned JPA entity with a {@code Long} id from a database sequence (see {@link LongVersionedEntity}) that
 * records when and by whom it was created (columns {@code created_at} and {@code created_by}, never updated) and last
 * modified ({@code modified_at} and {@code modified_by}). Synapse stamps it when it is persisted and updated: the
 * time from the application's {@link java.time.Clock}, the user from the request context's principal, else
 * {@code synapse.data.auditing.system-user}.
 */
@MappedSuperclass
public abstract class LongAuditedEntity extends LongVersionedEntity implements Auditable {

    @Column(name = "created_at", updatable = false)
    private @Nullable Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private @Nullable String createdBy;

    @Column(name = "modified_at")
    private @Nullable Instant modifiedAt;

    @Column(name = "modified_by")
    private @Nullable String modifiedBy;

    /** For subclasses. */
    protected LongAuditedEntity() {
    }

    @Override
    public @Nullable Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public @Nullable String getCreatedBy() {
        return createdBy;
    }

    @Override
    public @Nullable Instant getModifiedAt() {
        return modifiedAt;
    }

    @Override
    public @Nullable String getModifiedBy() {
        return modifiedBy;
    }

    @Override
    public void setCreated(Instant at, String by) {
        this.createdAt = at;
        this.createdBy = by;
    }

    @Override
    public void setModified(Instant at, String by) {
        this.modifiedAt = at;
        this.modifiedBy = by;
    }
}
