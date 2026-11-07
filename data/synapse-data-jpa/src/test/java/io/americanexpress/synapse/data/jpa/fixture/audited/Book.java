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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.data.jpa.AuditedEntity;

/**
 * An audited book (table {@code book}) that declares no listener: its id, version and audit columns come from
 * {@link AuditedEntity}. The ISBN is unique.
 */
@Entity
public class Book extends AuditedEntity {

    private @Nullable String title;

    @Column(unique = true)
    private @Nullable String isbn;

    /** For JPA. */
    protected Book() {
    }

    /**
     * Creates a book without an ISBN.
     *
     * @param title the title
     */
    public Book(String title) {
        this.title = title;
    }

    /**
     * Creates a book.
     *
     * @param title the title
     * @param isbn the ISBN
     */
    public Book(String title, String isbn) {
        this.title = title;
        this.isbn = isbn;
    }

    /**
     * Returns the title.
     *
     * @return the title
     */
    public @Nullable String getTitle() {
        return title;
    }

    /**
     * Changes the title.
     *
     * @param title the new title
     */
    public void rename(String title) {
        this.title = title;
    }
}
