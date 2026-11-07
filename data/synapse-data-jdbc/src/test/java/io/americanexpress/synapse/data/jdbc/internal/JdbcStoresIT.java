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
package io.americanexpress.synapse.data.jdbc.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.data.jdbc.fixture.H2TestVendor;
import io.americanexpress.synapse.data.jdbc.fixture.books.Book;
import io.americanexpress.synapse.data.jdbc.fixture.books.BookRepository;
import io.americanexpress.synapse.data.jdbc.fixture.books.BooksStore;
import io.americanexpress.synapse.data.jdbc.fixture.legacy.LegacyItem;
import io.americanexpress.synapse.data.jdbc.fixture.legacy.LegacyItemRepository;
import io.americanexpress.synapse.data.jdbc.fixture.legacy.LegacyStore;
import io.americanexpress.synapse.data.jdbc.fixture.legacy.Shelf;
import io.americanexpress.synapse.data.jdbc.fixture.legacy.ShelfRepository;
import io.americanexpress.synapse.data.jdbc.fixture.legacy.Slot;
import io.americanexpress.synapse.test.operation.RequestContexts;
import io.americanexpress.synapse.test.time.MutableClock;

import static io.americanexpress.synapse.test.assertj.SynapseAssertions.assertThatSynapseException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots an application with two H2 JDBC stores and no primary, each migrated from its own
 * {@code db/migration/<store>}, and checks that each store's repositories, auditing, optimistic locking and
 * transactions work on that store. Version conflicts and unique-constraint failures reach callers of an operation as
 * {@code STALE_VERSION} and {@code ALREADY_EXISTS}. The legacy store also has ids from a sequence, a relation and a
 * derived query.
 */
@Tag("smoke")
@SpringBootTest(classes = JdbcStoresIT.TestApplication.class, properties = {
        "synapse.data.stores.books.url=jdbc:h2:mem:jdbc-it-books;DB_CLOSE_DELAY=-1",
        "synapse.data.stores.books.username=sa", "synapse.data.stores.books.password=",
        "synapse.data.stores.legacy.url=jdbc:h2:mem:jdbc-it-legacy;DB_CLOSE_DELAY=-1",
        "synapse.data.stores.legacy.username=sa", "synapse.data.stores.legacy.password="})
@ActiveProfiles("test")
class JdbcStoresIT {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MutableClock clock;

    @Autowired
    private BookRepository books;

    @Autowired
    private LegacyItemRepository legacyItems;

    @Autowired
    private ShelfRepository shelves;

    @Autowired
    @Qualifier("books")
    private JdbcClient booksJdbc;

    @Autowired
    @Qualifier("legacy")
    private JdbcClient legacyJdbc;

    @Autowired
    private Writers writers;

    @Autowired
    private RenameBothCopiesOperation renameBothCopies;

    @Autowired
    private AddBookOperation addBook;

    @BeforeEach
    void resetTheClock() {
        clock.set(START);
    }

    @Test
    void repositoriesWorkPerStore() {
        Book book = books.save(new Book("Dune"));
        LegacyItem item = legacyItems.save(new LegacyItem("lamp"));

        assertThat(book.getId()).isNotNull();
        assertThat(item.getId()).isNotNull();
        assertThat(
                booksJdbc.sql("SELECT title FROM book WHERE id = ?").param(book.getId()).query(String.class).single())
                .isEqualTo("Dune");
        assertThat(legacyJdbc
                .sql("SELECT name FROM legacy_item WHERE id = ?")
                .param(item.getId())
                .query(String.class)
                .single()).isEqualTo("lamp");
        assertThat(tableCount(booksJdbc, "legacy_item")).as("the books store has no legacy items").isZero();
        assertThat(tableCount(legacyJdbc, "book")).as("the legacy store has no books").isZero();
        assertThat(books.findById(id(book)).map(Book::getTitle)).contains("Dune");
    }

