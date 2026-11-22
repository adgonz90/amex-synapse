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
package io.americanexpress.synapse.data.jpa.fixture;

import io.americanexpress.synapse.data.vendor.DataStoreVendor;

/**
 * The H2 vendor the store tests use. Synapse ships no H2 vendor module.
 */
public class H2TestVendor implements DataStoreVendor {

    @Override
    public String name() {
        return "h2";
    }

    @Override
    public boolean supports(String jdbcUrl) {
        return jdbcUrl.startsWith("jdbc:h2:");
    }

    @Override
    public String defaultDatabaseVersion() {
        return "2.4";
    }

    @Override
    public String databaseProductName() {
        return "H2";
    }

    @Override
    public String hibernateDialect() {
        return "org.hibernate.dialect.H2Dialect";
    }

    @Override
    public String jdbcDialect() {
        return "org.springframework.data.jdbc.core.dialect.JdbcH2Dialect";
    }
}
