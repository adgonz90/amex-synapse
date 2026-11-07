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
package io.americanexpress.synapse.data.jpa.fixture.books;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * The books store's repository, with a custom fragment and one query method of each kind Spring Data prepares at
 * startup.
 */
public interface BookRepository extends JpaRepository<Book, Long>, BookRepositoryCustom {

    /**
     * A derived query.
     *
     * @param title the title
     * @return the books with that title
     */
    List<Book> findByTitle(String title);

    /**
     * A derived count query.
     *
     * @param title the title
     * @return how many books have that title
     */
    long countByTitle(String title);

    /**
     * A JPQL query.
     *
     * @param prefix the start of the title
     * @return the books whose title starts with it
     */
    @Query("SELECT b FROM Book b WHERE b.title LIKE CONCAT(?1, '%')")
    List<Book> findByTitlePrefix(String prefix);

    /**
     * A native query.
     *
     * @return how many rows the book table has
     */
    @Query(value = "SELECT COUNT(*) FROM book", nativeQuery = true)
    long countRows();
}
