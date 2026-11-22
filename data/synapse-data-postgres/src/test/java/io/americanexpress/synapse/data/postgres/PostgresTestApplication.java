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

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

import io.americanexpress.synapse.data.postgres.fixture.books.BooksStore;
import io.americanexpress.synapse.data.postgres.fixture.legacy.LegacyStore;

/**
 * The service of the integration tests: it scans the fixture data module, which declares the books (JPA) and legacy
 * (JDBC) stores, and gets the PostgreSQL vendor from synapse-data-postgres's auto-configuration. Neither store is
 * primary.
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@ComponentScan(basePackageClasses = {BooksStore.class,
        LegacyStore.class}, excludeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class))
public class PostgresTestApplication {

    /**
     * Runs the service, as the JVM that {@code PostgresExitOnRefreshIT} launches does.
     *
     * @param args the arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(PostgresTestApplication.class, args);
    }
}
