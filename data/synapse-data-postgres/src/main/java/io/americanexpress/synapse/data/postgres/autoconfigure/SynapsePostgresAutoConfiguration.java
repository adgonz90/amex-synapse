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
package io.americanexpress.synapse.data.postgres.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import io.americanexpress.synapse.data.postgres.PostgresVendor;
import io.americanexpress.synapse.data.vendor.DataStoreVendor;

/**
 * Contributes the PostgreSQL {@link DataStoreVendor} when the PostgreSQL JDBC driver is on the class path. Synapse
 * gives it every store whose URL starts with {@code jdbc:postgresql:}.
 */
@AutoConfiguration
@ConditionalOnClass(org.postgresql.Driver.class)
public final class SynapsePostgresAutoConfiguration {

    private static final String VENDOR = "postgresDataStoreVendor";

    SynapsePostgresAutoConfiguration() {
    }

    /**
     * The PostgreSQL vendor. Define a bean with the same name to replace it.
     *
     * @return the vendor
     */
    @Bean(VENDOR)
    @ConditionalOnMissingBean(name = VENDOR)
    static PostgresVendor postgresDataStoreVendor() {
        return new PostgresVendor();
    }
}
