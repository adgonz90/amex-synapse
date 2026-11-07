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
package io.americanexpress.synapse.data.jdbc.fixture.books;

import java.time.Instant;

import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import io.americanexpress.synapse.data.Auditable;

/**
 * An audited book aggregate (table {@code book}): an id the database generates, a version and the audit columns. The
 * ISBN is unique.
 */
@Table
public class Book implements Auditable {

    @Id
    private @Nullable Long id;

    @Version
    private @Nullable Long version;

    private @Nullable String title;

    private @Nullable String isbn;

    private @Nullable Instant createdAt;

    private @Nullable String createdBy;

    private @Nullable Instant modifiedAt;

    private @Nullable String modifiedBy;

    /** For Spring Data. */
    Book() {
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
     * Returns the id.
     *
     * @return the id, or {@code null} before the book is first saved
     */
    public @Nullable Long getId() {
        return id;
    }

    /**
     * Returns the version.
     *
     * @return the version, or {@code null} before the book is first saved
     */
    public @Nullable Long getVersion() {
        return version;
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
