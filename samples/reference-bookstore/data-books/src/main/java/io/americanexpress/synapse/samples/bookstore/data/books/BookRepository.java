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

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The books store's repository. It is in the store's package, so Synapse registers it on the books store (no
 * {@code @EnableJpaRepositories}), and its own methods run in transactions of the books store.
 */
public interface BookRepository extends JpaRepository<StoredBook, UUID> {

    /**
     * Finds the books whose author's name contains a text, ignoring case.
     *
     * <p>
     * Spring Data derives {@code upper(author) like upper('%le guin%')}, which no B-tree index can serve, so every
     * search reads the whole {@code book} table, and V1 creates no index on {@code author}. That is fine for this
     * sample's catalog. For a large one, add a migration with a trigram index (PostgreSQL's {@code pg_trgm}
     * extension, a GIN index on {@code upper(author)}), or search by prefix instead, which a B-tree index on
     * {@code upper(author)} with the {@code varchar_pattern_ops} operator class serves.
     *
     * @param author part of the author's name, such as {@code "le guin"}
     * @param page which page to return, and its order
     * @return the page, with the number of books that match
     */
    Page<StoredBook> findByAuthorContainingIgnoreCase(String author, Pageable page);

    /**
     * Returns whether a book has the ISBN. The table's unique constraint is what keeps ISBNs unique: a book saved with
     * a taken one fails with Synapse's {@code ALREADY_EXISTS}, whether or not this was asked first.
     *
     * @param isbn the ISBN
     * @return {@code true} if a book has it
     */
    boolean existsByIsbn(String isbn);
}