    @Test
    void auditingStampsAggregates() {
        Book saved = books.save(new Book("Emma"));

        Map<String, @Nullable Object> row = row(id(saved));
        assertThat(instant(row, "created_at")).isEqualTo(START);
        assertThat(instant(row, "modified_at")).isEqualTo(START);
        assertThat(row)
                .containsEntry("created_by", "system")
                .containsEntry("modified_by", "system")
                .containsEntry("version", 0L);

        clock.advance(Duration.ofHours(1));
        Book loaded = books.findById(id(saved)).orElseThrow();
        assertThat(loaded.getCreatedAt()).as("read back from the store").isEqualTo(START);
        loaded.rename("Emma (2nd edition)");
        books.save(loaded);

        Map<String, @Nullable Object> updated = row(id(saved));
        assertThat(instant(updated, "created_at")).isEqualTo(START);
        assertThat(instant(updated, "modified_at")).isEqualTo(START.plus(Duration.ofHours(1)));
        assertThat(updated).containsEntry("title", "Emma (2nd edition)").containsEntry("version", 1L);
    }

    @Test
    void principalFromTheRequestContextIsTheAuditor() {
        Book saved = RequestContexts.with().principal(() -> "ana").call(() -> books.save(new Book("Beloved")));

        assertThat(row(id(saved))).containsEntry("created_by", "ana").containsEntry("modified_by", "ana");
    }

    @Test
    void versionConflictIsStaleVersion() {
        Book saved = books.save(new Book("Ulysses"));
        assertThat(AopUtils.isAopProxy(renameBothCopies)).isTrue();

        assertThatSynapseException(() -> renameBothCopies.execute(id(saved)))
                .hasCode(SynapseErrors.STALE_VERSION)
                .hasCategory(ErrorCategory.CONFLICT)
                .satisfies(failure -> assertThat(causes(failure))
                        .as("Spring Data JDBC's own exception, which data-core's translator knows")
                        .anyMatch(OptimisticLockingFailureException.class::isInstance));
        assertThat(row(id(saved))).containsEntry("title", "first copy").containsEntry("version", 1L);
    }

