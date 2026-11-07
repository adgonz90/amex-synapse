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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jakarta.persistence.EntityManagerFactory;

import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.americanexpress.synapse.data.postgres.fixture.books.Book;
import io.americanexpress.synapse.data.postgres.fixture.books.BookRepository;
import io.americanexpress.synapse.test.data.StoreConnection;
import io.americanexpress.synapse.test.data.SynapseContainers;
import io.americanexpress.synapse.test.data.SynapseDataTest;
import io.americanexpress.synapse.test.time.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The data slice for the books store on PostgreSQL (Docker), connected to the shared container with
 * {@code @StoreConnection}: the slice gets the PostgreSQL vendor from synapse-data-postgres, migrates and validates the
 * store, and runs each test in a books transaction that is rolled back. Ids and auditing come from the test clock.
 */
@SynapseDataTest("books")
@Tag("smoke")
class PostgresStoreIT {

    @StoreConnection("books")
    static final PostgreSQLContainer BOOKS = SynapseContainers.postgres();

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private ApplicationContext context;

    @Autowired
    @Qualifier("books")
    private JdbcClient jdbc;

    @Autowired
    @Qualifier("books")
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private BookRepository books;

    @Autowired
    private MutableClock clock;

    /**
     * Flyway migrated the container's database when the store started, and Hibernate validated the entities against
     * it: the context would not have started otherwise. The slice left the legacy store out.
     */
    @Test
    void migratesAndValidates() {
        assertThat(context.getBean("postgresDataStoreVendor")).isInstanceOf(PostgresVendor.class);
        assertThat(context.getBean("booksStoreLifecycle", SmartLifecycle.class).isRunning()).isTrue();
        assertThat(context.containsBean("legacyDataSource")).isFalse();
        assertThat(jdbc
                .sql("SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL")
                .query((row, number) -> row.getString("version") + ":" + row.getBoolean("success"))
                .list()).containsExactly("1:true");
        assertThat(jdbc.sql("SELECT current_database()").query(String.class).single())
                .isEqualTo(BOOKS.getDatabaseName());
        assertThat(jdbc.sql("SHOW server_version").query(String.class).single()).startsWith("18.6");
        Dialect dialect = entityManagerFactory
                .unwrap(SessionFactoryImplementor.class)
                .getJdbcServices()
                .getDialect();
        assertThat(dialect).isExactlyInstanceOf(PostgreSQLDialect.class);
        assertThat(dialect.getVersion().getMajor()).isEqualTo(15);
    }

    @Test
    void runsInATransactionOfTheStore() {
        assertThat(TestTransaction.isActive()).isTrue();
        assertThat(TestTransaction.isFlaggedForRollback()).isTrue();
        assertThat(TransactionSynchronizationManager.hasResource(entityManagerFactory)).isTrue();
    }

    @Test
    void idsAuditingAndVersion() {
        assertThat(clock.instant()).isEqualTo(START);

        Book book = books.saveAndFlush(new Book("Dune", "978-0-00-000001-0"));

        UUID id = id(book);
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
        assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(START.toEpochMilli());
        List<Instant> stamps = jdbc
                .sql("SELECT created_at, modified_at FROM book WHERE id = ?")
                .param(id)
                .query((result, number) -> List
                        .of(result.getObject("created_at", OffsetDateTime.class).toInstant(),
                                result.getObject("modified_at", OffsetDateTime.class).toInstant()))
                .single();
        assertThat(stamps).containsExactly(START, START);
        Map<String, @Nullable Object> row = jdbc
                .sql("SELECT created_by, modified_by, version, title, isbn FROM book WHERE id = ?")
                .param(id)
                .query()
                .singleRow();
        assertThat(row)
                .containsEntry("created_by", "system")
                .containsEntry("modified_by", "system")
                .containsEntry("version", 0L)
                .containsEntry("title", "Dune")
                .containsEntry("isbn", "978-0-00-000001-0");
    }

    private static UUID id(Book book) {
        UUID id = book.getId();
        if (id == null) {
            throw new AssertionError("The book has no id");
        }
        return id;
    }
}
