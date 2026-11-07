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

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.hibernate.cfg.Environment;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.Ordered;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.data.autoconfigure.SynapseDataAutoConfiguration;
import io.americanexpress.synapse.data.internal.store.StoreStartStep;
import io.americanexpress.synapse.data.jpa.autoconfigure.SynapseDataJpaAutoConfiguration;
import io.americanexpress.synapse.data.jpa.fixture.H2TestVendor;
import io.americanexpress.synapse.data.jpa.fixture.books.BooksStore;
import io.americanexpress.synapse.data.jpa.fixture.brokenddl.BrokenDdlStore;
import io.americanexpress.synapse.data.jpa.fixture.factoryemf.BooksStoreWithFactoryEmf;
import io.americanexpress.synapse.data.jpa.fixture.isbn.IsbnBooksStore;
import io.americanexpress.synapse.data.jpa.fixture.legacy.LegacyStore;
import io.americanexpress.synapse.data.jpa.fixture.ownemf.BooksStoreWithOwnEntityManagerFactory;
import io.americanexpress.synapse.data.jpa.fixture.shelves.ShelvesStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs each schema action against H2, in the store's {@code start()}, after its migrations.
 */
@ExtendWith(OutputCaptureExtension.class)
class JpaSchemaActionIT {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations
                    .of(SynapseCoreAutoConfiguration.class, SynapseDataAutoConfiguration.class,
                            SynapseDataJpaAutoConfiguration.class,
                            DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
                            TransactionManagerCustomizationAutoConfiguration.class,
                            TransactionAutoConfiguration.class))
            .withUserConfiguration(H2TestVendor.class);

    @Tag("smoke")
    @Test
    void validatePassesAfterMigrations() {
        runner
                .withUserConfiguration(BooksStore.class)
                .withPropertyValues(store(url("validate")))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("booksSchemaAction", JpaSchemaAction.class).action())
                            .isEqualTo("validate");
                    assertThat(columns(url("validate"), "book")).contains("title");
                });
    }

    /** Hibernate's schema tool resolves its own dialect, which must get the store's database version too. */
    @Test
    void theSchemaToolKnowsTheDatabaseVersion(CapturedOutput output) {
        runner
                .withUserConfiguration(BooksStore.class)
                .withPropertyValues(store(url("version")))
                .run(context -> assertThat(context).hasNotFailed());
        assertThat(output).doesNotContain("HHH000511");
    }

    @Test
    void validateFailsOnAMismatch() {
        runner
                .withUserConfiguration(IsbnBooksStore.class)
                .withPropertyValues(store(url("mismatch")))
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable root = NestedExceptionUtils
                            .getMostSpecificCause(Objects.requireNonNull(context.getStartupFailure()));
                    assertThat(root).isInstanceOfSatisfying(SynapseConfigurationException.class, failure -> {
                        assertThat(failure.description())
                                .startsWith("The schema of the store 'books' does not match its entities: ")
                                .contains("isbn")
                                .contains("book");
                        assertThat(failure.action())
                                .isEqualTo("Add a migration in classpath:db/migration/books that brings the schema "
                                        + "in line, or fix the mapping.");
                    });
                });
    }

    @Test
    void createBuildsTheSchemaInStart() {
        runner
                .withUserConfiguration(BooksStore.class, BookTableProbe.class)
                .withPropertyValues(store(url("create")))
                .withPropertyValues("synapse.data.stores.books.allow-ddl-auto=true",
                        "synapse.data.stores.books.jpa.hibernate.ddl-auto=create",
                        "synapse.data.stores.books.migrations.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(BookTableProbe.class).existedBefore)
                            .as("the table when the step ordered before the schema action started")
                            .isFalse();
                    assertThat(columns(url("create"), "book")).containsExactlyInAnyOrder("id", "title");
                });
    }

    @Test
    void updateAddsAColumn() {
        runner
                .withUserConfiguration(IsbnBooksStore.class)
                .withPropertyValues(store(url("update")))
                .withPropertyValues("synapse.data.stores.books.allow-ddl-auto=true",
                        "synapse.data.stores.books.jpa.hibernate.ddl-auto=update")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(columns(url("update"), "book")).contains("id", "title", "isbn");
                });
    }

    /**
     * Hibernate hands over the mapping model update needs only for the EntityManagerFactory Synapse builds. The failure
     * comes when the beans are created, before any migration runs.
     */
    @Test
    void updateNeedsTheEntityManagerFactorySynapseBuilds() throws SQLException {
        runner
                .withUserConfiguration(BooksStoreWithOwnEntityManagerFactory.class)
                .withPropertyValues(store(url("update-own")))
                .withPropertyValues("synapse.data.stores.books.allow-ddl-auto=true",
                        "synapse.data.stores.books.jpa.hibernate.ddl-auto=update")
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable root = NestedExceptionUtils
                            .getMostSpecificCause(Objects.requireNonNull(context.getStartupFailure()));
                    assertThat(root).isInstanceOfSatisfying(SynapseConfigurationException.class, failure -> {
                        assertThat(failure.description())
                                .isEqualTo("The store 'books' sets ddl-auto to update, which Synapse runs only on the "
                                        + "EntityManagerFactory it builds, and the application defines "
                                        + "booksEntityManagerFactory.");
                        assertThat(failure.action())
                                .isEqualTo("Remove the application's booksEntityManagerFactory bean, or change the "
                                        + "schema with a migration in classpath:db/migration/books.");
                    });
                });
        assertThat(tables(url("update-own"))).as("no migration ran").isEmpty();
    }

    /**
     * Minor 14: who defines the EntityManagerFactory is read from its bean definition (is it made by
     * {@code synapseJpaStoreFactory}?), not inferred from the mapping model being kept: a bean the application defines
     * is the application's, even when it calls Synapse's factory.
     */
    @Test
    void updateIsDecidedFromTheBeanDefinition() {
        runner
                .withUserConfiguration(BooksStoreWithFactoryEmf.class)
                .withPropertyValues(store(url("update-factory-built")))
                .withPropertyValues("synapse.data.stores.books.allow-ddl-auto=true",
                        "synapse.data.stores.books.jpa.hibernate.ddl-auto=update")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(NestedExceptionUtils
                            .getMostSpecificCause(Objects.requireNonNull(context.getStartupFailure())))
                            .isInstanceOfSatisfying(SynapseConfigurationException.class,
                                    failure -> assertThat(failure.description())
                                            .isEqualTo("The store 'books' sets ddl-auto to update, which Synapse "
                                                    + "runs only on the EntityManagerFactory it builds, and the "
                                                    + "application defines booksEntityManagerFactory."));
                });
    }

    /**
     * Review Focus 3: Hibernate prefers {@code hibernate.hbm2ddl.auto.<contributor>} to the root key, so it would run
     * while the EntityManagerFactory is built, before the migrations. The guard rejects it, and nothing is created.
     */
    @Test
    void aContributorQualifiedHbm2ddlKeyChangesNothing() throws SQLException {
        runner
                .withUserConfiguration(BooksStore.class)
                .withPropertyValues(store(url("orm-key")))
                .withPropertyValues("synapse.data.stores.books.jpa.properties.hibernate.hbm2ddl.auto.orm=update")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(NestedExceptionUtils
                            .getMostSpecificCause(Objects.requireNonNull(context.getStartupFailure())))
                            .isInstanceOfSatisfying(SynapseConfigurationException.class,
                                    failure -> assertThat(failure.description())
                                            .isEqualTo("The store 'books' sets hibernate.hbm2ddl.auto.orm in "
                                                    + "synapse.data.stores.books.jpa.properties."));
                });
        assertThat(tables(url("orm-key"))).isEmpty();
    }

    /**
     * Hibernate also reads schema keys from system properties and a class-path {@code hibernate.properties}, through
     * the copy of them its {@code Environment} class makes once, when it loads. That class is loaded before the key is
     * set here, so a system property would never reach it: the test writes the key into that copy, which is what
     * Hibernate reads. A root key and one qualified with Hibernate's default contributor ({@code .orm}, which Hibernate
     * prefers to the root one) both fail startup before Hibernate runs anything; so does {@code none} for a
     * contributor-qualified action, which would turn the store's own action into a no-op.
     *
     * @param key the schema key Hibernate's global settings set
     * @param value its value
     */
    @ParameterizedTest
    @CsvSource({"jakarta.persistence.schema-generation.database.action, create",
            "jakarta.persistence.schema-generation.database.action.orm, create",
            "jakarta.persistence.schema-generation.database.action.orm, none",
            "javax.persistence.schema-generation.database.action.orm, none",
            "hibernate.hbm2ddl.import_files, none"})
    void aGlobalSchemaKeyFailsStartup(String key, String value) throws ReflectiveOperationException, SQLException {
        String database = "global-" + key.replace('.', '-') + "-" + value;
        withGlobalSetting(key, value, () -> runner
                .withUserConfiguration(BooksStore.class)
                .withPropertyValues(store(url(database)))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(NestedExceptionUtils
                            .getMostSpecificCause(Objects.requireNonNull(context.getStartupFailure())))
                            .isInstanceOfSatisfying(SynapseConfigurationException.class, failure -> {
                                assertThat(failure.description())
                                        .isEqualTo("Hibernate's global settings (system properties or "
                                                + "hibernate.properties) set " + key + ", which would change the "
                                                + "schema of the store 'books' behind its ddl-auto.");
                                assertThat(failure.action())
                                        .isEqualTo("Remove " + key + " from the JVM's system properties and "
                                                + "hibernate.properties, and use "
                                                + "synapse.data.stores.books.jpa.hibernate.ddl-auto instead.");
                                assertThat(failure.description() + failure.action()).doesNotContain(value);
                            });
                }));
        assertThat(tables(url(database))).as("nothing ran").isEmpty();
    }

    /**
     * Global settings may turn the legacy and root actions off, as {@code -Dhibernate.hbm2ddl.auto=none} does: Synapse's
     * own settings already say none for them, and the store's action still runs when it starts. Here it validates a
     * schema that doesn't match, and fails.
     *
     * @param key the schema key Hibernate's global settings set to none
     * @param value the way the value is written
     */
    @ParameterizedTest
    @CsvSource({"hibernate.hbm2ddl.auto, none", "hibernate.hbm2ddl.auto.orm, ' NONE '",
            "jakarta.persistence.schema-generation.database.action, None",
            "jakarta.persistence.schema-generation.scripts.action, none",
            "javax.persistence.schema-generation.database.action, none",
            "javax.persistence.schema-generation.scripts.action, none"})
    void aGlobalNoneForTheRootActionsIsAllowed(String key, String value) throws ReflectiveOperationException {
        withGlobalSetting(key, value, () -> {
            runner
                    .withUserConfiguration(BooksStore.class)
                    .withPropertyValues(store(url("global-none-" + key.replace('.', '-'))))
                    .run(context -> assertThat(context).hasNotFailed());
            runner
                    .withUserConfiguration(IsbnBooksStore.class)
                    .withPropertyValues(store(url("global-none-mismatch-" + key.replace('.', '-'))))
                    .run(context -> assertThat(NestedExceptionUtils
                            .getMostSpecificCause(Objects.requireNonNull(context.getStartupFailure())))
                            .isInstanceOfSatisfying(SynapseConfigurationException.class,
                                    failure -> assertThat(failure.description())
                                            .startsWith("The schema of the store 'books' does not match its "
                                                    + "entities: ")));
        });
    }

    /**
     * Runs the test with the setting in Hibernate's copy of the global settings, then restores the earlier value.
     */
    private static void withGlobalSetting(String key, String value, Runnable test)
            throws ReflectiveOperationException {
        Properties global = hibernateGlobalProperties();
        String earlier = global.getProperty(key);
        global.setProperty(key, value);
        try {
            test.run();
        } finally {
            if (earlier == null) {
                global.remove(key);
            } else {
                global.setProperty(key, earlier);
            }
        }
    }

    /** Boot's create destroys the previous data: Synapse drops the store's tables before creating them. */
    @Test
    void createReplacesTheTables() throws SQLException {
        execute(url("create-replaces"),
                "CREATE TABLE book (id BIGINT PRIMARY KEY, title VARCHAR(255), note VARCHAR(9))",
                "INSERT INTO book VALUES (1, 'Dune', 'x')");
        runner
                .withUserConfiguration(BooksStore.class)
                .withPropertyValues(store(url("create-replaces")))
                .withPropertyValues("synapse.data.stores.books.allow-ddl-auto=true",
                        "synapse.data.stores.books.jpa.hibernate.ddl-auto=create",
                        "synapse.data.stores.books.migrations.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(columns(url("create-replaces"), "book")).containsExactlyInAnyOrder("id", "title");
                    assertThat(count(url("create-replaces"), "book")).isZero();
                });
    }

    /** As in Boot, the schema actions never create or drop a database schema an entity names. */
    @Test
    void schemaActionsLeaveDatabaseSchemasAlone() throws SQLException {
        execute(url("shelves"), "CREATE SCHEMA shelves");
        runner
                .withUserConfiguration(ShelvesStore.class)
                .withPropertyValues(store(url("shelves")))
                .withPropertyValues("synapse.data.stores.books.allow-ddl-auto=true",
                        "synapse.data.stores.books.jpa.hibernate.ddl-auto=create-drop",
                        "synapse.data.stores.books.migrations.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(columns(url("shelves"), "shelved_book")).containsExactly("id");
                });
        assertThat(columns(url("shelves"), "shelved_book")).as("dropped when the context closed").isEmpty();
        assertThat(schemas(url("shelves"))).as("the schema stays").contains("shelves");
    }

    /** An allowed action stops at a failed statement: startup fails instead of serving a half-built schema. */
    @Test
    void aFailedDdlStatementFailsStartup() {
        runner
                .withUserConfiguration(BrokenDdlStore.class)
                .withPropertyValues(store(url("broken-ddl")))
                .withPropertyValues("synapse.data.stores.books.allow-ddl-auto=true",
                        "synapse.data.stores.books.jpa.hibernate.ddl-auto=create",
                        "synapse.data.stores.books.migrations.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("booksStoreLifecycle")
                            .as("the database's rejection of the statement")
                            .hasRootCauseInstanceOf(SQLException.class);
                });
    }

    /** Building the beans opens no connection with the default validate either: it connects in start(). */
    @Test
    void validateConnectsOnlyWhenTheStoreStarts() throws SQLException {
        execute(url("validate-start"), "CREATE TABLE book (id BIGINT PRIMARY KEY, title VARCHAR(255) NOT NULL)");
        runner
                .withUserConfiguration(BooksStore.class, PoolProbe.class)
                .withPropertyValues(store(url("validate-start")))
                .withPropertyValues("synapse.data.stores.books.migrations.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PoolProbe.class).poolStartedBefore)
                            .as("the pool when the step ordered before the schema action started")
                            .isFalse();
                    assertThat(context.getBean("booksDataSource", HikariDataSource.class).getHikariPoolMXBean())
                            .as("validate connected")
                            .isNotNull();
                });
    }

    /**
     * Two stores on one database: create-drop drops only its own store's tables. The legacy store's table exists
     * already, and it validates without migrating (its Flyway would refuse a schema the books store has filled).
     */
    @Test
    void createDropDropsOnlyItsStoresTables() throws SQLException {
        String url = url("two-stores");
        execute(url, "CREATE TABLE legacy_item (id BIGINT PRIMARY KEY, name VARCHAR(255) NOT NULL)");
        runner
                .withUserConfiguration(BooksStore.class, LegacyStore.class)
                .withPropertyValues(store(url))
                .withPropertyValues("synapse.data.stores.books.allow-ddl-auto=true",
                        "synapse.data.stores.books.jpa.hibernate.ddl-auto=create-drop",
                        "synapse.data.stores.books.migrations.enabled=false",
                        "synapse.data.stores.legacy.url=" + url, "synapse.data.stores.legacy.username=sa",
                        "synapse.data.stores.legacy.password=", "synapse.data.stores.legacy.migrations.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(columns(url, "book")).isNotEmpty();
                    assertThat(columns(url, "legacy_item")).isNotEmpty();
                });
        assertThat(columns(url, "book")).as("the books store's table").isEmpty();
        assertThat(columns(url, "legacy_item")).as("the legacy store's table").isNotEmpty();
    }

    @Test
    void createDropDropsOnStop() throws SQLException {
        runner
                .withUserConfiguration(BooksStore.class)
                .withPropertyValues(store(url("create-drop")))
                .withPropertyValues("synapse.data.stores.books.allow-ddl-auto=true",
                        "synapse.data.stores.books.jpa.hibernate.ddl-auto=create-drop",
                        "synapse.data.stores.books.migrations.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(columns(url("create-drop"), "book")).isNotEmpty();
                });
        assertThat(columns(url("create-drop"), "book")).as("dropped when the context closed").isEmpty();
    }

    private static String url(String database) {
        return "jdbc:h2:mem:schema-action-" + database + ";DB_CLOSE_DELAY=-1";
    }

    private static String[] store(String url) {
        return new String[]{"synapse.data.stores.books.url=" + url, "synapse.data.stores.books.username=sa",
                "synapse.data.stores.books.password="};
    }

    /** Hibernate's copy of the system properties and {@code hibernate.properties}, made when its Environment loads. */
    private static Properties hibernateGlobalProperties() throws ReflectiveOperationException {
        Field field = Environment.class.getDeclaredField("GLOBAL_PROPERTIES");
        field.setAccessible(true);
        return (Properties) Objects.requireNonNull(field.get(null));
    }

    private static void execute(String url, String... statements) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private static int count(String url, String table) throws SQLException {
        return Integer.parseInt(values(url, "SELECT COUNT(*) FROM " + table).get(0));
    }

    /** The tables of the PUBLIC schema. */
    private static List<String> tables(String url) throws SQLException {
        return values(url,
                "SELECT LOWER(TABLE_NAME) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'");
    }

    private static List<String> schemas(String url) throws SQLException {
        return values(url, "SELECT LOWER(SCHEMA_NAME) FROM INFORMATION_SCHEMA.SCHEMATA");
    }

    private static List<String> values(String url, String query) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(query)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    /** Reads a table's columns through a connection of its own, outside the store's pool. */
    private static List<String> columns(String url, String table) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            return columns(connection, table);
        }
    }

    private static List<String> columns(Connection connection, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (PreparedStatement statement = connection
                .prepareStatement(
                        "SELECT LOWER(COLUMN_NAME) FROM INFORMATION_SCHEMA.COLUMNS WHERE LOWER(TABLE_NAME) = ?")) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    columns.add(rows.getString(1));
                }
            }
        }
        return columns;
    }

    /** A start step of the books store ordered before its schema action, which records whether the table exists. */
    @Configuration(proxyBeanMethods = false)
    static class BookTableProbe {

        @Nullable
        Boolean existedBefore;

        @Bean
        StoreStartStep bookTableProbeStep(@Qualifier("books") DataSource dataSource) {
            BookTableProbe probe = this;
            return new OrderedStep() {

                @Override
                public String store() {
                    return "books";
                }

                @Override
                public void start() {
                    try (Connection connection = dataSource.getConnection()) {
                        probe.existedBefore = !columns(connection, "book").isEmpty();
                    } catch (SQLException ex) {
                        throw new IllegalStateException(ex);
                    }
                }
            };
        }
    }

    /** A start step of the books store ordered before its schema action, which records whether its pool started. */
    @Configuration(proxyBeanMethods = false)
    static class PoolProbe {

        @Nullable
        Boolean poolStartedBefore;

        @Bean
        StoreStartStep poolProbeStep(@Qualifier("books") HikariDataSource dataSource) {
            PoolProbe probe = this;
            return new OrderedStep() {

                @Override
                public String store() {
                    return "books";
                }

                @Override
                public void start() {
                    probe.poolStartedBefore = dataSource.getHikariPoolMXBean() != null;
                }
            };
        }
    }

    /** A start step ordered before the unordered ones. */
    interface OrderedStep extends StoreStartStep, Ordered {

        @Override
        default int getOrder() {
            return 0;
        }
    }
}
