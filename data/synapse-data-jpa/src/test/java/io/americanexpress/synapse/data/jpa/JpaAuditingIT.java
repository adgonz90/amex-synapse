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
package io.americanexpress.synapse.data.jpa;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.hibernate.StaleStateException;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.stat.Statistics;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.data.jpa.fixture.H2TestVendor;
import io.americanexpress.synapse.data.jpa.fixture.audited.AuditedStore;
import io.americanexpress.synapse.data.jpa.fixture.audited.Book;
import io.americanexpress.synapse.data.jpa.fixture.audited.BookRepository;
import io.americanexpress.synapse.data.jpa.fixture.audited.LedgerEntry;
import io.americanexpress.synapse.data.jpa.fixture.audited.LegacyNote;
import io.americanexpress.synapse.test.operation.RequestContexts;
import io.americanexpress.synapse.test.time.MutableClock;

import static io.americanexpress.synapse.test.assertj.SynapseAssertions.assertThatSynapseException;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots an application with one H2 store, migrated from {@code db/migration/audited} and validated against its
 * entities, and checks the default entity listener: UUIDv7 ids from the application's clock, auditing from the clock
 * and the request context, the sequence of the {@code Long} bases, and Spring Data's single {@code INSERT} for a new
 * entity. Optimistic-lock and unique-constraint failures reach callers of an operation as {@code STALE_VERSION} and
 * {@code ALREADY_EXISTS}.
 */
@Tag("smoke")
@SpringBootTest(classes = JpaAuditingIT.TestApplication.class, properties = {
        "synapse.data.stores.audited.url=jdbc:h2:mem:jpa-audited;DB_CLOSE_DELAY=-1",
        "synapse.data.stores.audited.username=sa", "synapse.data.stores.audited.password=",
        "synapse.data.stores.audited.jpa.properties.hibernate.generate_statistics=true"})
@ActiveProfiles("test")
class JpaAuditingIT {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MutableClock clock;

    @Autowired
    private BookRepository books;

    @Autowired
    @Qualifier("audited")
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    @Qualifier("audited")
    private JdbcClient jdbc;

    @Autowired
    private RenameBothCopiesOperation renameBothCopies;

    @Autowired
    private AddBookOperation addBook;

    @Autowired
    private RenameInTwoEntityManagersOperation renameInTwoEntityManagers;

    /**
     * A later instant on every call than any other test uses: the generator never goes back in time, so an id's
     * timestamp is the clock's only while the clock is ahead of every id generated before.
     */
    private static Instant fresh = Instant.parse("2030-01-01T00:00:00Z");

    @BeforeEach
    void resetTheClock() {
        clock.set(START);
    }

    @Test
    void persistAssignsAUuidV7FromTheClock() {
        Instant now = freshInstant();
        clock.set(now);

        UUID id = id(books.save(new Book("Dune")));

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
        assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(now.toEpochMilli());
        assertThat(jdbc.sql("SELECT title FROM book WHERE id = ?").param(id).query(String.class).single())
                .isEqualTo("Dune");
    }

    /** Hibernate creates the listener through Spring, so it reads the context's clock: the test's MutableClock. */
    @Test
    void theCallbacksUseTheContextsClock() {
        Instant now = freshInstant();
        clock.set(now);

        Book book = books.save(new Book("Kindred"));

        assertThat(book.getCreatedAt()).isEqualTo(now);
        assertThat(id(book).getMostSignificantBits() >>> 16).isEqualTo(now.toEpochMilli());
    }

    @Test
    void auditingFillsCreatedAndModified() {
        UUID id = id(books.save(new Book("Emma")));

        Map<String, @Nullable Object> row = row("SELECT * FROM book WHERE id = ?", id);
        assertThat(instant(row, "created_at")).isEqualTo(START);
        assertThat(instant(row, "modified_at")).isEqualTo(START);
        assertThat(row).containsEntry("created_by", "system").containsEntry("modified_by", "system");
        assertThat(row.get("version")).isEqualTo(0L);
    }

