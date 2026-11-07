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
package io.americanexpress.synapse.data.postgres;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.americanexpress.synapse.data.jpa.internal.JpaStoreFactory;
import io.americanexpress.synapse.data.postgres.fixture.books.Book;
import io.americanexpress.synapse.data.postgres.fixture.books.BookRepository;
import io.americanexpress.synapse.data.postgres.fixture.books.Review;
import io.americanexpress.synapse.data.postgres.fixture.books.ReviewRepository;
import io.americanexpress.synapse.test.data.SynapseContainers;

import static io.americanexpress.synapse.data.postgres.PostgresServices.FOREIGN_KEYS;
import static io.americanexpress.synapse.data.postgres.PostgresServices.SEQUENCES;
import static io.americanexpress.synapse.data.postgres.PostgresServices.TABLES;
import static io.americanexpress.synapse.data.postgres.PostgresServices.arguments;
import static io.americanexpress.synapse.data.postgres.PostgresServices.names;
import static io.americanexpress.synapse.data.postgres.PostgresServices.service;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ddl-auto: create} on PostgreSQL (Docker), which runs with {@code hibernate.hbm2ddl.halt_on_error}, so a
 * failed DDL statement fails startup. Its drop phase (foreign keys, tables, sequences) must not fail on an empty
 * database, where none of them exists, nor on one that the previous run filled.
 */
class PostgresSchemaActionIT {

    static final PostgreSQLContainer BOOKS = SynapseContainers.postgres("books");

    static final PostgreSQLContainer LEGACY = SynapseContainers.postgres("legacy");

    @Test
    void createDropsAndCreatesOnAnEmptyDatabaseAndAgain() throws SQLException {
        String url = PostgresServices.emptyDatabase(BOOKS, "create_check");
        assertThat(names(BOOKS, url, TABLES)).isEmpty();
        String[] create = arguments(BOOKS, url, LEGACY, "--synapse.data.stores.books.jpa.hibernate.ddl-auto=create",
                "--synapse.data.stores.books.allow-ddl-auto=true",
                "--synapse.data.stores.books.migrations.enabled=false");

        try (ConfigurableApplicationContext service = service().run(create)) {
            assertThat(service.getBean(JpaStoreFactory.BEAN_NAME, JpaStoreFactory.class).vendorProperties("books"))
                    .containsEntry("hibernate.hbm2ddl.halt_on_error", "true")
                    .containsEntry("hibernate.hbm2ddl.auto", "none");
            Book book = service.getBean(BookRepository.class).save(new Book("Created", "978-0-00-000006-0"));
            Review review = service.getBean(ReviewRepository.class).save(new Review(book, 5));
            assertThat(review.getId()).isEqualTo(1L);
        }
        assertThat(names(BOOKS, url, TABLES)).containsExactly("book", "review");
        assertThat(names(BOOKS, url, SEQUENCES)).containsExactly("review_seq");
        assertThat(names(BOOKS, url, FOREIGN_KEYS)).containsExactly("review -> book");
        assertThat(names(BOOKS, url, "SELECT title FROM book")).containsExactly("Created");

        // Again, on the database the first run filled: its drop phase now drops what the first run created.
        try (ConfigurableApplicationContext service = service().run(create)) {
            assertThat(service.getBean(BookRepository.class).count()).isZero();
            assertThat(service.getBean(ReviewRepository.class).count()).isZero();
        }
        assertThat(names(BOOKS, url, TABLES)).containsExactly("book", "review");
        assertThat(names(BOOKS, url, SEQUENCES)).containsExactly("review_seq");
        assertThat(names(BOOKS, url, FOREIGN_KEYS)).containsExactly("review -> book");
    }
}
