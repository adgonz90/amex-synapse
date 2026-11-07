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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.data.postgres.fixture.books.Book;
import io.americanexpress.synapse.data.postgres.fixture.books.BookRepository;
import io.americanexpress.synapse.data.postgres.fixture.legacy.LegacyItem;
import io.americanexpress.synapse.data.postgres.fixture.legacy.LegacyItemRepository;
import io.americanexpress.synapse.test.data.StoreConnection;
import io.americanexpress.synapse.test.data.SynapseContainers;

import static io.americanexpress.synapse.test.assertj.SynapseAssertions.assertThatSynapseException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * PostgreSQL's failures as the callers of operations see them (Docker), through the Synapse pipeline of the fixture
 * service: the books store (JPA) and the legacy store (JDBC), each on its own container. Each test names the SQLState
 * PostgreSQL reports. A unique violation is {@code ALREADY_EXISTS}, an optimistic-lock conflict {@code STALE_VERSION}
 * and a statement timeout {@code DEADLINE_EXCEEDED}. Lock timeouts, serialization failures and deadlocks are
 * {@code INTERNAL} today: whether they get a retryable code is an open question for the maintainers.
 */
@SpringBootTest(classes = PostgresTestApplication.class)
@ActiveProfiles("test")
@Import({PostgresErrorTranslationIT.AddTwoCopiesOperation.class,
        PostgresErrorTranslationIT.RenameBothCopiesOperation.class, PostgresErrorTranslationIT.AddItemOperation.class,
        PostgresErrorTranslationIT.LegacyScriptOperation.class,
        PostgresErrorTranslationIT.RepeatableReadScriptOperation.class,
        PostgresErrorTranslationIT.BooksScriptOperation.class})
class PostgresErrorTranslationIT {

    @StoreConnection("books")
    static final PostgreSQLContainer BOOKS = SynapseContainers.postgres("books");

    @StoreConnection("legacy")
    static final PostgreSQLContainer LEGACY = SynapseContainers.postgres("legacy");

    private static final Duration WAIT = Duration.ofSeconds(10);

    @Autowired
    @Qualifier("books")
    private JdbcClient books;

    @Autowired
    @Qualifier("legacy")
    private JdbcClient legacy;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private AddTwoCopiesOperation addTwoCopies;

    @Autowired
    private RenameBothCopiesOperation renameBothCopies;

    @Autowired
    private AddItemOperation addItem;

    @Autowired
    private LegacyScriptOperation legacyScript;

    @Autowired
    private RepeatableReadScriptOperation repeatableReadScript;

    @Autowired
    private BooksScriptOperation booksScript;

    /**
     * Review Focus 4: two books with one ISBN, inserted only when the operation's transaction commits. The unique
     * violation (23505) reaches the pipeline wrapped by the commit, and the caller gets {@code ALREADY_EXISTS}.
     */
    @Test
    void duplicateAtCommitIsDuplicate() {
        String isbn = "978-0-00-000002-0";
        assertThat(AopUtils.isAopProxy(addTwoCopies)).isTrue();

        assertThatSynapseException(() -> addTwoCopies.execute(isbn))
                .hasCode(SynapseErrors.ALREADY_EXISTS)
                .hasCategory(ErrorCategory.CONFLICT)
                .satisfies(failure -> {
                    assertThat(failure.getMessage()).isEqualTo("The resource already exists");
                    assertThat(sqlStates(failure)).containsExactly("23505");
                });
        assertThat(books.sql("SELECT COUNT(*) FROM book WHERE isbn = ?").param(isbn).query(Integer.class).single())
                .isZero();
    }

    /** A JDBC store's unique violation (23505) is {@code ALREADY_EXISTS} too. */
    @Test
    void jdbcDuplicateIsDuplicate() {
        String name = "duplicate-" + UUID.randomUUID();
        try {
            addItem.execute(name);

            assertThatSynapseException(() -> addItem.execute(name))
                    .hasCode(SynapseErrors.ALREADY_EXISTS)
                    .satisfies(failure -> assertThat(sqlStates(failure)).containsExactly("23505"));
            assertThat(count(name)).isOne();
        } finally {
            delete(name);
        }
    }

    /**
     * Acceptance 8: a book read twice, each copy in its own transaction, then saved twice. The second save carries a
     * stale version, and the caller gets {@code STALE_VERSION}; the first save stays.
     */
    @Test
    void staleWriteIsStaleVersion() {
        UUID id = id(bookRepository.save(new Book("Ulysses", "978-0-00-000003-0")));
        try {
            assertThatSynapseException(() -> renameBothCopies.execute(id))
                    .hasCode(SynapseErrors.STALE_VERSION)
                    .hasCategory(ErrorCategory.CONFLICT);
            Map<String, @Nullable Object> row = books
                    .sql("SELECT title, version FROM book WHERE id = ?")
                    .param(id)
                    .query()
                    .singleRow();
            assertThat(row).containsEntry("title", "first copy").containsEntry("version", 1L);
        } finally {
            books.sql("DELETE FROM book WHERE id = ?").param(id).update();
        }
    }

