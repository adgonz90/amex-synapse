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
package io.americanexpress.synapse.data.internal.store;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport.ConditionAndOutcomes;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.core.lifecycle.SynapseLifecyclePhases;
import io.americanexpress.synapse.data.autoconfigure.SynapseDataAutoConfiguration;
import io.americanexpress.synapse.data.fixture.H2TestVendor;
import io.americanexpress.synapse.data.fixture.books.BooksStore;
import io.americanexpress.synapse.data.fixture.broken.BrokenStore;
import io.americanexpress.synapse.data.fixture.legacy.LegacyStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots an application with two H2 stores, {@code books} and {@code legacy}, each migrated by Flyway from its own
 * {@code db/migration/<store>} folder when the stores start, before any inbound phase.
 */
@Tag("smoke")
@SpringBootTest(classes = StoreMigrationsIT.TestApplication.class, properties = {
        "synapse.data.stores.books.url=jdbc:h2:mem:migrations-books;DB_CLOSE_DELAY=-1",
        "synapse.data.stores.books.username=sa", "synapse.data.stores.books.password=",
        "synapse.data.stores.legacy.url=jdbc:h2:mem:migrations-legacy;DB_CLOSE_DELAY=-1",
        "synapse.data.stores.legacy.username=sa", "synapse.data.stores.legacy.password="})
@ActiveProfiles("test")
class StoreMigrationsIT {

    @Autowired
    @Qualifier("books")
    private JdbcClient books;

    @Autowired
    @Qualifier("legacy")
    private JdbcClient legacy;

    @Autowired
    private ConfigurableApplicationContext context;

    @Autowired
    private InboundProbe inbound;

    @Autowired
    private BeforeStoresProbe beforeStores;

    @Test
    void eachStoreRunsItsOwnMigrations() {
        assertThat(tables(books)).contains("book", "flyway_schema_history").doesNotContain("legacy_item", "other");
        assertThat(tables(legacy)).contains("legacy_item", "flyway_schema_history").doesNotContain("book", "other");
        assertThat(appliedVersions(books)).containsExactly("1");
        assertThat(appliedVersions(legacy)).containsExactly("1");
    }

    @Test
    void bootsFlywayBacksOff() {
        assertThat(context.getBeansOfType(Flyway.class).keySet())
                .containsExactlyInAnyOrder("booksFlyway",
                        "legacyFlyway");
        assertThat(context.containsBean("flywayInitializer")).isFalse();
        Map<String, ConditionAndOutcomes> outcomes = ConditionEvaluationReport
                .get(context.getBeanFactory())
                .getConditionAndOutcomesBySource();
        ConditionAndOutcomes autoConfiguration = Objects
                .requireNonNull(outcomes.get(FlywayAutoConfiguration.class.getName()),
                        "Boot's Flyway auto-configuration is considered");
        assertThat(autoConfiguration.isFullMatch()).isTrue();
        ConditionAndOutcomes flywayConfiguration = Objects
                .requireNonNull(outcomes.get(FlywayAutoConfiguration.class.getName() + "$FlywayConfiguration"),
                        "Boot's Flyway configuration is considered");
        assertThat(flywayConfiguration.isFullMatch()).as("Boot's Flyway configuration backs off").isFalse();
    }

    @Test
    void storesStartBeforeInboundPhases() {
        assertThat(inbound.booksTablesAtStart).contains("book");
        assertThat(inbound.legacyTablesAtStart).contains("legacy_item");
        assertThat(context.getBean("booksStoreLifecycle", StoreLifecycle.class).isRunning()).isTrue();
        assertThat(context.getBean("legacyStoreLifecycle", StoreLifecycle.class).getPhase())
                .isLessThan(SynapseLifecyclePhases.INBOUND);
    }