    /**
     * Spring JDBC translates the unique-constraint violation into a {@link DuplicateKeyException}, which data-core's
     * translator knows: no JDBC translator of its own is needed.
     */
    @Test
    void duplicateIsAlreadyExists() {
        addBook.execute("978-0-00-000000-1");

        assertThatSynapseException(() -> addBook.execute("978-0-00-000000-1"))
                .hasCode(SynapseErrors.ALREADY_EXISTS)
                .hasCategory(ErrorCategory.CONFLICT)
                .satisfies(failure -> {
                    assertThat(failure.getMessage()).isEqualTo("The resource already exists");
                    assertThat(failure.getCause()).isInstanceOf(DuplicateKeyException.class);
                });
        assertThat(booksJdbc
                .sql("SELECT COUNT(*) FROM book WHERE isbn = ?")
                .param("978-0-00-000000-1")
                .query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void transactionalWithAStoreName() {
        Binding binding = writers.saveLegacyItem("desk");

        assertThat(binding.legacy()).as("the legacy DataSource is bound").isTrue();
        assertThat(binding.books()).as("the books DataSource is not bound").isFalse();
        assertThat(legacyJdbc.sql("SELECT COUNT(*) FROM legacy_item WHERE name = 'desk'").query(Integer.class).single())
                .isEqualTo(1);
    }

    /** The store's own sequence, through Spring Data's id-generating callback bound to the store's beans. */
    @Test
    void sequenceIdsComeFromTheStoresSequence() {
        Shelf first = shelves.save(new Shelf("first"));
        Shelf second = shelves.save(new Shelf("second"));

        long firstId = Objects.requireNonNull(first.getId());
        assertThat(firstId).isGreaterThanOrEqualTo(100L);
        assertThat(second.getId()).isEqualTo(firstId + 1);
        assertThat(legacyJdbc.sql("SELECT name FROM shelf WHERE id = ?").param(firstId).query(String.class).single())
                .isEqualTo("first");
    }

    /** The relation is loaded through the converter's resolver, which uses the store's data access strategy. */
    @Test
    void relationsAreSavedAndLoaded() {
        long id = Objects.requireNonNull(shelves.save(new Shelf("hall", "top", "bottom")).getId());

        assertThat(legacyJdbc.sql("SELECT label FROM slot WHERE shelf = ?").param(id).query(String.class).list())
                .containsExactlyInAnyOrder("top", "bottom");
        assertThat(shelves.findById(id).orElseThrow().getSlots())
                .extracting(Slot::label)
                .containsExactlyInAnyOrder("top", "bottom");
    }

    @Test
    void derivedQueriesRunOnTheStore() {
        shelves.save(new Shelf("garage", "tools"));

        assertThat(shelves.findByName("garage"))
                .singleElement()
                .satisfies(shelf -> assertThat(shelf.getSlots()).extracting(Slot::label).containsExactly("tools"));
        assertThat(shelves.findByName("attic")).isEmpty();
    }

    @Test
    void rollbackOnException() {
        assertThatThrownBy(() -> writers.saveBookThenFail("Rebecca"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("after the insert");

        assertThat(booksJdbc.sql("SELECT COUNT(*) FROM book WHERE title = 'Rebecca'").query(Integer.class).single())
                .isZero();
    }

    private Map<String, @Nullable Object> row(long id) {
        List<Map<String, @Nullable Object>> rows = booksJdbc
                .sql("SELECT * FROM book WHERE id = ?")
                .param(id)
                .query()
                .listOfRows();
        assertThat(rows).hasSize(1);
        Map<String, @Nullable Object> lower = new LinkedHashMap<>();
        rows.getFirst().forEach((key, value) -> lower.put(key.toLowerCase(Locale.ROOT), value));
        return lower;
    }

    private static List<Throwable> causes(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable current = failure; current != null && chain.size() < 20; current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }

    private static int tableCount(JdbcClient jdbc, String table) {
        return jdbc
                .sql("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) = ?")
                .param(table)
                .query(Integer.class)
                .single();
    }

    private static Instant instant(Map<String, @Nullable Object> row, String column) {
        if (!(row.get(column) instanceof OffsetDateTime value)) {
            throw new AssertionError(column + " is not a timestamp with time zone: " + row.get(column));
        }
        return value.toInstant();
    }

    /** The id of a saved book, which the database generated. */
    static long id(Book book) {
        Long id = book.getId();
        if (id == null) {
            throw new AssertionError("The book has no id");
        }
        return id;
    }

    /** Which stores' DataSources a transaction had bound. */
    record Binding(boolean books, boolean legacy) {
    }

    /** Writes in each store's transactions. */
    public static class Writers {

        private final BookRepository books;

        private final LegacyItemRepository legacyItems;

        private final DataSource booksDataSource;

        private final DataSource legacyDataSource;

        Writers(BookRepository books, LegacyItemRepository legacyItems, DataSource booksDataSource,
                DataSource legacyDataSource) {
            this.books = books;
            this.legacyItems = legacyItems;
            this.booksDataSource = booksDataSource;
            this.legacyDataSource = legacyDataSource;
        }

        @Transactional("legacy")
        public Binding saveLegacyItem(String name) {
            legacyItems.save(new LegacyItem(name));
            return new Binding(TransactionSynchronizationManager.hasResource(booksDataSource),
                    TransactionSynchronizationManager.hasResource(legacyDataSource));
        }

        @Transactional("books")
        public void saveBookThenFail(String title) {
            books.save(new Book(title));
            throw new IllegalStateException("after the insert");
        }
    }

    /** Reads a book twice, then saves both copies: the second save carries a stale version. */
    static class RenameBothCopiesOperation implements CommandOperation<Long, Integer> {

        private final BookRepository books;

        RenameBothCopiesOperation(BookRepository books) {
            this.books = books;
        }

        @Override
        public Integer execute(Long id) {
            Book first = books.findById(id).orElseThrow();
            Book second = books.findById(id).orElseThrow();
            first.rename("first copy");
            books.save(first);
            second.rename("second copy");
            books.save(second);
            return 2;
        }
    }

    /** Adds a book with an ISBN, which the table keeps unique. */
    static class AddBookOperation implements CommandOperation<String, Long> {

        private final BookRepository books;

        AddBookOperation(BookRepository books) {
            this.books = books;
        }

        @Override
        public Long execute(String isbn) {
            return id(books.save(new Book("With an ISBN", isbn)));
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({H2TestVendor.class, BooksStore.class, LegacyStore.class})
    static class TestApplication {

        @Bean
        MutableClock clock() {
            return MutableClock.at("2026-01-01T00:00:00Z");
        }

        @Bean
        Writers writers(BookRepository books, LegacyItemRepository legacyItems,
                @Qualifier("books") DataSource booksDataSource, @Qualifier("legacy") DataSource legacyDataSource) {
            return new Writers(books, legacyItems, booksDataSource, legacyDataSource);
        }

        @Bean
        RenameBothCopiesOperation renameBothCopiesOperation(BookRepository books) {
            return new RenameBothCopiesOperation(books);
        }

        @Bean
        AddBookOperation addBookOperation(BookRepository books) {
            return new AddBookOperation(books);
        }
    }
}
