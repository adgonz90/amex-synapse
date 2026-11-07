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
package io.americanexpress.synapse.data;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * An entity that records when and by whom it was created and last modified. Synapse stamps it on save: the time from
 * the application's {@link java.time.Clock}, the user from the request's principal, else a system user.
 */
public interface Auditable {

    /**
     * Returns when the entity was created.
     *
     * @return the creation time, or {@code null} before the entity is first saved
     */
    @Nullable
    Instant getCreatedAt();

    /**
     * Returns who created the entity.
     *
     * @return the creating user, or {@code null} before the entity is first saved
     */
    @Nullable
    String getCreatedBy();

    /**
     * Returns when the entity was last modified.
     *
     * @return the modification time, or {@code null} before the entity is first saved
     */
    @Nullable
    Instant getModifiedAt();

    /**
     * Returns who last modified the entity.
     *
     * @return the modifying user, or {@code null} before the entity is first saved
     */
    @Nullable
    String getModifiedBy();

    /**
     * Records the creation.
     *
     * @param at when the entity was created
     * @param by who created it
     */
    void setCreated(Instant at, String by);

    /**
     * Records a modification.
     *
     * @param at when the entity was modified
     * @param by who modified it
     */
    void setModified(Instant at, String by);
}
