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
package io.americanexpress.synapse.test.data.slice.books;

import jakarta.persistence.Entity;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.data.jpa.AuditedEntity;

/**
 * An audited book (table {@code book}): its id, version and audit columns come from {@link AuditedEntity}.
 */
@Entity
public class Book extends AuditedEntity {

    private @Nullable String title;

    /** For JPA. */
    protected Book() {
    }

    /**
     * Creates a book.
     *
     * @param title the title
     */
    public Book(String title) {
        this.title = title;
    }

    /**
     * Returns the title.
     *
     * @return the title
     */
    public @Nullable String getTitle() {
        return title;
    }
}