    @Test
    void updateMovesModifiedOnly() {
        UUID id = id(books.save(new Book("Persuasion")));
        clock.advance(Duration.ofHours(1));

        Book loaded = books.findById(id).orElseThrow();
        loaded.rename("Persuasion (2nd edition)");
        books.save(loaded);

        Map<String, @Nullable Object> row = row("SELECT * FROM book WHERE id = ?", id);
        assertThat(instant(row, "created_at")).isEqualTo(START);
        assertThat(instant(row, "modified_at")).isEqualTo(START.plus(Duration.ofHours(1)));
        assertThat(row).containsEntry("title", "Persuasion (2nd edition)").containsEntry("version", 1L);
    }

    @Test
    void principalFromTheRequestContextIsTheAuditor() {
        UUID id = RequestContexts.with().principal(() -> "ana").call(() -> id(books.save(new Book("Beloved"))));

        assertThat(row("SELECT * FROM book WHERE id = ?", id))
                .containsEntry("created_by", "ana")
                .containsEntry("modified_by", "ana");
    }

    @Test
    void customColumnsAreAudited() {
        inTransaction(entityManager -> entityManager.persist(new LegacyNote(1, "first")));

        Map<String, @Nullable Object> row = row("SELECT * FROM legacy_note WHERE id = ?", 1L);
        assertThat(instant(row, "note_created")).isEqualTo(START);
        assertThat(instant(row, "note_modified")).isEqualTo(START);
        assertThat(row).containsEntry("note_created_by", "system").containsEntry("note_modified_by", "system");

        clock.advance(Duration.ofMinutes(5));
        inTransaction(entityManager -> entityManager.merge(new LegacyNote(1, "second")));

        Map<String, @Nullable Object> updated = row("SELECT * FROM legacy_note WHERE id = ?", 1L);
        assertThat(instant(updated, "note_created")).isEqualTo(START);
        assertThat(instant(updated, "note_modified")).isEqualTo(START.plus(Duration.ofMinutes(5)));
        assertThat(updated).containsEntry("body", "second");
    }

    @Test
    void longIdBasesUseTheSequence() {
        LedgerEntry first = new LedgerEntry(10);
        LedgerEntry second = new LedgerEntry(20);

        inTransaction(entityManager -> {
            entityManager.persist(first);
            entityManager.persist(second);
        });

        assertThat(first.getId()).isEqualTo(1L);
        assertThat(second.getId()).isEqualTo(2L);
        assertThat(jdbc.sql("SELECT id FROM ledger_entry ORDER BY id").query(Long.class).list())
                .containsExactly(1L, 2L);
        Map<String, @Nullable Object> row = row("SELECT * FROM ledger_entry WHERE id = ?", 1L);
        assertThat(instant(row, "created_at")).isEqualTo(START);
        assertThat(row).containsEntry("created_by", "system").containsEntry("version", 0L);
    }

    @Test
    void saveOfANewEntityPersistsOnce() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        books.save(new Book("Middlemarch"));

