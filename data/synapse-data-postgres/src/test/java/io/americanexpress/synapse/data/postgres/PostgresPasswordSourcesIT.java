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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.NestedExceptionUtils;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.americanexpress.synapse.test.data.SynapseContainers;

import static io.americanexpress.synapse.data.postgres.PostgresServices.arguments;
import static io.americanexpress.synapse.data.postgres.PostgresServices.service;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * I1 (review probes A1, A2), on PostgreSQL (Docker): a store whose password is empty connects with an empty password.
 * PgJDBC never takes the password from a {@code .pgpass} file instead, which it reads whenever it is given no password
 * (here the file named by {@code -Dorg.postgresql.pgpassfile}, which holds the container's real password).
 */
class PostgresPasswordSourcesIT {

    static final PostgreSQLContainer BOOKS = SynapseContainers.postgres("books");

    static final PostgreSQLContainer LEGACY = SynapseContainers.postgres("legacy");

    private static final String PGPASSFILE = "org.postgresql.pgpassfile";

    @Test
    void anEmptyPasswordNeverReadsThePgpassFile(@TempDir Path directory) throws IOException {
        Path pgpass = directory.resolve("pgpass");
        Files
                .writeString(pgpass, "*:*:*:" + BOOKS.getUsername() + ":" + BOOKS.getPassword() + "\n",
                        StandardCharsets.UTF_8);
        String previous = System.getProperty(PGPASSFILE);
        System.setProperty(PGPASSFILE, pgpass.toString());
        try {
            Throwable failure = catchThrowable(
                    () -> service().run(arguments(BOOKS, BOOKS.getJdbcUrl(), "", LEGACY)).close());

            assertThat(failure).as("the store must not start with the .pgpass password").isNotNull();
            assertThat(NestedExceptionUtils.getMostSpecificCause(failure))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("password is an empty string");
        } finally {
            if (previous == null) {
                System.clearProperty(PGPASSFILE);
            } else {
                System.setProperty(PGPASSFILE, previous);
            }
        }
    }
}
