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

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.Location;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.data.DataAccess;
import io.americanexpress.synapse.data.autoconfigure.SynapseDataStoreProperties;
import io.americanexpress.synapse.data.internal.store.StoreDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class FlywayStoreMigrationTest {

    private static final String SECRET = "s3cr3t";

    private static final StoreDefinition BOOKS = store("books");

    @Test
    void defaultLocationIsTheStoresFolder() {
        Flyway flyway = flyway(BOOKS, properties(null, Map.of()));

        assertThat(flyway.getConfiguration().getLocations())
                .extracting(Location::getDescriptor)
                .containsExactly("classpath:db/migration/books");
    }

    @Test
    void aDashedStoreUsesItsNameAsWritten() {
        Flyway flyway = flyway(store("legacy-orders"), properties(null, Map.of()));

        assertThat(flyway.getConfiguration().getLocations())
                .extracting(Location::getDescriptor)
                .containsExactly("classpath:db/migration/legacy-orders");
    }

    @Test
    void configuredLocationsWin() {
        Flyway flyway = flyway(BOOKS, properties(List.of("classpath:db/books-a", "classpath:db/books-b"), Map.of()));

        assertThat(flyway.getConfiguration().getLocations())
                .extracting(Location::getDescriptor)
                .containsExactly("classpath:db/books-a", "classpath:db/books-b");
    }

    @Test
    void anEmptyLocationListKeepsTheDefault() {
        Flyway flyway = flyway(BOOKS, properties(List.of(), Map.of()));

        assertThat(flyway.getConfiguration().getLocations())
                .extracting(Location::getDescriptor)
                .containsExactly("classpath:db/migration/books");
    }

    @Test
    void flywayKeysPassThrough() {
        Flyway flyway = flyway(BOOKS, properties(null,
                Map.of("baseline-on-migrate", "true", "table", "books_history", "validateMigrationNaming", "true")));

        assertThat(flyway.getConfiguration().isBaselineOnMigrate()).isTrue();
        assertThat(flyway.getConfiguration().getTable()).isEqualTo("books_history");
        assertThat(flyway.getConfiguration().isValidateMigrationNaming()).isTrue();
    }

    @Test
    void placeholderNamesAreKeptAsWritten() {
        Flyway flyway = flyway(BOOKS,
                properties(null, Map.of("placeholders.table-owner", "app", "placeholder-prefix", "#[")));

        assertThat(flyway.getConfiguration().getPlaceholders()).containsEntry("table-owner", "app");
        assertThat(flyway.getConfiguration().getPlaceholderPrefix()).isEqualTo("#[");
    }

    @ParameterizedTest
    @CsvSource({"baselineonmigrate, baselineonmigrate", "baseline.on.migrate, baseline.on.migrate",
            "not-a-flyway-key, not-a-flyway-key"})
    void unknownFlywayKeysNameTheStoreKey(String key, String writtenKey) {
        SynapseConfigurationException failure = catchThrowableOfType(SynapseConfigurationException.class,
                () -> flyway(BOOKS, properties(null, Map.of(key, SECRET, "table", "books_history"))));

        assertThat(failure.description())
                .isEqualTo("The store 'books' sets synapse.data.stores.books.migrations.flyway." + writtenKey
                        + ", which is not a Flyway setting.");
        assertThat(failure.action())
                .isEqualTo("Write Flyway settings with Flyway's own names, such as baseline-on-migrate, in a config "
                        + "file. Environment variable forms of Flyway keys are not supported.");
        assertThat(failure.description() + failure.action()).doesNotContain(SECRET);
    }

    @Test
    void severalUnknownFlywayKeysNameTheFirst() {
        SynapseConfigurationException failure = catchThrowableOfType(SynapseConfigurationException.class,
                () -> flyway(BOOKS, properties(null, Map.of("zeta", "1", "alpha", "2"))));

        assertThat(failure.description())
                .isEqualTo("The store 'books' sets synapse.data.stores.books.migrations.flyway.alpha, which is not a "
                        + "Flyway setting.");
        assertThat(failure).hasNoCause();
    }

    @ParameterizedTest
    @CsvSource({"connect-retries", "connectRetries", "lock-retry-count"})
    void invalidFlywayValuesNameTheKeyButNotTheValue(String key) {
        SynapseConfigurationException failure = catchThrowableOfType(SynapseConfigurationException.class,
                () -> flyway(BOOKS, properties(null, Map.of(key, SECRET, "table", "books_history"))));

        String fullKey = "synapse.data.stores.books.migrations.flyway." + key;
        assertThat(failure.description())
                .isEqualTo("The store 'books' sets " + fullKey + " to a value Flyway rejects.");
        assertThat(failure.action())
                .isEqualTo("Fix the value of " + fullKey + ". Flyway's message isn't shown, since it may contain the "
                        + "value.");
        assertThat(failure.description() + failure.action()).doesNotContain(SECRET);
        assertThat(failure).hasNoCause();
    }

    @Test
    void aFlywayFailureThatNamesNoKeyNamesAllTheStoresKeys() {
        SynapseConfigurationException failure = FlywayStoreMigration
                .rejected("books", new FlywayException("Something about " + SECRET),
                        new java.util.TreeMap<>(Map
                                .of("flyway.table", "table", "flyway.baselineOnMigrate",
                                        "baseline-on-migrate")));

        assertThat(failure.description())
                .isEqualTo("Flyway rejects the migrations.flyway settings of the store 'books': "
                        + "synapse.data.stores.books.migrations.flyway.baseline-on-migrate, "
                        + "synapse.data.stores.books.migrations.flyway.table.");
        assertThat(failure.action())
                .isEqualTo("Check these settings against Flyway's documentation. Flyway's message isn't shown, since "
                        + "it may contain a value.");
        assertThat(failure.description() + failure.action()).doesNotContain(SECRET);
        assertThat(failure).hasNoCause();
    }

    @Test
    void twoSpellingsOfOneFlywayKeyFail() {
        SynapseConfigurationException failure = catchThrowableOfType(SynapseConfigurationException.class,
                () -> flyway(BOOKS, properties(null, Map
                        .of("baseline-on-migrate", "true", "baselineOnMigrate",
                                "false"))));

        assertThat(failure.description())
                .isEqualTo("The store 'books' sets synapse.data.stores.books.migrations.flyway.baseline-on-migrate "
                        + "and synapse.data.stores.books.migrations.flyway.baselineOnMigrate, which are the same "
                        + "Flyway setting.");
        assertThat(failure.action()).isEqualTo("Remove one of them.");
    }

    @Test
    void theStoresDataSourceIsUsed() {
        try (HikariDataSource dataSource = unreachable()) {
            Flyway flyway = FlywayStoreMigration
                    .flyway(BOOKS, dataSource, properties(null, Map.of()), getClass().getClassLoader());

            assertThat(flyway.getConfiguration().getDataSource()).isSameAs(dataSource);
        }
    }

    @Test
    void loadingOpensNoConnection() {
        try (HikariDataSource dataSource = unreachable()) {
            Flyway flyway = FlywayStoreMigration
                    .flyway(BOOKS, dataSource, properties(null, Map.of()), getClass().getClassLoader());

            assertThat(flyway).isNotNull();
            assertThat(dataSource.getHikariPoolMXBean()).isNull();
            assertThat(dataSource.isRunning()).isFalse();
        }
    }

    @ParameterizedTest
    @CsvSource({"url, url", "URL, url", "user, username", "User, username", "password, password",
            "PASSWORD, password", "pass-word, password", "pass_word, password", "locations, migrations.locations",
            "Locations, migrations.locations", "LOCATIONS, migrations.locations"})
    void connectionOverridesAreRejected(String key, String storeKey) {
        SynapseConfigurationException failure = catchThrowableOfType(SynapseConfigurationException.class,
                () -> flyway(BOOKS, properties(null, Map.of(key, SECRET))));

        String fullKey = "synapse.data.stores.books.migrations.flyway." + key;
        assertThat(failure.description())
                .isEqualTo("The store 'books' sets " + fullKey + ", which would replace its " + storeKey + ".");
        assertThat(failure.action())
                .isEqualTo("Remove " + fullKey + " and set synapse.data.stores.books." + storeKey + " instead.");
        assertThat(failure.description() + failure.action()).doesNotContain(SECRET);
    }

    /** SQL on each new migration connection could change the store's role or schema behind its settings. */
    @ParameterizedTest
    @ValueSource(strings = {"init-sql", "initSql", "init_sql", "INITSQL"})
    void initSqlIsRejected(String key) {
        SynapseConfigurationException failure = catchThrowableOfType(SynapseConfigurationException.class,
                () -> flyway(BOOKS, properties(null, Map.of(key, "SET ROLE " + SECRET))));

        String fullKey = "synapse.data.stores.books.migrations.flyway." + key;
        assertThat(failure.description())
                .isEqualTo("The store 'books' sets " + fullKey + ", SQL that runs on each new connection and could "
                        + "change the role or schema the store uses.");
        assertThat(failure.action())
                .isEqualTo("Remove " + fullKey + ". Name the schema in synapse.data.stores.books.url.");
        assertThat(failure.description() + failure.action()).doesNotContain(SECRET);
    }

    @Test
    void migrateNamesTheStoreWhenFlywayFails() {
        try (HikariDataSource dataSource = unreachable()) {
            dataSource.setConnectionTimeout(250);
            Flyway flyway = FlywayStoreMigration
                    .flyway(BOOKS, dataSource, properties(null, Map.of()), getClass().getClassLoader());
            FlywayStoreMigration migration = new FlywayStoreMigration("books", flyway);

            assertThat(migration.store()).isEqualTo("books");
            assertThatThrownBy(migration::migrate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("The migrations of the store 'books' failed.")
                    .hasCauseInstanceOf(FlywayException.class);
        }
    }

    private static Flyway flyway(StoreDefinition store, SynapseDataStoreProperties properties) {
        try (HikariDataSource dataSource = unreachable()) {
            return FlywayStoreMigration
                    .flyway(store, dataSource, properties, FlywayStoreMigrationTest.class.getClassLoader());
        }
    }

    private static HikariDataSource unreachable() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:h2:tcp://127.0.0.1:1/none");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static SynapseDataStoreProperties properties(@Nullable List<String> locations,
            Map<String, String> flyway) {
        return new SynapseDataStoreProperties(null, null, null, null, false, null, false,
                new SynapseDataStoreProperties.Migrations(true, locations, flyway),
                new SynapseDataStoreProperties.Health(false, Duration.ofSeconds(1)));
    }

    private static StoreDefinition store(String name) {
        return new StoreDefinition(name, name, "com.example." + name, DataAccess.JDBC, false, "com.example.Store");
    }
}
