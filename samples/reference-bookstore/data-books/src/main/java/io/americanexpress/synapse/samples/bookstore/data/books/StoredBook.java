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
package io.americanexpress.synapse.samples.bookstore.data.books;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.data.jpa.AuditedEntity;

/**
 * A book as the books store keeps it (table {@code book}). {@link AuditedEntity} gives it the rest: a UUIDv7 id
 * assigned when it is first saved, a {@code version} for optimistic locking (0 when it is added, plus 1 on every
 * change), and the audit columns {@code created_at}, {@code created_by}, {@code modified_at} and {@code modified_by},
 * which Synapse stamps on every save.
 *
 * <p>
 * The entity never leaves the service's data layer: the operations map it to the contract's {@code Book} record.
 */
@Entity
@Table(name = "book")
public class StoredBook extends AuditedEntity {

    /** Unique in the store: V1's constraint {@code book_isbn_key} enforces it, not JPA. */
    @Column(name = "isbn", nullable = false, length = 17)
    private @Nullable String isbn;

    @Column(name = "title", nullable = false)
    private @Nullable String title;

    @Column(name = "author", nullable = false)
    private @Nullable String author;

    @Column(name = "published_year")
    private @Nullable Integer publishedYear;

    /** For JPA. */
    protected StoredBook() {
    }

    /**
     * Creates a book that is not saved yet.
     *
     * @param isbn the ISBN, at most 17 characters (an ISBN-13 with its hyphens), unique in the store
     * @param title the title
     * @param author the author
     * @param publishedYear the year of publication, or {@code null} when it is not known
     */
    public StoredBook(String isbn, String title, String author, @Nullable Integer publishedYear) {
        this.isbn = isbn;
        this.title = title;
        this.author = author;
        this.publishedYear = publishedYear;
    }

    /**
     * Replaces the book's details. The change is written when the transaction commits (or the repository flushes),
     * which raises the version by 1; it fails if another transaction wrote the book after this copy was read.
     *
     * @param newIsbn the ISBN
     * @param newTitle the title
     * @param newAuthor the author
     * @param newPublishedYear the year of publication, or {@code null} when it is not known
     */
    public void revise(String newIsbn, String newTitle, String newAuthor, @Nullable Integer newPublishedYear) {
        this.isbn = newIsbn;
        this.title = newTitle;
        this.author = newAuthor;
        this.publishedYear = newPublishedYear;
    }

    /**
     * Returns the ISBN.
     *
     * @return the ISBN
     */
    public @Nullable String getIsbn() {
        return isbn;
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
     * Returns the author.
     *
     * @return the author
     */
    public @Nullable String getAuthor() {
        return author;
    }

    /**
     * Returns the year of publication.
     *
     * @return the year, or {@code null} when it is not known
     */
    public @Nullable Integer getPublishedYear() {
        return publishedYear;
    }
}
