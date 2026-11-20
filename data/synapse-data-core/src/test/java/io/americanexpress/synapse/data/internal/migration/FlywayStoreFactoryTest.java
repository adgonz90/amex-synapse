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
package io.americanexpress.synapse.data.internal.migration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.data.SynapseDataStore;
import io.americanexpress.synapse.data.autoconfigure.SynapseDataAutoConfiguration;
import io.americanexpress.synapse.data.fixture.H2TestVendor;
import io.americanexpress.synapse.data.fixture.books.BooksStore;
import io.americanexpress.synapse.data.fixture.legacy.LegacyStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application's Flyway beans that a store's {@code <prefix>Flyway} uses, as Boot's Flyway auto-configuration uses
 * them: {@link FlywayConfigurationCustomizer}s (unqualified, then qualified with the store's name, then the store's own
 * {@code <prefix>FlywayConfigurationCustomizer}), and the {@link Callback}s and {@code JavaMigration}s qualified with
 * the store's name, or unqualified ones while there is a single store.
 */
class FlywayStoreFactoryTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(
                    AutoConfigurations.of(SynapseCoreAutoConfiguration.class, SynapseDataAutoConfiguration.class))
            .withUserConfiguration(H2TestVendor.class);

    /** The books store, on a database of its own: each test migrates it from scratch. */
    private ApplicationContextRunner books(String database) {
        return runner.withUserConfiguration(BooksStore.class).withPropertyValues(store("books", database));
    }

    @Test
    void aCustomizerTakesEffect() {
        books("ff-customizer")
                .withBean("baselineCustomizer", FlywayConfigurationCustomizer.class,
                        () -> configuration -> configuration.baselineOnMigrate(true))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(flyway(context.getBean("booksFlyway", Flyway.class)).isBaselineOnMigrate()).isTrue();
                });
    }

    /** The more specific group runs later and wins, whatever the @Order of each bean. */
    @Test
    void aStoreQualifiedCustomizerRunsAfterAnUnqualifiedOne() {
        books("ff-order")
                .withUserConfiguration(TableCustomizers.class)
                .withPropertyValues(store("legacy", "flyway-factory-order"))
                .withUserConfiguration(LegacyStore.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(flyway(context.getBean("booksFlyway", Flyway.class)).getTable())
                            .isEqualTo("books_history");
                    assertThat(flyway(context.getBean("legacyFlyway", Flyway.class)).getTable())
                            .isEqualTo("any_history");
                });
    }

    /** The store class's own customizer is the most specific one, so it runs last. */
    @Test
    void theStoresOwnCustomizerRunsLast() {
        runner
                .withUserConfiguration(BooksStoreWithOwnCustomizer.class, TableCustomizers.class)
                .withPropertyValues(store("books", "flyway-factory-own"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(flyway(context.getBean("booksFlyway", Flyway.class)).getTable())
                            .isEqualTo("own_history");
                });
    }

    /** The store's DataSource and locations are applied after every customizer, so a customizer can't move them. */
    @Test
    void aCustomizerCannotMoveTheStore() {
        books("ff-moving")
                .withBean("movingCustomizer", FlywayConfigurationCustomizer.class,
                        () -> configuration -> configuration
                                .dataSource("jdbc:h2:mem:elsewhere", "other", "")
                                .locations("classpath:db/migration/legacy"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Flyway flyway = context.getBean("booksFlyway", Flyway.class);
                    assertThat(flyway(flyway).getDataSource()).isSameAs(context.getBean("booksDataSource"));
                    assertThat(flyway(flyway).getLocations())
                            .extracting(Location::getDescriptor)
                            .containsExactly("classpath:db/migration/books");
                    assertThat(tables(context.getBean("booksJdbcClient", JdbcClient.class)))
                            .contains("book")
                            .doesNotContain("legacy_item");
                });
    }

    /** As {@code migrations.flyway.init-sql}, SQL on each new connection could change the store's role or schema. */
    @Test
    void aCustomizerSettingInitSqlFails() {
        books("ff-initsql")
                .withBean("roleCustomizer", FlywayConfigurationCustomizer.class,
                        () -> configuration -> configuration.initSql("SET ROLE admin"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable root = NestedExceptionUtils
                            .getMostSpecificCause(Objects.requireNonNull(context.getStartupFailure()));
                    assertThat(root).isInstanceOfSatisfying(SynapseConfigurationException.class, failure -> {
                        assertThat(failure.description())
                                .isEqualTo("The FlywayConfigurationCustomizer bean roleCustomizer sets initSql for the "
                                        + "store 'books', SQL that runs on each new connection and could change the "
                                        + "role or schema the store uses.");
                        assertThat(failure.action())
                                .isEqualTo("Remove initSql from roleCustomizer. Name the schema in "
                                        + "synapse.data.stores.books.url.");
                    });
                    assertThat(root.getMessage()).doesNotContain("SET ROLE");
                });
    }

    @Test
    void aStoreQualifiedCallbackRunsAfterMigrate() {
        books("ff-callback")
                .withUserConfiguration(LegacyStore.class, QualifiedCallbacks.class)
                .withPropertyValues(store("legacy", "flyway-factory-callback"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("booksCallback", RecordingCallback.class).events)
                            .contains(Event.AFTER_MIGRATE);
                    assertThat(flyway(context.getBean("legacyFlyway", Flyway.class)).getCallbacks())
                            .doesNotContain(context.getBean("booksCallback", Callback.class));
                });
    }

    @Test
    void aStoreQualifiedJavaMigrationIsApplied() {
        books("ff-java")
                .withUserConfiguration(LegacyStore.class)
                .withPropertyValues(store("legacy", "flyway-factory-java"))
                .withUserConfiguration(QualifiedJavaMigration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(tables(context.getBean("booksJdbcClient", JdbcClient.class))).contains("book", "shelf");
                    assertThat(tables(context.getBean("legacyJdbcClient", JdbcClient.class))).doesNotContain("shelf");
                });
    }

    @Test
    void anUnqualifiedCallbackAppliesToTheOnlyStore() {
        books("ff-only").withBean("anyCallback", RecordingCallback.class, RecordingCallback::new).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("anyCallback", RecordingCallback.class).events).contains(Event.AFTER_MIGRATE);
        });
    }

    /** With several stores, an unqualified callback applies to none (the startup report names it). */
    @Test
    void anUnqualifiedCallbackWithTwoStoresIsNotApplied() {
        books("ff-two")
                .withUserConfiguration(LegacyStore.class)
                .withPropertyValues(store("legacy", "flyway-factory-two"))
                .withBean("anyCallback", RecordingCallback.class, RecordingCallback::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("anyCallback", RecordingCallback.class).events).isEmpty();
                });
    }

    /** Without spring-boot-flyway the type is absent, so no customizer is looked up, and the store still migrates. */
    @Test
    void withoutSpringBootFlywayCustomizersAreSkipped() {
        books("ff-noboot")
                .withClassLoader(new FilteredClassLoader(FlywayConfigurationCustomizer.class))
                .withBean("tableCustomizer", FlywayConfigurationCustomizer.class,
                        () -> configuration -> configuration.table("customized_history"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(flyway(context.getBean("booksFlyway", Flyway.class)).getTable())
                            .isEqualTo("flyway_schema_history");
                    assertThat(tables(context.getBean("booksJdbcClient", JdbcClient.class))).contains("book");
                });
    }

    private static org.flywaydb.core.api.configuration.Configuration flyway(Flyway flyway) {
        return flyway.getConfiguration();
    }

    private static String[] store(String name, String database) {
        return new String[]{"synapse.data.stores." + name + ".url=jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1",
                "synapse.data.stores." + name + ".username=sa", "synapse.data.stores." + name + ".password="};
    }

    private static List<String> tables(JdbcClient client) {
        return client
                .sql("SELECT LOWER(TABLE_NAME) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'")
                .query((row, rowNumber) -> row.getString(1))
                .list();
    }

    @Configuration(proxyBeanMethods = false)
    static class TableCustomizers {

        @Bean
        @Order(Integer.MAX_VALUE)
        FlywayConfigurationCustomizer anyTable() {
            return configuration -> configuration.table("any_history");
        }

        @Bean
        @Order(Integer.MIN_VALUE)
        @Qualifier("books")
        FlywayConfigurationCustomizer booksTable() {
            return configuration -> configuration.table("books_history");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @SynapseDataStore("books")
    static class BooksStoreWithOwnCustomizer {

        @Bean
        FlywayConfigurationCustomizer booksFlywayConfigurationCustomizer() {
            return configuration -> configuration.table("own_history");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class QualifiedCallbacks {

        @Bean
        @Qualifier("books")
        RecordingCallback booksCallback() {
            return new RecordingCallback();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class QualifiedJavaMigration {

        @Bean
        @Qualifier("books")
        V2__Shelves booksShelves() {
            return new V2__Shelves();
        }
    }

    /** Records the events Flyway sends it. */
    static class RecordingCallback implements Callback {

        final List<Event> events = new ArrayList<>();

        @Override
        public boolean supports(Event event, Context context) {
            return true;
        }

        @Override
        public boolean canHandleInTransaction(Event event, Context context) {
            return true;
        }

        @Override
        public void handle(Event event, Context context) {
            events.add(event);
        }

        @Override
        public String getCallbackName() {
            return "recording";
        }
    }

    /** A Java migration after the books store's V1. Flyway reads its version from the class name. */
    static class V2__Shelves extends BaseJavaMigration {

        @Override
        public void migrate(org.flywaydb.core.api.migration.Context context) throws Exception {
            try (var statement = context.getConnection().createStatement()) {
                statement.execute("CREATE TABLE shelf (id INT PRIMARY KEY)");
            }
        }
    }
}
