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
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.data.jdbc.core.dialect.JdbcPostgresDialect;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.data.postgres.fixture.books.Book;
import io.americanexpress.synapse.data.postgres.fixture.books.BookRepository;
import io.americanexpress.synapse.data.postgres.fixture.legacy.LegacyItem;
import io.americanexpress.synapse.data.postgres.fixture.legacy.LegacyItemRepository;
import io.americanexpress.synapse.test.data.StoreConnection;
import io.americanexpress.synapse.test.data.SynapseContainers;

import static io.americanexpress.synapse.data.postgres.PostgresServices.FOREIGN_KEYS;
import static io.americanexpress.synapse.data.postgres.PostgresServices.SEQUENCES;
import static io.americanexpress.synapse.data.postgres.PostgresServices.TABLES;
import static io.americanexpress.synapse.data.postgres.PostgresServices.arguments;
import static io.americanexpress.synapse.data.postgres.PostgresServices.names;
import static io.americanexpress.synapse.data.postgres.PostgresServices.service;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The fixture service on PostgreSQL (Docker), as a deployed service runs: the books store (JPA) on one container and
 * the legacy store (JDBC) on another, neither primary, configured by the data module's {@code META-INF/synapse} files
 * (acceptance 8). The tests that need other settings start the service again in a context of their own.
 */
@SpringBootTest(classes = PostgresTestApplication.class)
@ActiveProfiles("test")
@Import(PostgresTwoStoresIT.LegacyWriter.class)
@ExtendWith(OutputCaptureExtension.class)
class PostgresTwoStoresIT {

    @StoreConnection("books")
    static final PostgreSQLContainer BOOKS = SynapseContainers.postgres("books");

    @StoreConnection("legacy")
    static final PostgreSQLContainer LEGACY = SynapseContainers.postgres("legacy");

    @Autowired
    private ConfigurableApplicationContext context;

    @Autowired
    private LegacyWriter legacyWriter;

    @Autowired
    @Qualifier("books")
    private JdbcClient books;

    @Autowired
    @Qualifier("legacy")
    private JdbcClient legacy;

    @Test
    void eachStoreHasItsOwnInfrastructure() throws SQLException {
        HikariDataSource booksPool = context.getBean("booksDataSource", HikariDataSource.class);
        HikariDataSource legacyPool = context.getBean("legacyDataSource", HikariDataSource.class);
        assertThat(booksPool.getJdbcUrl()).isEqualTo(BOOKS.getJdbcUrl());
        assertThat(legacyPool.getJdbcUrl()).isEqualTo(LEGACY.getJdbcUrl());
        assertThat(booksPool.getPoolName()).isEqualTo("books");
        assertThat(legacyPool.getPoolName()).isEqualTo("legacy");
        assertThat(context.getBeanFactory().getMergedBeanDefinition("booksDataSource").isPrimary()).isFalse();
        assertThat(context.getBeanFactory().getMergedBeanDefinition("legacyDataSource").isPrimary()).isFalse();

        assertThat(context.getBean("booksEntityManagerFactory")).isInstanceOf(EntityManagerFactory.class);
        assertThat(context.getBean("booksTransactionManager")).isInstanceOf(JpaTransactionManager.class);
        assertThat(context.getBean("legacyTransactionManager")).isInstanceOf(JdbcTransactionManager.class);
        assertThat(context.getBean("legacyJdbcAggregateTemplate")).isInstanceOf(JdbcAggregateTemplate.class);
        assertThat(context.getBean("legacyJdbcDialect")).isSameAs(JdbcPostgresDialect.INSTANCE);
        assertThat(context.containsBean("legacyEntityManagerFactory")).isFalse();
        assertThat(context.containsBean("booksJdbcAggregateTemplate")).isFalse();
        assertThat(context.getBeanNamesForType(BookRepository.class)).containsExactly("bookRepository");
        assertThat(context.getBeanNamesForType(LegacyItemRepository.class)).containsExactly("legacyItemRepository");

        CompositeHealthContributor db = context.getBean("dbHealthContributor", CompositeHealthContributor.class);
        assertThat(status(db, "books")).isEqualTo(Status.UP);
        assertThat(status(db, "legacy")).isEqualTo(Status.UP);

        String history = "SELECT script FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank";
        assertThat(books.sql(history).query(String.class).list()).containsExactly("V1__books.sql");
        assertThat(legacy.sql(history).query(String.class).list()).containsExactly("V1__legacy.sql");
        assertThat(names(BOOKS, BOOKS.getJdbcUrl(), TABLES))
                .containsExactly("book", "flyway_schema_history",
                        "review");
        assertThat(names(BOOKS, BOOKS.getJdbcUrl(), SEQUENCES)).containsExactly("review_seq");
        assertThat(names(LEGACY, LEGACY.getJdbcUrl(), TABLES))
                .containsExactly("flyway_schema_history",
                        "legacy_item");
    }

