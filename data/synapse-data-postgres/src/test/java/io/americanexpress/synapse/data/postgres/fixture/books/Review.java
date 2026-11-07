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
package io.americanexpress.synapse.data.postgres.fixture.books;

import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.data.jpa.LongAuditedEntity;

/**
 * A review of a book (table {@code review}): a foreign key to {@code book}, and a {@code Long} id from the sequence
 * {@code review_seq}, so the schema has the constraints and sequences a schema action drops and creates.
 */
@Entity
public class Review extends LongAuditedEntity {

    @ManyToOne(optional = false)
    private @Nullable Book book;

    private int stars;

    /** For JPA. */
    protected Review() {
    }

    /**
     * Creates a review.
     *
     * @param book the book
     * @param stars the number of stars
     */
    public Review(Book book, int stars) {
        this.book = book;
        this.stars = stars;
    }

    /**
     * Returns the book.
     *
     * @return the book
     */
    public @Nullable Book getBook() {
        return book;
    }

    /**
     * Returns the number of stars.
     *
     * @return the stars
     */
    public int getStars() {
        return stars;
    }
}