        assertThat(statistics.getEntityInsertCount()).isEqualTo(1);
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(statistics.getPrepareStatementCount()).as("one INSERT, no SELECT").isEqualTo(1);
    }

    @Test
    void versionConflictIsStaleVersion() {
        UUID id = id(books.save(new Book("Ulysses")));
        assertThat(AopUtils.isAopProxy(renameBothCopies)).isTrue();

        assertThatSynapseException(() -> renameBothCopies.execute(id))
                .hasCode(SynapseErrors.STALE_VERSION)
                .hasCategory(ErrorCategory.CONFLICT);
        assertThat(row("SELECT * FROM book WHERE id = ?", id))
                .containsEntry("title", "first copy")
                .containsEntry("version", 1L);
    }

    @Test
    void uniqueViolationIsAlreadyExists() {
        addBook.execute("978-0-00-000000-1");

        assertThatSynapseException(() -> addBook.execute("978-0-00-000000-1"))
                .hasCode(SynapseErrors.ALREADY_EXISTS)
                .hasCategory(ErrorCategory.CONFLICT)
                .satisfies(failure -> assertThat(failure.getMessage()).isEqualTo("The resource already exists"));
        assertThat(jdbc
                .sql("SELECT COUNT(*) FROM book WHERE isbn = ?")
                .param("978-0-00-000000-1")
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    /**
     * A failure Spring never translates: two EntityManagers of the store's factory, outside Spring's transactions,
     * update the same book. The pipeline gets JPA's and Hibernate's own exceptions (Hibernate's
     * {@link StaleStateException} below JPA's {@code OptimisticLockException}), which only the JPA translator knows.
     */
    @Test
    void anUntranslatedStaleUpdateIsStaleVersion() {
        UUID id = id(books.save(new Book("Middlemarch")));
        assertThat(AopUtils.isAopProxy(renameInTwoEntityManagers)).isTrue();

        assertThatSynapseException(() -> renameInTwoEntityManagers.execute(id))
                .hasCode(SynapseErrors.STALE_VERSION)
                .hasCategory(ErrorCategory.CONFLICT)
                .satisfies(failure -> {
                    List<Throwable> chain = causes(failure);
                    assertThat(chain).anyMatch(StaleStateException.class::isInstance);
                    assertThat(chain).as("Spring translated nothing").noneMatch(DataAccessException.class::isInstance);
                });
        assertThat(row("SELECT * FROM book WHERE id = ?", id))
                .containsEntry("title", "first manager")
                .containsEntry("version", 1L);
    }

    /** The stamps are truncated to the column's microseconds, so the entity equals what the store gives back. */
    @Test
    void aNanosecondClockStampsWhatTheStoreKeeps() {
        clock.set(Instant.parse("2026-01-01T00:00:00.123456789Z"));

        Book saved = books.save(new Book("Kindred"));

        Instant micros = Instant.parse("2026-01-01T00:00:00.123456Z");
        assertThat(saved.getCreatedAt()).isEqualTo(micros);
        assertThat(saved.getModifiedAt()).isEqualTo(micros);
        Book reloaded = books.findById(id(saved)).orElseThrow();
        assertThat(reloaded).isNotSameAs(saved);
        assertThat(reloaded.getCreatedAt()).isEqualTo(saved.getCreatedAt());
        assertThat(reloaded.getModifiedAt()).isEqualTo(saved.getModifiedAt());
        assertThat(instant(row("SELECT * FROM book WHERE id = ?", id(saved)), "created_at")).isEqualTo(micros);
    }

    /** Equality with a Hibernate proxy reads the proxy's id and class without loading it. */
    @Test
    void equalityDoesNotInitializeAProxy() {
        Book saved = books.save(new Book("Proxied"));
        // An entry with an id of its own: a reference never reads the row, so none is needed (and the sequence's
        // ids stay those longIdBasesUseTheSequence expects).
        LedgerEntry entry = new LedgerEntry(30);
        ReflectionTestUtils.setField(entry, LongIdEntity.class, "id", 999L, Long.class);

        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            Book proxy = entityManager.getReference(Book.class, id(saved));
            LedgerEntry entryProxy = entityManager.getReference(LedgerEntry.class, 999L);
            assertThat(proxy).isInstanceOf(HibernateProxy.class);
            assertThat(entryProxy).isInstanceOf(HibernateProxy.class);

            assertThat(saved.equals(proxy)).isTrue();
            assertThat(entry.equals(entryProxy)).isTrue();
            assertThat(new Book("Another").equals(proxy)).isFalse();

            assertThat(Hibernate.isInitialized(proxy)).as("the book proxy is not loaded").isFalse();
            assertThat(Hibernate.isInitialized(entryProxy)).as("the entry proxy is not loaded").isFalse();
            assertThat(saved.hashCode()).isEqualTo(Book.class.hashCode());
        }
    }

    /** With another system user, in an application of its own. */
    @Nested
    @TestPropertySource(properties = {"synapse.data.auditing.system-user=batch",
            "synapse.data.stores.audited.url=jdbc:h2:mem:jpa-audited-batch;DB_CLOSE_DELAY=-1"})
    class WithAnotherSystemUser {

        @Autowired
        private BookRepository batchBooks;

        @Autowired
        @Qualifier("audited")
        private JdbcClient batchJdbc;

        @Test
        void systemUserIsConfigurable() {
            UUID id = id(batchBooks.save(new Book("Night")));

            assertThat(batchJdbc.sql("SELECT created_by FROM book WHERE id = ?").param(id).query(String.class).single())
                    .isEqualTo("batch");
        }
    }

    private void inTransaction(Consumer<EntityManager> work) {
        try (EntityManager entityManager = entityManagerFactory.createEntityManager()) {
            entityManager.getTransaction().begin();
            work.accept(entityManager);
            entityManager.getTransaction().commit();
        }
    }

    private Map<String, @Nullable Object> row(String sql, Object id) {
        List<Map<String, @Nullable Object>> rows = jdbc.sql(sql).param(id).query().listOfRows();
        assertThat(rows).hasSize(1);
        return lowerCaseKeys(rows.getFirst());
    }

    private static Map<String, @Nullable Object> lowerCaseKeys(Map<String, @Nullable Object> row) {
        Map<String, @Nullable Object> lower = new LinkedHashMap<>();
        row.forEach((key, value) -> lower.put(key.toLowerCase(Locale.ROOT), value));
        return lower;
    }

    private static List<Throwable> causes(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable current = failure; current != null && chain.size() < 20; current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }

    private static Instant freshInstant() {
        fresh = fresh.plus(Duration.ofDays(1));
        return fresh;
    }

    /** The id of a saved book, which the listener assigned. */
    static UUID id(Book book) {
        UUID id = book.getId();
        if (id == null) {
            throw new AssertionError("The book has no id");
        }
        return id;
    }

    private static Instant instant(Map<String, @Nullable Object> row, String column) {
        if (!(row.get(column) instanceof OffsetDateTime value)) {
            throw new AssertionError(column + " is not a timestamp with time zone: " + row.get(column));
        }
        return value.toInstant();
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

    /**
     * Reads a book in two EntityManagers of the store's factory, outside Spring's transactions, and commits a change
     * in each: the second commit carries a stale version.
     */
    static class RenameInTwoEntityManagersOperation implements CommandOperation<UUID, Integer> {

        private final EntityManagerFactory entityManagerFactory;

        RenameInTwoEntityManagersOperation(EntityManagerFactory entityManagerFactory) {
            this.entityManagerFactory = entityManagerFactory;
        }

        @Override
        public Integer execute(UUID id) {
            try (EntityManager first = entityManagerFactory.createEntityManager();
                    EntityManager second = entityManagerFactory.createEntityManager()) {
                first.getTransaction().begin();
                second.getTransaction().begin();
                Book firstCopy = first.find(Book.class, id);
                Book secondCopy = second.find(Book.class, id);
                firstCopy.rename("first manager");
                first.getTransaction().commit();
                secondCopy.rename("second manager");
                second.getTransaction().commit();
            }
            return 2;
        }
    }

    /** Adds a book with an ISBN, which the table keeps unique. */
    static class AddBookOperation implements CommandOperation<String, UUID> {

        private final BookRepository books;

        AddBookOperation(BookRepository books) {
            this.books = books;
        }

        @Override
        public UUID execute(String isbn) {
            return id(books.save(new Book("With an ISBN", isbn)));
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({H2TestVendor.class, AuditedStore.class})
    static class TestApplication {

        @Bean
        MutableClock clock() {
            return MutableClock.at("2026-01-01T00:00:00Z");
        }

        @Bean
        RenameBothCopiesOperation renameBothCopiesOperation(BookRepository books) {
            return new RenameBothCopiesOperation(books);
        }

        @Bean
        RenameInTwoEntityManagersOperation renameInTwoEntityManagersOperation(
                @Qualifier("audited") EntityManagerFactory entityManagerFactory) {
            return new RenameInTwoEntityManagersOperation(entityManagerFactory);
        }

        @Bean
        AddBookOperation addBookOperation(BookRepository books) {
            return new AddBookOperation(books);
        }
    }
}
