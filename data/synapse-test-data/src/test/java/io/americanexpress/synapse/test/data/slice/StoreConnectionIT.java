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
package io.americanexpress.synapse.test.data.slice;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.americanexpress.synapse.test.data.StoreConnection;
import io.americanexpress.synapse.test.data.StubJdbcContainer;
import io.americanexpress.synapse.test.data.SynapseDataTest;
import io.americanexpress.synapse.test.data.slice.books.Book;
import io.americanexpress.synapse.test.data.slice.books.BookRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A store connected with {@code @StoreConnection}: the store factory finds the registered connection details by the
 * store's qualifier, although they are not a default candidate, and they win over the store's {@code url} in
 * {@code application-test.yaml}. The container is a stub that answers another H2 database, so no Docker is needed.
 */
@SynapseDataTest("books")
@Tag("smoke")
class StoreConnectionIT {

    private static final String URL = "jdbc:h2:mem:store-connection-books;DB_CLOSE_DELAY=-1";

    @StoreConnection("books")
    static final StubJdbcContainer BOOKS = new StubJdbcContainer(URL, "sa", "");

    @Autowired
    private ApplicationContext context;

    @Autowired
    @Qualifier("books")
    private DataSource dataSource;

    @Autowired
    @Qualifier("books")
    private JdbcClient jdbc;

    @Autowired
    private BookRepository books;

    @Test
    void theStoreConnectsThroughTheConnectionDetails() {
        assertThat(context.getBean("booksStoreConnectionDetails")).isInstanceOf(JdbcConnectionDetails.class);
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        assertThat(((HikariDataSource) dataSource).getJdbcUrl()).isEqualTo(URL);
    }

    @Test
    void theStoreIsMigratedAndUsedThroughTheConnection() {
        books.saveAndFlush(new Book("Dune"));

        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single())
                .isEqualToIgnoringCase("store-connection-books");
        assertThat(jdbc.sql("SELECT title FROM book").query(String.class).single()).isEqualTo("Dune");
    }
}