    /**
     * A {@code @Transactional("legacy")} bean writes through the legacy store's transaction, to the legacy container
     * only, and its rollback undoes the write there.
     */
    @Test
    void transactionalLegacyTargetsLegacy() throws SQLException {
        String name = "written-" + UUID.randomUUID();
        String rolledBack = "rolled-back-" + UUID.randomUUID();
        try {
            assertThat(legacyWriter.write(name)).isTrue();
            assertThat(catchThrowable(() -> legacyWriter.writeAndFail(rolledBack)))
                    .isInstanceOf(IllegalStateException.class);

            String items = "SELECT name FROM legacy_item WHERE name IN ('" + name + "', '" + rolledBack + "')";
            assertThat(names(LEGACY, LEGACY.getJdbcUrl(), items)).containsExactly(name);
            assertThat(names(BOOKS, BOOKS.getJdbcUrl(), TABLES)).doesNotContain("legacy_item");
        } finally {
            legacy.sql("DELETE FROM legacy_item WHERE name IN (?, ?)").params(name, rolledBack).update();
        }
    }

    @Test
    void unqualifiedTransactionalFails() {
        Throwable failure = catchThrowable(
                () -> service(UnqualifiedService.class).run(arguments(BOOKS, LEGACY)).close());

        assertThat(NestedExceptionUtils.getMostSpecificCause(failure))
                .isInstanceOf(SynapseConfigurationException.class)
                .hasMessage("There are 2 stores and none is primary, so an unqualified @Transactional can't choose "
                        + "a transaction manager: " + UnqualifiedService.class.getName() + "#save.");
    }

    /**
     * The WARN, and the INFO budget line with the pool sizes of the data module's {@code config.yaml}. Runs under
     * Maven only: see {@link #assertTheDataModuleIsOnTheClassPath()}.
     */
    @Test
    void springDatasourceUrlWarns(CapturedOutput output) {
        assertTheDataModuleIsOnTheClassPath();
        try (ConfigurableApplicationContext service = service()
                .run(arguments(BOOKS, LEGACY, "--spring.datasource.url=jdbc:postgresql://ignored-host/ignored"))) {
            assertThat(service.isRunning()).isTrue();
        }

        assertThat(output)
                .contains("spring.datasource.* is set (spring.datasource.url), but Synapse data stores don't use "
                        + "it. Configure each store under synapse.data.stores.<name>.")
                .contains("Synapse data stores: books (pool 4), legacy (pool 3); at most 7 connections per "
                        + "instance.")
                .doesNotContain("ignored-host");
    }

    /**
     * Acceptance 8: {@code ddl-auto: update} fails startup; with {@code allow-ddl-auto} it starts, and Hibernate's
     * update builds the books schema in an empty database (migrations off), foreign key and sequence included.
     */
    @Test
    void ddlAutoUpdateFailsUnlessAllowed() throws SQLException {
        Throwable failure = catchThrowable(() -> service()
                .run(arguments(BOOKS, LEGACY, "--synapse.data.stores.books.jpa.hibernate.ddl-auto=update"))
                .close());

        assertThat(NestedExceptionUtils.getMostSpecificCause(failure))
                .isInstanceOf(SynapseConfigurationException.class)
                .hasMessage("The store 'books' sets ddl-auto to update, which changes the schema at startup.");

        String url = PostgresServices.emptyDatabase(BOOKS, "update_check");
        try (ConfigurableApplicationContext service = service()
                .run(arguments(BOOKS, url, LEGACY, "--synapse.data.stores.books.jpa.hibernate.ddl-auto=update",
                        "--synapse.data.stores.books.allow-ddl-auto=true",
                        "--synapse.data.stores.books.migrations.enabled=false"))) {
            BookRepository repository = service.getBean(BookRepository.class);
            repository.save(new Book("Updated", "978-0-00-000004-0"));
            assertThat(repository.count()).isOne();
        }
        assertThat(names(BOOKS, url, TABLES)).containsExactly("book", "review");
        assertThat(names(BOOKS, url, SEQUENCES)).containsExactly("review_seq");
        assertThat(names(BOOKS, url, FOREIGN_KEYS)).containsExactly("review -> book");
    }