    /** {@code statement_timeout} cancels the statement (57014) on a JDBC store: {@code DEADLINE_EXCEEDED}. */
    @Test
    void statementTimeoutIsDeadlineExceeded() {
        SynapseException failure = failure(() -> legacyScript
                .execute(List
                        .of(jdbc -> setLocal(jdbc, "statement_timeout", "100"),
                                jdbc -> jdbc.sql("SELECT 1 FROM pg_sleep(5)").query(Integer.class).single())));

        assertThat(failure.code()).isEqualTo(SynapseErrors.DEADLINE_EXCEEDED.code());
        assertThat(failure.category()).isEqualTo(ErrorCategory.TIMEOUT);
        assertThat(sqlStates(failure)).containsExactly("57014");
    }

    /** The same on a JPA store, through a repository's native query. */
    @Test
    void jpaRepositoryStatementTimeoutIsDeadlineExceeded() {
        SynapseException failure = failure(() -> booksScript
                .execute(List
                        .of(
                                entityManager -> setLocal(entityManager, "statement_timeout", "100"),
                                entityManager -> bookRepository.sleep(5))));

        assertThat(failure.code()).isEqualTo(SynapseErrors.DEADLINE_EXCEEDED.code());
        assertThat(sqlStates(failure)).containsExactly("57014");
    }

    /** The same through the store's EntityManager. */
    @Test
    void entityManagerStatementTimeoutIsDeadlineExceeded() {
        SynapseException failure = failure(() -> booksScript
                .execute(List
                        .of(
                                entityManager -> setLocal(entityManager, "statement_timeout", "100"),
                                entityManager -> entityManager
                                        .createNativeQuery("SELECT 1 FROM pg_sleep(5)")
                                        .getSingleResult())));

        assertThat(failure.code()).isEqualTo(SynapseErrors.DEADLINE_EXCEEDED.code());
        assertThat(sqlStates(failure)).containsExactly("57014");
    }

    /** {@code lock_timeout} gives up on a row another session holds (55P03): {@code INTERNAL} today. */
    @Test
    void lockTimeoutIsInternal() throws SQLException {
        long id = insert("locked-" + UUID.randomUUID());
        try (Connection other = connection(LEGACY)) {
            other.setAutoCommit(false);
            execute(other, "SELECT id FROM legacy_item WHERE id = " + id + " FOR UPDATE");

            SynapseException failure = failure(() -> legacyScript
                    .execute(List.of(jdbc -> setLocal(jdbc, "lock_timeout", "100"), jdbc -> lock(jdbc, id))));

            other.rollback();
            assertThat(failure.code()).isEqualTo(SynapseErrors.INTERNAL.code());
            assertThat(sqlStates(failure)).containsExactly("55P03");
        } finally {
            delete(id);
        }
    }

    /**
     * The same on a JPA store: a pessimistic lock on a book another session holds. Hibernate's PostgreSQL dialect
     * raises its {@code LockTimeoutException} for 55P03, which no translator maps: {@code INTERNAL} today.
     */
    @Test
    void jpaLockTimeoutIsInternal() throws SQLException {
        UUID id = id(bookRepository.save(new Book("Locked", "978-0-00-000007-0")));
        try (Connection other = connection(BOOKS)) {
            other.setAutoCommit(false);
            execute(other, "SELECT id FROM book WHERE id = '" + id + "' FOR UPDATE");

            SynapseException failure = failure(() -> booksScript
                    .execute(List
                            .of(entityManager -> setLocal(entityManager, "lock_timeout", "100"),
                                    entityManager -> entityManager
                                            .find(Book.class, id, LockModeType.PESSIMISTIC_WRITE))));

            other.rollback();
            assertThat(failure.code()).isEqualTo(SynapseErrors.INTERNAL.code());
            assertThat(sqlStates(failure)).containsExactly("55P03");
            assertThat(causes(failure)).anyMatch(org.hibernate.exception.LockTimeoutException.class::isInstance);
        } finally {
            books.sql("DELETE FROM book WHERE id = ?").param(id).update();
        }
    }

