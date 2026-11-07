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
package io.americanexpress.synapse.data.vendor;

import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.core.Incubating;

/**
 * A database a store can use. A vendor module (such as synapse-data-postgres) defines one as a bean. Synapse picks a
 * store's vendor from its JDBC URL and uses the vendor's dialects, so it never asks the database at startup.
 */
@Incubating
public interface DataStoreVendor {

    /**
     * Returns the vendor's name.
     *
     * @return the name, such as {@code postgres}
     */
    String name();

    /**
     * Returns whether this vendor serves the given URL.
     *
     * @param jdbcUrl a store's JDBC URL
     * @return {@code true} when this vendor serves it
     */
    boolean supports(String jdbcUrl);

    /**
     * Returns the database version Synapse assumes when the store sets no {@code database-version}.
     *
     * @return the version, such as {@code 15}
     */
    String defaultDatabaseVersion();

    /**
     * Returns the database product name Hibernate resolves the dialect from, as the JDBC driver reports it (such as
     * {@code PostgreSQL}). Hibernate then chooses the dialect without a connection. Without a name, Synapse sets
     * {@link #hibernateDialect()} instead.
     *
     * @return the product name, or {@code null} when Hibernate doesn't know the database by name
     */
    default @Nullable String databaseProductName() {
        return null;
    }

    /**
     * Returns the class name of the Hibernate dialect, used when the vendor has no {@link #databaseProductName()}.
     *
     * @return the dialect class name
     */
    String hibernateDialect();

    /**
     * Returns the class name of the Spring Data JDBC dialect. The class has a public static {@code INSTANCE} field.
     *
     * @return the dialect class name
     */
    String jdbcDialect();

    /**
     * Returns the driver properties a store may not set in {@code synapse.data.stores.<name>.hikari.data-source-properties}:
     * those that would make the driver connect somewhere else than the store's {@code url} says, as someone else, in
     * another schema, or that hold a secret. Synapse matches them in any case, and fails startup naming the key, never
     * its value. Synapse itself always blocks {@code user}, {@code username}, {@code password} and {@code url}.
     *
     * @return the property names
     */
    default Set<String> blockedDriverProperties() {
        return Set.of();
    }

    /**
     * Checks a store's URL beyond {@link #supports}, such as that it names a host and a database. Synapse fails startup
     * with {@code The store '<name>' has a url that <problem>.}, so the problem must never quote the URL.
     *
     * @param jdbcUrl a URL this vendor supports
     * @return what is wrong with it, such as {@code names no database}, or {@code null} when nothing is
     */
    default @Nullable String urlProblem(String jdbcUrl) {
        return null;
    }

    /**
     * Returns the form of a URL this vendor accepts, which the failure for a {@link #urlProblem} shows.
     *
     * @return the form, such as {@code jdbc:postgresql://<host>[:<port>]/<database>}, or {@code null}
     */
    default @Nullable String urlForm() {
        return null;
    }

    /**
     * Returns the class-path resources the driver reads for every connection, behind the store's settings, such as
     * PostgreSQL's {@code org/postgresql/driverconfig.properties}. Startup fails when one is on the class path; the
     * failure names its location, never its content.
     *
     * @return the resource names
     */
    default List<String> forbiddenResources() {
        return List.of();
    }
}