    /**
     * The data module's {@code META-INF/synapse/config.yaml} sets the pool sizes, and its {@code config-test.yaml}
     * the connection timeouts of the test environment; the service's {@code application-test.yaml} sets neither. Runs
     * under Maven only: see {@link #assertTheDataModuleIsOnTheClassPath()}.
     */
    @Test
    void moduleConfigFilesConfigureTheStore() {
        assertTheDataModuleIsOnTheClassPath();
        HikariDataSource booksPool = context.getBean("booksDataSource", HikariDataSource.class);
        HikariDataSource legacyPool = context.getBean("legacyDataSource", HikariDataSource.class);

        assertThat(booksPool.getMaximumPoolSize()).isEqualTo(4);
        assertThat(legacyPool.getMaximumPoolSize()).isEqualTo(3);
        assertThat(booksPool.getConnectionTimeout()).isEqualTo(5000);
        assertThat(legacyPool.getConnectionTimeout()).isEqualTo(5000);
        assertThat(context.getEnvironment().getProperty("synapse.data.stores.books.hikari.maximum-pool-size"))
                .isEqualTo("4");
    }

    /**
     * PostgreSQL driver settings in {@code jpa.properties} under {@code hibernate.connection.} (such as {@code PGHOST},
     * {@code PGDBNAME} or {@code sslmode}) change nothing: Hibernate uses the store's pool, which connects as the
     * store's {@code url}, {@code username} and {@code password} say, and never logs their values.
     */
    @Test
    void postgresDriverKeysInJpaPropertiesChangeNothing(CapturedOutput output) {
        String prefix = "--synapse.data.stores.books.jpa.properties.hibernate.connection.";
        try (ConfigurableApplicationContext service = service()
                .run(arguments(BOOKS, LEGACY, prefix + "PGHOST=elsewhere-host", prefix + "PGPORT=1",
                        prefix + "PGDBNAME=elsewhere", prefix + "sslmode=require", prefix + "sslpassword=s3cr3t"))) {
            JdbcClient jdbc = service.getBean("booksJdbcClient", JdbcClient.class);
            BookRepository repository = service.getBean(BookRepository.class);
            String isbn = "978-0-00-000005-0";
            try {
                repository.save(new Book("Connected", isbn));
                assertThat(jdbc.sql("SELECT current_database()").query(String.class).single())
                        .isEqualTo(BOOKS.getDatabaseName());
                assertThat(jdbc
                        .sql("SELECT COUNT(*) FROM book WHERE isbn = ?")
                        .param(isbn)
                        .query(Integer.class)
                        .single()).isOne();
            } finally {
                jdbc.sql("DELETE FROM book WHERE isbn = ?").param(isbn).update();
            }
        }
        assertThat(output).doesNotContain("s3cr3t", "elsewhere-host");
    }

    /**
     * The fixture data module's {@code META-INF/synapse} files are in {@code src/test/data-module}, a class-path root
     * of their own that only Maven's failsafe adds ({@code additionalClasspathElement} in this module's pom): beside
     * {@code application-test.yaml}, Synapse would fail startup. An IDE run doesn't have them.
     */
    private static void assertTheDataModuleIsOnTheClassPath() {
        assertThat(PostgresTwoStoresIT.class.getClassLoader().getResource("META-INF/synapse/config.yaml"))
                .as("The fixture data module (src/test/data-module/META-INF/synapse) is not on the class path. Maven's "
                        + "failsafe adds it (additionalClasspathElement in synapse-data-postgres/pom.xml); run this "
                        + "test with ./mvnw verify, or add src/test/data-module to the IDE's test class path.")
                .isNotNull();
    }

    /** The status of a store's indicator in the {@code db} contributor. */
    private static Status status(CompositeHealthContributor db, String store) {
        HealthIndicator indicator = (HealthIndicator) Objects
                .requireNonNull(db.getContributor(store), "the indicator of " + store);
        return Objects.requireNonNull(indicator.health(), "the health of " + store).getStatus();
    }

    /** Writes legacy items in the legacy store's transaction. */
    static class LegacyWriter {

        private final LegacyItemRepository items;

        private final DataSource legacyDataSource;

        private final DataSource booksDataSource;

        LegacyWriter(LegacyItemRepository items, @Qualifier("legacy") DataSource legacyDataSource,
                @Qualifier("books") DataSource booksDataSource) {
            this.items = items;
            this.legacyDataSource = legacyDataSource;
            this.booksDataSource = booksDataSource;
        }

        /**
         * Saves an item.
         *
         * @param name the item's name
         * @return whether the write ran in a transaction on the legacy store's connection only
         */
        @Transactional("legacy")
        public boolean write(String name) {
            items.save(new LegacyItem(name));
            return TransactionSynchronizationManager.hasResource(legacyDataSource)
                    && !TransactionSynchronizationManager.hasResource(booksDataSource);
        }

        /**
         * Saves an item, then fails, which rolls the save back.
         *
         * @param name the item's name
         */
        @Transactional("legacy")
        public void writeAndFail(String name) {
            items.save(new LegacyItem(name));
            throw new IllegalStateException("fails after the write");
        }
    }

    /** A bean whose {@code @Transactional} names no store. */
    static class UnqualifiedService {

        /** Saves nothing. */
        @Transactional
        public void save() {
        }
    }
}
