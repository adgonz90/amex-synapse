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
package io.americanexpress.synapse.data.fixture.wrappedbooks;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import io.americanexpress.synapse.data.SynapseDataStore;

/**
 * Declares the books store and replaces its DataSource with one that wraps a Hikari pool.
 */
@Configuration(proxyBeanMethods = false)
@SynapseDataStore("books")
public class BooksStoreWithWrappedDataSource {

    /** The pool the DataSource wraps. */
    public static final HikariDataSource POOL = pool();

    @Bean
    DataSource booksDataSource() {
        return new DelegatingDataSource(POOL);
    }

    private static HikariDataSource pool() {
        HikariDataSource pool = new HikariDataSource();
        pool.setPoolName("books");
        pool.setJdbcUrl("jdbc:h2:mem:wrappedbooks;DB_CLOSE_DELAY=-1");
        pool.setUsername("sa");
        pool.setPassword("");
        return pool;
    }
}
