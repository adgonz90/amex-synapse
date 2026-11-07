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
package io.americanexpress.synapse.data.jpa.fixture.isbn;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.jspecify.annotations.Nullable;

/**
 * A book with an {@code isbn} column, which the books store's migration doesn't create.
 */
@Entity
@Table(name = "book")
public class IsbnBook {

    @Id
    private @Nullable Long id;

    private @Nullable String title;

    private @Nullable String isbn;

    /** For JPA. */
    protected IsbnBook() {
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
     * Returns the title.
     *
     * @return the title
     */
    public @Nullable String getTitle() {
        return title;
    }

    /**
     * Returns the ISBN.
     *
     * @return the ISBN
     */
    public @Nullable String getIsbn() {
        return isbn;
    }
}
