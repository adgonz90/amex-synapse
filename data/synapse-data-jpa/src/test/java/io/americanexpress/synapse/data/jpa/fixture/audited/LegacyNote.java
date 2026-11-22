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
package io.americanexpress.synapse.data.jpa.fixture.audited;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.data.Auditable;

/**
 * A note of a legacy schema (table {@code legacy_note}) that uses no Synapse base class: it implements
 * {@link Auditable} on its own columns {@code note_created}, {@code note_created_by}, {@code note_modified} and
 * {@code note_modified_by}, and its id is assigned by the application.
 */
@Entity
public class LegacyNote implements Auditable {

    @Id
    private @Nullable Long id;

    private @Nullable String body;

    @Column(name = "note_created", updatable = false)
    private @Nullable Instant created;

    @Column(name = "note_created_by", updatable = false)
    private @Nullable String createdBy;

    @Column(name = "note_modified")
    private @Nullable Instant modified;

    @Column(name = "note_modified_by")
    private @Nullable String modifiedBy;

    /** For JPA. */
    protected LegacyNote() {
    }

    /**
     * Creates a note.
     *
     * @param id the id
     * @param body the text
     */
    public LegacyNote(long id, String body) {
        this.id = id;
        this.body = body;
    }

    /**
     * Returns the id.
     *
     * @return the id
     */
    public @Nullable Long getId() {
        return id;
    }

    /**
     * Returns the text.
     *
     * @return the text
     */
    public @Nullable String getBody() {
        return body;
    }

    @Override
    public @Nullable Instant getCreatedAt() {
        return created;
    }

    @Override
    public @Nullable String getCreatedBy() {
        return createdBy;
    }

    @Override
    public @Nullable Instant getModifiedAt() {
        return modified;
    }

    @Override
    public @Nullable String getModifiedBy() {
        return modifiedBy;
    }

    @Override
    public void setCreated(Instant at, String by) {
        this.created = at;
        this.createdBy = by;
    }

    @Override
    public void setModified(Instant at, String by) {
        this.modified = at;
        this.modifiedBy = by;
    }
}
