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
package io.americanexpress.synapse.data.jpa.internal;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import io.americanexpress.synapse.data.jpa.fixture.H2TestVendor;
import io.americanexpress.synapse.data.jpa.fixture.books.Book;
import io.americanexpress.synapse.data.jpa.fixture.books.BookRepository;
import io.americanexpress.synapse.data.jpa.fixture.books.BooksStore;
import io.americanexpress.synapse.data.jpa.fixture.legacy.LegacyItemRepository;
import io.americanexpress.synapse.data.jpa.fixture.legacy.LegacyStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots an application with two H2 stores, each migrated from its own {@code db/migration/<store>}, and checks that
 * each store's repositories read and write that store's database.
 */
@Tag("smoke")
@SpringBootTest(classes = JpaRepositoriesIT.TestApplication.class, properties = {
        "synapse.data.stores.books.url=jdbc:h2:mem:jpa-repositories-it-books;DB_CLOSE_DELAY=-1",
        "synapse.data.stores.books.username=sa", "synapse.data.stores.books.password=",
        "synapse.data.stores.legacy.url=jdbc:h2:mem:jpa-repositories-it-legacy;DB_CLOSE_DELAY=-1",
        "synapse.data.stores.legacy.username=sa", "synapse.data.stores.legacy.password="})
@ActiveProfiles("test")
class JpaRepositoriesIT {

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private LegacyItemRepository legacyItemRepository;

    @Autowired
    @Qualifier("books")
    private JdbcClient books;

    @Test
    void saveAndFindPerStore() {
        bookRepository.save(new Book(1, "Dune"));

        assertThat(bookRepository.findById(1L))
                .hasValueSatisfying(book -> assertThat(book.getTitle()).isEqualTo("Dune"));
        assertThat(books.sql("SELECT title FROM book WHERE id = 1").query(String.class).single()).isEqualTo("Dune");
        assertThat(legacyItemRepository.findAll()).as("the legacy store has its own database").isEmpty();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({H2TestVendor.class, BooksStore.class, LegacyStore.class})
    static class TestApplication {
    }
}
