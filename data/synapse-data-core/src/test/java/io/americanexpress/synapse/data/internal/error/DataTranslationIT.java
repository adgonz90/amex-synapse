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
package io.americanexpress.synapse.data.internal.error;

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
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.data.fixture.H2TestVendor;
import io.americanexpress.synapse.data.fixture.books.BooksStore;
import io.americanexpress.synapse.data.fixture.legacy.LegacyStore;

import static io.americanexpress.synapse.test.assertj.SynapseAssertions.assertThatSynapseException;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots an application with two H2 stores and calls operations through the pipeline proxy: a unique-constraint
 * violation on the {@code books} store becomes {@code ALREADY_EXISTS}, and the unreachable {@code legacy} store
 * becomes {@code UNAVAILABLE}. Neither message carries SQL, a table, a value or a URL.
 */
@Tag("smoke")
@SpringBootTest(classes = DataTranslationIT.TestApplication.class, properties = {
        "synapse.data.stores.books.url=jdbc:h2:mem:translation;DB_CLOSE_DELAY=-1",
        "synapse.data.stores.books.username=sa", "synapse.data.stores.books.password=",
        "synapse.data.stores.legacy.url=jdbc:h2:tcp://127.0.0.1:1/none", "synapse.data.stores.legacy.username=sa",
        "synapse.data.stores.legacy.password=", "synapse.data.stores.legacy.hikari.connection-timeout=250",
        "synapse.data.stores.legacy.migrations.enabled=false"})
@ActiveProfiles("test")
class DataTranslationIT {

    private static final String ISBN = "978-0-00-000000-1";

    @Autowired
    private AddBookTwiceOperation addBookTwice;

    @Autowired
    private CountLegacyBooksOperation countLegacyBooks;

    @Autowired
    @Qualifier("books")
    private JdbcClient books;

    @BeforeEach
    void createTheTable() {
        books.sql("DROP TABLE IF EXISTS book").update();
        books.sql("CREATE TABLE book (id INT AUTO_INCREMENT PRIMARY KEY, isbn VARCHAR(20) NOT NULL UNIQUE)").update();
    }

    @Test
    void duplicateInsertIsAlreadyExistsThroughThePipeline() {
        assertThat(AopUtils.isAopProxy(addBookTwice)).isTrue();

        assertThatSynapseException(() -> addBookTwice.execute(ISBN))
                .hasCode(SynapseErrors.ALREADY_EXISTS)
                .hasCategory(ErrorCategory.CONFLICT)
                .satisfies(failure -> {
                    assertThat(failure.getMessage()).isEqualTo("The resource already exists");
                    assertThat(failure.getCause()).isInstanceOf(DuplicateKeyException.class);
                });
        assertThat(books.sql("SELECT COUNT(*) FROM book").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void unreachableStoreIsUnavailable() {
        assertThatSynapseException(() -> countLegacyBooks.execute("all"))
                .hasCode(SynapseErrors.UNAVAILABLE)
                .hasCategory(ErrorCategory.UNAVAILABLE)
                .satisfies(failure -> {
                    assertThat(failure.getMessage()).isEqualTo("The service is unavailable");
                    assertThat(failure.getCause()).isInstanceOf(CannotGetJdbcConnectionException.class);
                });
    }

    /** Inserts the same ISBN twice into the books store; the second insert violates the unique constraint. */
    static class AddBookTwiceOperation implements CommandOperation<String, Integer> {

        private final JdbcClient books;

        AddBookTwiceOperation(JdbcClient books) {
            this.books = books;
        }

        @Override
        public Integer execute(String isbn) {
            int inserted = 0;
            for (int i = 0; i < 2; i++) {
                inserted += books.sql("INSERT INTO book (isbn) VALUES (:isbn)").param("isbn", isbn).update();
            }
            return inserted;
        }
    }

    /** Counts the books of the legacy store, which can't be reached. */
    static class CountLegacyBooksOperation implements QueryOperation<String, Integer> {

        private final JdbcClient legacy;

        CountLegacyBooksOperation(JdbcClient legacy) {
            this.legacy = legacy;
        }

        @Override
        public Integer execute(String filter) {
            return legacy.sql("SELECT COUNT(*) FROM book").query(Integer.class).single();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({H2TestVendor.class, BooksStore.class, LegacyStore.class})
    static class TestApplication {

        @Bean
        AddBookTwiceOperation addBookTwiceOperation(@Qualifier("books") JdbcClient books) {
            return new AddBookTwiceOperation(books);
        }

        @Bean
        CountLegacyBooksOperation countLegacyBooksOperation(@Qualifier("legacy") JdbcClient legacy) {
            return new CountLegacyBooksOperation(legacy);
        }
    }
}