    /**
     * A repeatable-read transaction updates a row that another session changed after the transaction's snapshot
     * (40001): {@code INTERNAL} today.
     */
    @Test
    void serializationFailureIsInternal() throws SQLException {
        long id = insert("serialized-" + UUID.randomUUID());
        try (Connection other = connection(LEGACY)) {
            SynapseException failure = failure(() -> repeatableReadScript
                    .execute(List
                            .of(
                                    jdbc -> jdbc
                                            .sql("SELECT name FROM legacy_item WHERE id = ?")
                                            .param(id)
                                            .query(String.class)
                                            .single(),
                                    jdbc -> execute(other,
                                            "UPDATE legacy_item SET version = version + 1 WHERE id = " + id),
                                    jdbc -> jdbc
                                            .sql("UPDATE legacy_item SET version = version + 1 WHERE id = ?")
                                            .param(id)
                                            .update())));

            assertThat(failure.code()).isEqualTo(SynapseErrors.INTERNAL.code());
            assertThat(sqlStates(failure)).containsExactly("40001");
        } finally {
            delete(id);
        }
    }

    /**
     * The operation's transaction and another session lock two rows in opposite orders. The operation's session waits
     * the shorter {@code deadlock_timeout}, so it finds the deadlock and is the one PostgreSQL aborts (40P01):
     * {@code INTERNAL} today. The other session then gets its lock.
     */
    @Test
    void deadlockIsInternal() throws Exception {
        long first = insert("first-" + UUID.randomUUID());
        long second = insert("second-" + UUID.randomUUID());
        AtomicReference<@Nullable CompletableFuture<Void>> otherWaits = new AtomicReference<>();
        try (Connection other = connection(LEGACY)) {
            other.setAutoCommit(false);
            execute(other, "SET LOCAL deadlock_timeout = '10s'");
            execute(other, "SELECT id FROM legacy_item WHERE id = " + second + " FOR UPDATE");
            int otherPid = pid(other);

            SynapseException failure = failure(() -> legacyScript
                    .execute(List
                            .of(jdbc -> setLocal(jdbc, "deadlock_timeout", "200"), jdbc -> lock(jdbc, first),
                                    jdbc -> {
                                        otherWaits
                                                .set(CompletableFuture
                                                        .runAsync(() -> execute(other,
                                                                "SELECT id FROM legacy_item WHERE id = " + first
                                                                        + " FOR UPDATE")));
                                        awaitBlocked(jdbc, otherPid);
                                    }, jdbc -> lock(jdbc, second))));

            Objects
                    .requireNonNull(otherWaits.get(), "the other session's wait")
                    .get(WAIT.toSeconds(), TimeUnit.SECONDS);
            other.rollback();
            assertThat(failure.code()).isEqualTo(SynapseErrors.INTERNAL.code());
            assertThat(sqlStates(failure)).containsExactly("40P01");
        } finally {
            delete(first);
            delete(second);
        }
    }

    private static SynapseException failure(Runnable call) {
        SynapseException failure = catchThrowableOfType(SynapseException.class, call::run);
        assertThat(failure).as("the operation failed").isNotNull();
        return failure;
    }

    private static void setLocal(JdbcClient jdbc, String setting, String value) {
        jdbc.sql("SELECT set_config(?, ?, true)").param(setting).param(value).query(String.class).single();
    }

    private static void setLocal(EntityManager entityManager, String setting, String value) {
        entityManager
                .createNativeQuery("SELECT set_config(?1, ?2, true)")
                .setParameter(1, setting)
                .setParameter(2, value)
                .getSingleResult();
    }

    private static void lock(JdbcClient jdbc, long id) {
        jdbc.sql("SELECT id FROM legacy_item WHERE id = ? FOR UPDATE").param(id).query(Long.class).single();
    }

