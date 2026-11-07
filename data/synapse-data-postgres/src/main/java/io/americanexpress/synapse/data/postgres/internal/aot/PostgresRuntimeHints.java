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
package io.americanexpress.synapse.data.postgres.internal.aot;

import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

import io.americanexpress.synapse.data.postgres.PostgresVendor;

/**
 * Registers what a native image needs for the PostgreSQL vendor's dialects. The public static {@code INSTANCE} field
 * of the Spring Data JDBC dialect is read reflectively by synapse-data-jdbc for every JDBC store. The public
 * constructors of the Hibernate dialect are needed only when a store names that dialect itself (for example with
 * {@code jpa.database-platform}): otherwise Hibernate resolves the dialect from the vendor's product name and creates
 * it directly. The classes are named, not linked: neither Hibernate nor Spring Data JDBC is a dependency of this
 * module.
 */
public final class PostgresRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
        PostgresVendor vendor = new PostgresVendor();
        hints
                .reflection()
                .registerType(TypeReference.of(vendor.hibernateDialect()),
                        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
        hints.reflection().registerType(TypeReference.of(vendor.jdbcDialect()), type -> type.withField("INSTANCE"));
    }
}