    @Test
    void noStoreConnectsBeforeTheStoresStart() {
        assertThat(beforeStores.booksPoolStarted).as("books pool open before the stores' phase").isFalse();
        assertThat(beforeStores.legacyPoolStarted).as("legacy pool open before the stores' phase").isFalse();
        assertThat(beforeStores.isRunning()).isTrue();
        assertThat(context.getBean("booksDataSource", HikariDataSource.class).getHikariPoolMXBean())
                .as("the migration opened the pool afterwards")
                .isNotNull();
    }

    @Test
    void aFailingMigrationFailsStartup() {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(SynapseCoreAutoConfiguration.class, SynapseDataAutoConfiguration.class))
                .withUserConfiguration(H2TestVendor.class, BrokenStore.class)
                .withPropertyValues("synapse.data.stores.broken.url=jdbc:h2:mem:migrations-broken;DB_CLOSE_DELAY=-1",
                        "synapse.data.stores.broken.username=sa", "synapse.data.stores.broken.password=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable failure = Objects.requireNonNull(context.getStartupFailure());
                    Throwable migration = failure;
                    while (migration != null
                            && !"The migrations of the store 'broken' failed.".equals(migration.getMessage())) {
                        migration = migration.getCause();
                    }
                    assertThat(migration).as("the failure names the store").isInstanceOf(IllegalStateException.class);
                    assertThat(Objects.requireNonNull(migration).getCause())
                            .as("Flyway's cause names the migration")
                            .hasMessageContaining("V1__broken.sql");
                });
    }

    private static Set<String> tables(JdbcClient client) {
        return client
                .sql("SELECT LOWER(TABLE_NAME) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'")
                .query((row, rowNumber) -> row.getString(1))
                .list()
                .stream()
                .collect(Collectors.toSet());
    }

    private static List<String> appliedVersions(JdbcClient client) {
        return client
                .sql("SELECT \"version\" FROM \"flyway_schema_history\" WHERE \"success\" AND \"version\" IS NOT NULL "
                        + "ORDER BY \"installed_rank\"")
                .query((row, rowNumber) -> row.getString(1))
                .list();
    }

    /** An inbound edge: records, when it starts, which tables each store has by then. */
    static class InboundProbe implements SmartLifecycle {

        private final JdbcClient books;

        private final JdbcClient legacy;

        private Set<String> booksTablesAtStart = Set.of();

        private Set<String> legacyTablesAtStart = Set.of();

        private boolean running;

        InboundProbe(JdbcClient books, JdbcClient legacy) {
            this.books = books;
            this.legacy = legacy;
        }

        @Override
        public void start() {
            booksTablesAtStart = tables(books);
            legacyTablesAtStart = tables(legacy);
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public int getPhase() {
            return SynapseLifecyclePhases.INBOUND;
        }
    }

    /**
     * Starts just before the stores' phase: records whether either store's pool is open by then (acceptance 17: no
     * connection while beans are created).
     */
    static class BeforeStoresProbe implements SmartLifecycle {

        private final HikariDataSource books;

        private final HikariDataSource legacy;

        private boolean booksPoolStarted = true;

        private boolean legacyPoolStarted = true;

        private boolean running;

        BeforeStoresProbe(HikariDataSource books, HikariDataSource legacy) {
            this.books = books;
            this.legacy = legacy;
        }

        @Override
        public void start() {
            booksPoolStarted = books.getHikariPoolMXBean() != null;
            legacyPoolStarted = legacy.getHikariPoolMXBean() != null;
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public int getPhase() {
            return SynapseLifecyclePhases.DATA_STORES - 1;
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({H2TestVendor.class, BooksStore.class, LegacyStore.class})
    static class TestApplication {

        @Bean
        BeforeStoresProbe beforeStoresProbe(@Qualifier("books") HikariDataSource books,
                @Qualifier("legacy") HikariDataSource legacy) {
            return new BeforeStoresProbe(books, legacy);
        }

        @Bean
        InboundProbe inboundProbe(@Qualifier("books") JdbcClient books, @Qualifier("legacy") JdbcClient legacy) {
            return new InboundProbe(books, legacy);
        }
    }
}