    /** Waits until a session waits for a lock. */
    private static void awaitBlocked(JdbcClient jdbc, int pid) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            Boolean blocked = jdbc
                    .sql("SELECT cardinality(pg_blocking_pids(?)) > 0")
                    .param(pid)
                    .query(Boolean.class)
                    .single();
            if (Boolean.TRUE.equals(blocked)) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("The other session never waited for the lock");
    }

    private long insert(String name) {
        return legacy
                .sql("INSERT INTO legacy_item (version, name) VALUES (0, ?) RETURNING id")
                .param(name)
                .query(Long.class)
                .single();
    }

    private int count(String name) {
        return legacy.sql("SELECT COUNT(*) FROM legacy_item WHERE name = ?").param(name).query(Integer.class).single();
    }

    private void delete(String name) {
        legacy.sql("DELETE FROM legacy_item WHERE name = ?").param(name).update();
    }

    private void delete(long id) {
        legacy.sql("DELETE FROM legacy_item WHERE id = ?").param(id).update();
    }

    private static Connection connection(PostgreSQLContainer container) throws SQLException {
        return DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    private static void execute(Connection connection, String sql) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static int pid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT pg_backend_pid()")) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private static UUID id(Book book) {
        UUID id = book.getId();
        if (id == null) {
            throw new AssertionError("The book has no id");
        }
        return id;
    }

    /** The SQLStates in a failure's cause chain, outermost first, each once. */
    static List<String> sqlStates(Throwable failure) {
        List<String> states = new ArrayList<>();
        int depth = 0;
        for (Throwable current = failure; current != null && depth < 20; current = current.getCause(), depth++) {
            if (current instanceof SQLException sql && sql.getSQLState() != null
                    && !states.contains(sql.getSQLState())) {
                states.add(sql.getSQLState());
            }
        }
        return states;
    }

    /** A failure and its causes, outermost first. */
    static List<Throwable> causes(Throwable failure) {
        List<Throwable> causes = new ArrayList<>();
        for (Throwable current = failure; current != null && causes.size() < 20; current = current.getCause()) {
            causes.add(current);
        }
        return causes;
    }

    /** A step of a script, run in the operation's transaction. */
    interface JdbcStep {

        void run(JdbcClient jdbc);
    }

    /** A step of a script, run with the books store's transactional EntityManager. */
    interface JpaStep {

        void run(EntityManager entityManager);
    }

    /** Adds two books with one ISBN in one transaction: neither is inserted before the commit. */
    static class AddTwoCopiesOperation implements CommandOperation<String, Integer> {

        private final BookRepository books;

        AddTwoCopiesOperation(BookRepository books) {
            this.books = books;
        }

        @Override
        @Transactional("books")
        public Integer execute(String isbn) {
            books.save(new Book("First copy", isbn));
            books.save(new Book("Second copy", isbn));
            return 2;
        }
    }

    /**
     * Reads a book twice, each copy in its own transaction, then saves both: the second save carries a stale version.
     */
    static class RenameBothCopiesOperation implements CommandOperation<UUID, Integer> {

        private final BookRepository books;

        RenameBothCopiesOperation(BookRepository books) {
            this.books = books;
        }

        @Override
        public Integer execute(UUID id) {
            Book first = books.findById(id).orElseThrow();
            Book second = books.findById(id).orElseThrow();
            first.rename("first copy");
            books.save(first);
            second.rename("second copy");
            books.save(second);
            return 2;
        }
    }

    /** Adds a legacy item, whose name the table keeps unique. */
    static class AddItemOperation implements CommandOperation<String, Long> {

        private final LegacyItemRepository items;

        AddItemOperation(LegacyItemRepository items) {
            this.items = items;
        }

        @Override
        public Long execute(String name) {
            Long id = items.save(new LegacyItem(name)).getId();
            if (id == null) {
                throw new IllegalStateException("The item has no id");
            }
            return id;
        }
    }

    /** Runs its steps in a legacy transaction. */
    static class LegacyScriptOperation implements CommandOperation<List<JdbcStep>, Integer> {

        private final JdbcClient jdbc;

        LegacyScriptOperation(@Qualifier("legacy") JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        @Transactional("legacy")
        public Integer execute(List<JdbcStep> steps) {
            steps.forEach(step -> step.run(jdbc));
            return steps.size();
        }
    }

    /** Runs its steps in a repeatable-read legacy transaction. */
    static class RepeatableReadScriptOperation implements CommandOperation<List<JdbcStep>, Integer> {

        private final JdbcClient jdbc;

        RepeatableReadScriptOperation(@Qualifier("legacy") JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        @Transactional(value = "legacy", isolation = Isolation.REPEATABLE_READ)
        public Integer execute(List<JdbcStep> steps) {
            steps.forEach(step -> step.run(jdbc));
            return steps.size();
        }
    }

    /** Runs its steps in a books transaction, with the store's transactional EntityManager. */
    static class BooksScriptOperation implements CommandOperation<List<JpaStep>, Integer> {

        private final EntityManagerFactory entityManagerFactory;

        BooksScriptOperation(@Qualifier("books") EntityManagerFactory entityManagerFactory) {
            this.entityManagerFactory = entityManagerFactory;
        }

        @Override
        @Transactional("books")
        public Integer execute(List<JpaStep> steps) {
            EntityManager entityManager = EntityManagerFactoryUtils
                    .getTransactionalEntityManager(entityManagerFactory);
            if (entityManager == null) {
                throw new IllegalStateException("No books transaction");
            }
            steps.forEach(step -> step.run(entityManager));
            return steps.size();
        }
    }
}
