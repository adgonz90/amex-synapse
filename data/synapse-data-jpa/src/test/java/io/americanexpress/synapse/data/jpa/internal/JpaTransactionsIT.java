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

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.americanexpress.synapse.data.jpa.fixture.H2TestVendor;
import io.americanexpress.synapse.data.jpa.fixture.books.Book;
import io.americanexpress.synapse.data.jpa.fixture.books.BooksStore;
import io.americanexpress.synapse.data.jpa.fixture.legacy.LegacyItem;
import io.americanexpress.synapse.data.jpa.fixture.legacy.LegacyStore;
import io.americanexpress.synapse.data.jpa.fixture.legacyorders.LegacyOrder;
import io.americanexpress.synapse.data.jpa.fixture.legacyorders.LegacyOrdersStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots an application with three H2 stores and no primary, each migrated from its own {@code db/migration/<store>},
 * and checks that {@code @Transactional("<store>")} runs on that store's EntityManagerFactory.
 */
@Tag("smoke")
@SpringBootTest(classes = JpaTransactionsIT.TestApplication.class, properties = {
        "synapse.data.stores.books.url=jdbc:h2:mem:jpa-tx-books;DB_CLOSE_DELAY=-1",
        "synapse.data.stores.books.username=sa", "synapse.data.stores.books.password=",
        "synapse.data.stores.legacy.url=jdbc:h2:mem:jpa-tx-legacy;DB_CLOSE_DELAY=-1",
        "synapse.data.stores.legacy.username=sa", "synapse.data.stores.legacy.password=",
        "synapse.data.stores.legacy-orders.url=jdbc:h2:mem:jpa-tx-legacy-orders;DB_CLOSE_DELAY=-1",
        "synapse.data.stores.legacy-orders.username=sa", "synapse.data.stores.legacy-orders.password="})
@ActiveProfiles("test")
class JpaTransactionsIT {

    @Autowired
    private Writers writers;

    @Autowired
    @Qualifier("books")
    private JdbcClient books;

    @Autowired
    @Qualifier("legacy")
    private JdbcClient legacy;

    @Autowired
    @Qualifier("legacy-orders")
    private JdbcClient legacyOrders;

    @Test
    void transactionalWithAStoreNameUsesThatStore() {
        Binding binding = writers.saveLegacyItem(1, "lamp");

        assertThat(binding.legacy).as("the legacy EntityManagerFactory is bound").isTrue();
        assertThat(binding.books).as("the books EntityManagerFactory is not bound").isFalse();
        assertThat(legacy.sql("SELECT name FROM legacy_item WHERE id = 1").query(String.class).single())
                .isEqualTo("lamp");
        assertThat(books
                .sql("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) = 'legacy_item'")
                .query(Integer.class)
                .single()).as("the books store has no legacy items").isZero();
    }

    @Test
    void rollbackOnException() {
        assertThatThrownBy(() -> writers.saveBookThenFail(2, "Dune"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("after the flush");

        assertThat(books.sql("SELECT COUNT(*) FROM book WHERE id = 2").query(Integer.class).single()).isZero();
    }

    @Test
    void commitsABook() {
        writers.saveBook(3, "Emma");

        assertThat(books.sql("SELECT title FROM book WHERE id = 3").query(String.class).single()).isEqualTo("Emma");
    }

    @Test
    void dashedStoreTransactions() {
        Binding binding = writers.saveLegacyOrder(4, "LO-4");

        assertThat(binding.legacyOrders).as("the legacy-orders EntityManagerFactory is bound").isTrue();
        assertThat(binding.legacy).isFalse();
        assertThat(legacyOrders.sql("SELECT reference FROM legacy_order WHERE id = 4").query(String.class).single())
                .isEqualTo("LO-4");
    }

    /** Which stores' EntityManagerFactories a transaction had bound. */
    record Binding(boolean books, boolean legacy, boolean legacyOrders) {
    }

    /** Writes through each store's shared EntityManager, in that store's transactions. */
    public static class Writers {

        @PersistenceContext(unitName = "books")
        private @Nullable EntityManager books;

        @PersistenceContext(unitName = "legacy")
        private @Nullable EntityManager legacy;

        @PersistenceContext(unitName = "legacy-orders")
        private @Nullable EntityManager legacyOrders;

        private final EntityManagerFactory booksFactory;

        private final EntityManagerFactory legacyFactory;

        private final EntityManagerFactory legacyOrdersFactory;

        Writers(EntityManagerFactory booksFactory, EntityManagerFactory legacyFactory,
                EntityManagerFactory legacyOrdersFactory) {
            this.booksFactory = booksFactory;
            this.legacyFactory = legacyFactory;
            this.legacyOrdersFactory = legacyOrdersFactory;
        }

        @Transactional("legacy")
        public Binding saveLegacyItem(long id, String name) {
            entityManager(legacy).persist(new LegacyItem(id, name));
            return binding();
        }

        @Transactional("books")
        public void saveBook(long id, String title) {
            entityManager(books).persist(new Book(id, title));
        }

        @Transactional("books")
        public void saveBookThenFail(long id, String title) {
            EntityManager entityManager = entityManager(books);
            entityManager.persist(new Book(id, title));
            entityManager.flush();
            throw new IllegalStateException("after the flush");
        }

        @Transactional("legacy-orders")
        public Binding saveLegacyOrder(long id, String reference) {
            entityManager(legacyOrders).persist(new LegacyOrder(id, reference));
            return binding();
        }

        private Binding binding() {
            return new Binding(TransactionSynchronizationManager.hasResource(booksFactory),
                    TransactionSynchronizationManager.hasResource(legacyFactory),
                    TransactionSynchronizationManager.hasResource(legacyOrdersFactory));
        }

        private static EntityManager entityManager(@Nullable EntityManager entityManager) {
            if (entityManager == null) {
                throw new IllegalStateException("not injected");
            }
            return entityManager;
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({H2TestVendor.class, BooksStore.class, LegacyStore.class, LegacyOrdersStore.class})
    static class TestApplication {

        @Bean
        Writers writers(@Qualifier("books") EntityManagerFactory books,
                @Qualifier("legacy") EntityManagerFactory legacy,
                @Qualifier("legacy-orders") EntityManagerFactory legacyOrders) {
            return new Writers(books, legacy, legacyOrders);
        }
    }
}
