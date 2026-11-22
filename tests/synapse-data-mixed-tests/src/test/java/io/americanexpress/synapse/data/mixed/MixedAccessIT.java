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
package io.americanexpress.synapse.data.mixed;

import java.util.Objects;
import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.data.autoconfigure.SynapseDataAutoConfiguration;
import io.americanexpress.synapse.data.jdbc.autoconfigure.SynapseDataJdbcAutoConfiguration;
import io.americanexpress.synapse.data.jpa.autoconfigure.SynapseDataJpaAutoConfiguration;
import io.americanexpress.synapse.data.mixed.fixture.H2TestVendor;
import io.americanexpress.synapse.data.mixed.fixture.auto.OrdersStore;
import io.americanexpress.synapse.data.mixed.fixture.books.Book;
import io.americanexpress.synapse.data.mixed.fixture.books.BookRepository;
import io.americanexpress.synapse.data.mixed.fixture.books.BooksStore;
import io.americanexpress.synapse.data.mixed.fixture.legacy.LegacyItem;
import io.americanexpress.synapse.data.mixed.fixture.legacy.LegacyItemRepository;
import io.americanexpress.synapse.data.mixed.fixture.legacy.LegacyStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With synapse-data-jpa and synapse-data-jdbc on the class path, a store that doesn't name its access fails startup,
 * and stores that name it get that access: books uses JPA and legacy uses JDBC, in one application.
 */
@Tag("smoke")
@SpringBootTest(classes = MixedAccessIT.TestApplication.class, properties = {
        "synapse.data.stores.books.url=jdbc:h2:mem:mixed-books;DB_CLOSE_DELAY=-1",
        "synapse.data.stores.books.username=sa", "synapse.data.stores.books.password=",
        "synapse.data.stores.legacy.url=jdbc:h2:mem:mixed-legacy;DB_CLOSE_DELAY=-1",
        "synapse.data.stores.legacy.username=sa", "synapse.data.stores.legacy.password="})
@ActiveProfiles("test")
class MixedAccessIT {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Writers writers;

    @Autowired
    @Qualifier("books")
    private JdbcClient booksJdbc;

    @Autowired
    @Qualifier("legacy")
    private JdbcClient legacyJdbc;

    @Test
    void autoAccessIsAmbiguous() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations
                        .of(SynapseCoreAutoConfiguration.class, SynapseDataAutoConfiguration.class,
                                SynapseDataJpaAutoConfiguration.class, SynapseDataJdbcAutoConfiguration.class))
                .withUserConfiguration(H2TestVendor.class, OrdersStore.class)
                .withPropertyValues("synapse.data.stores.orders.url=jdbc:h2:mem:mixed-orders;DB_CLOSE_DELAY=-1",
                        "synapse.data.stores.orders.username=sa", "synapse.data.stores.orders.password=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(NestedExceptionUtils
                            .getMostSpecificCause(Objects.requireNonNull(context.getStartupFailure())))
                            .isInstanceOfSatisfying(SynapseConfigurationException.class, failure -> {
                                assertThat(failure.description())
                                        .isEqualTo("The store 'orders' must name its access: both synapse-data-jpa "
                                                + "and synapse-data-jdbc are present.");
                                assertThat(failure.action())
                                        .isEqualTo("Use @SynapseDataStore(value = \"orders\", access = "
                                                + "DataAccess.JPA) or DataAccess.JDBC.");
                            });
                });
    }

    @Test
    void eachStoreNamesItsAccess() {
        assertThat(context.getBean("booksTransactionManager")).isInstanceOf(JpaTransactionManager.class);
        assertThat(context.getBean("booksEntityManagerFactory")).isInstanceOf(EntityManagerFactory.class);
        assertThat(context.containsBean("booksJdbcAggregateTemplate")).isFalse();
        assertThat(context.getBean("legacyTransactionManager")).isInstanceOf(JdbcTransactionManager.class);
        assertThat(context.getBean("legacyJdbcAggregateTemplate")).isInstanceOf(JdbcAggregateTemplate.class);
        assertThat(context.containsBean("legacyEntityManagerFactory")).isFalse();

        writers.saveBook(1, "Dune");
        writers.saveLegacyItem("lamp");

        assertThat(booksJdbc.sql("SELECT title FROM book WHERE id = 1").query(String.class).single())
                .isEqualTo("Dune");
        assertThat(legacyJdbc.sql("SELECT name FROM legacy_item").query(String.class).list()).containsExactly("lamp");
    }

    /** Writes through each store's repository, in that store's transactions. */
    public static class Writers {

        private final BookRepository books;

        private final LegacyItemRepository legacyItems;

        Writers(BookRepository books, LegacyItemRepository legacyItems) {
            this.books = books;
            this.legacyItems = legacyItems;
        }

        @Transactional("books")
        public void saveBook(long id, String title) {
            books.save(new Book(id, title));
        }

        @Transactional("legacy")
        public void saveLegacyItem(String name) {
            legacyItems.save(new LegacyItem(name));
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({H2TestVendor.class, BooksStore.class, LegacyStore.class})
    static class TestApplication {

        @Bean
        Writers writers(BookRepository books, LegacyItemRepository legacyItems) {
            return new Writers(books, legacyItems);
        }
    }
}
