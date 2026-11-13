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
package io.americanexpress.synapse.samples.bookstore.data.books;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.ExceptionTranslator;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.test.data.StoreConnection;
import io.americanexpress.synapse.test.data.SynapseContainers;
import io.americanexpress.synapse.test.data.SynapseDataTest;
import io.americanexpress.synapse.test.operation.OperationInvoker;
import io.americanexpress.synapse.test.operation.SynapseOperations;

import static io.americanexpress.synapse.test.assertj.SynapseAssertions.assertThatSynapseException;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The books store on PostgreSQL (Docker): {@code @SynapseDataTest("books")} starts the store alone, migrated by its
 * Flyway scripts and validated against its entity, and runs each test in a books transaction that is rolled back.
 * {@code @StoreConnection} connects the store to a container instead of a {@code url}.
 */
@SynapseDataTest("books")
@Tag("smoke")
class BookRepositoryIT {

    /** The JVM's shared PostgreSQL container: this module has one store, so it needs no key of its own. */
    @StoreConnection("books")
    static final PostgreSQLContainer BOOKS = SynapseContainers.postgres();

    @Autowired
    private BookRepository repository;

    @Autowired
    @Qualifier("books")
    private Flyway flyway;

    /** Synapse's translators of data access failures, as the operation pipeline of a service has them. */
    @Autowired
    @Qualifier("synapseDataExceptionTranslator")
    private ExceptionTranslator dataTranslator;

    @Autowired
    @Qualifier("synapseJpaExceptionTranslator")
    private ExceptionTranslator jpaTranslator;

    /**
     * The context started, so the schema matched {@link StoredBook}: the store validates its entities against the
     * schema at startup, after its Flyway ran. That schema is the one migration V1 made, and nothing is pending.
     */
    @Test
    void migratesAndValidates() {
        assertThat(flyway.info().applied())
                .extracting(MigrationInfo::getVersion)
                .extracting(MigrationVersion::getVersion)
                .containsExactly("1");
        assertThat(flyway.info().pending()).isEmpty();
    }

    /**
     * Two books with one ISBN break the table's unique constraint when the second is flushed: Synapse's translators
     * make that {@code ALREADY_EXISTS} in the category {@code CONFLICT}, as the operation pipeline of the service does
     * (REST 409, gRPC {@code ALREADY_EXISTS}).
     */
    @Test
    void isbnIsUnique() {
        repository.saveAndFlush(leGuin("978-0-553-38368-3", "A Wizard of Earthsea"));
        assertThat(repository.existsByIsbn("978-0-553-38368-3")).isTrue();
        assertThat(repository.existsByIsbn("978-0-441-47812-5")).isFalse();

        OperationInvoker invoker = SynapseOperations
                .with()
                .translator(dataTranslator)
                .translator(jpaTranslator)
                .invoker();

        assertThatSynapseException(
                () -> invoker.invoke(new SaveBook(repository), leGuin("978-0-553-38368-3", "Another title")))
                .hasCode(SynapseErrors.ALREADY_EXISTS)
                .hasCategory(ErrorCategory.CONFLICT);
    }

    /** JPA's {@code @Version}: 0 when the book is added, plus 1 on every change that is written. */
    @Test
    void versionStartsAtZeroAndIncrements() {
        StoredBook book = repository.saveAndFlush(new StoredBook("978-0-441-17271-9", "Dune", "Frank Herbert", null));
        assertThat(book.getVersion()).isZero();

        book.revise("978-0-441-17271-9", "Dune", "Frank Herbert", 1965);
        StoredBook revised = repository.saveAndFlush(book);

        assertThat(revised.getVersion()).isOne();
        assertThat(revised.getPublishedYear()).isEqualTo(1965);
    }

    /** Part of the author's name, in any case, one page at a time in the order of the {@code Pageable}. */
    @Test
    void searchesByAuthorIgnoringCase() {
        repository.saveAndFlush(leGuin("978-0-441-47812-5", "The Left Hand of Darkness"));
        repository.saveAndFlush(leGuin("978-0-553-38368-3", "A Wizard of Earthsea"));
        repository.saveAndFlush(new StoredBook("978-0-441-17271-9", "Dune", "Frank Herbert", 1965));

        Page<StoredBook> first = repository
                .findByAuthorContainingIgnoreCase("le guin",
                        PageRequest.of(0, 1, Sort.by("title")));
        Page<StoredBook> second = repository.findByAuthorContainingIgnoreCase("le guin", first.nextPageable());

        assertThat(first.getContent()).extracting(StoredBook::getTitle).containsExactly("A Wizard of Earthsea");
        assertThat(first.getTotalElements()).isEqualTo(2);
        assertThat(first.hasNext()).isTrue();
        assertThat(second.getContent()).extracting(StoredBook::getTitle).containsExactly("The Left Hand of Darkness");
        assertThat(second.hasNext()).isFalse();
    }

    private static StoredBook leGuin(String isbn, String title) {
        return new StoredBook(isbn, title, "Ursula K. Le Guin", null);
    }

    /** Saves a book: the repository call of the test, run through the operation pipeline. */
    static class SaveBook implements CommandOperation<StoredBook, StoredBook> {

        private final BookRepository repository;

        SaveBook(BookRepository repository) {
            this.repository = repository;
        }

        @Override
        public StoredBook execute(StoredBook book) {
            return repository.saveAndFlush(book);
        }
    }
}
