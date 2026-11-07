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
package io.americanexpress.synapse.test.data.slice;

import java.time.Clock;
import java.time.Instant;
import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.americanexpress.synapse.core.operation.ServiceOperation;
import io.americanexpress.synapse.test.data.SynapseDataTest;
import io.americanexpress.synapse.test.data.slice.books.Book;
import io.americanexpress.synapse.test.data.slice.books.BookRepository;
import io.americanexpress.synapse.test.time.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A slice for the books store, in an application whose test settings configure the books and legacy stores: only the
 * books store starts, migrated, and each test runs in a books transaction that is rolled back.
 */
@SynapseDataTest("books")
@Tag("smoke")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SynapseDataTestIT {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @Autowired
    private BookRepository books;

    @Autowired
    @Qualifier("books")
    private JdbcClient jdbc;

    @Autowired
    @Qualifier("books")
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private Clock clock;

    @Test
    void startsOnlyThatStore() {
        assertThat(context.containsBean("booksDataSource")).isTrue();
        assertThat(context.containsBean("bookRepository")).isTrue();
        assertThat(context.containsBean("legacyDataSource")).isFalse();
        assertThat(context.containsBeanDefinition("legacyStoreDefinition")).isFalse();
        assertThat(context.getBeansOfType(ServiceOperation.class)).isEmpty();
        assertThat(context.containsBeanDefinition("countBooksOperation")).isFalse();
    }

    @Test
    void migrationsRanForTheStore() {
        assertThat(jdbc
                .sql("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) = 'book'")
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void runsInATransactionOfTheStore() {
        assertThat(TestTransaction.isActive()).isTrue();
        assertThat(TestTransaction.isFlaggedForRollback()).isTrue();
        assertThat(TransactionSynchronizationManager.hasResource(entityManagerFactory))
                .as("the books EntityManagerFactory is bound")
                .isTrue();
    }

    @Test
    @Order(1)
    void eachTestRollsBack() {
        books.saveAndFlush(new Book("Dune"));

        assertThat(jdbc.sql("SELECT COUNT(*) FROM book").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    @Order(2)
    void theNextTestSeesNoRows() {
        assertThat(books.count()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM book").query(Integer.class).single()).isZero();
    }

    @Test
    void clockIsTheMutableTestClock() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        assertThat(clock).isInstanceOf(MutableClock.class);

        Book book = books.saveAndFlush(new Book("Emma"));

        assertThat(book.getCreatedAt()).isEqualTo(start);
        assertThat(book.getModifiedAt()).isEqualTo(start);
        assertThat(jdbc
                .sql("SELECT created_at FROM book WHERE id = ?")
                .param(book.getId())
                .query(Instant.class)
                .single()).isEqualTo(start);
    }

    @Test
    void runsAsTest() {
        assertThat(environment.getActiveProfiles()).containsExactly("test");
    }
}
