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
package io.americanexpress.synapse.data.jdbc.fixture.ownbeans;

import java.util.List;
import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.data.jdbc.core.convert.DataAccessStrategy;
import org.springframework.data.jdbc.core.convert.JdbcConverter;
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions;
import org.springframework.data.jdbc.core.dialect.JdbcDialect;
import org.springframework.data.jdbc.core.dialect.JdbcH2Dialect;
import org.springframework.jdbc.support.JdbcTransactionManager;

import io.americanexpress.synapse.data.SynapseDataStore;

/**
 * Declares the books store and replaces its transaction manager, dialect, conversions and aggregate template with its
 * own.
 */
@Configuration(proxyBeanMethods = false)
@SynapseDataStore("books")
public class BooksStoreWithOwnBeans {

    /** The default timeout of the replacement transaction manager, in seconds. */
    public static final int TIMEOUT = 42;

    /** The replacement dialect. */
    public static final JdbcDialect DIALECT = new JdbcH2Dialect();

    /** The replacement conversions. */
    public static final JdbcCustomConversions CONVERSIONS = JdbcCustomConversions.of(DIALECT, List.of());

    @Bean
    JdbcTransactionManager booksTransactionManager(@Qualifier("books") DataSource dataSource) {
        JdbcTransactionManager transactionManager = new JdbcTransactionManager(dataSource);
        transactionManager.setDefaultTimeout(TIMEOUT);
        return transactionManager;
    }

    @Bean
    JdbcDialect booksJdbcDialect() {
        return DIALECT;
    }

    @Bean
    JdbcCustomConversions booksJdbcCustomConversions() {
        return CONVERSIONS;
    }

    @Bean
    OwnAggregateTemplate booksJdbcAggregateTemplate(@Qualifier("books") JdbcConverter converter,
            @Qualifier("books") DataAccessStrategy dataAccessStrategy) {
        return new OwnAggregateTemplate(converter, dataAccessStrategy);
    }

    /** The replacement aggregate template. */
    public static class OwnAggregateTemplate extends JdbcAggregateTemplate {

        OwnAggregateTemplate(JdbcConverter converter, DataAccessStrategy dataAccessStrategy) {
            super(converter, dataAccessStrategy);
        }
    }
}
