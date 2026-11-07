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
package io.americanexpress.synapse.test.data;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared PostgreSQL containers (Docker): one per key per JVM, started on first use, from the PostgreSQL image.
 */
class SynapseContainersIT {

    /**
     * postgres:18.6-alpine, by the digest pulled on 2026-11-07 (M6): a re-pushed tag can't change the image. The
     * server version below confirms it is 18.6.
     */
    private static final String PINNED = "postgres@sha256:"
            + "123b17998ccaf47e53b502e8c6a1e79afd8f579c6f3eec83c563da026f957395";

    @Test
    void sharedPerKey() throws SQLException {
        PostgreSQLContainer shared = SynapseContainers.postgres();
        PostgreSQLContainer legacy = SynapseContainers.postgres("legacy");

        assertThat(SynapseContainers.postgres()).isSameAs(shared);
        assertThat(SynapseContainers.postgres("default")).isSameAs(shared);
        assertThat(SynapseContainers.postgres("legacy")).isSameAs(legacy);
        assertThat(legacy).isNotSameAs(shared);
        assertThat(shared.isRunning()).isTrue();
        assertThat(legacy.isRunning()).isTrue();
        assertThat(legacy.getContainerId()).isNotEqualTo(shared.getContainerId());
        assertThat(SynapseContainers.POSTGRES_IMAGE).isEqualTo(PINNED);
        assertThat(shared.getDockerImageName()).isEqualTo(PINNED);
        assertThat(legacy.getDockerImageName()).isEqualTo(PINNED);
        assertThat(serverVersion(shared)).startsWith("18.6");
        assertThat(serverVersion(legacy)).startsWith("18.6");
    }

    private static String serverVersion(PostgreSQLContainer container) throws SQLException {
        try (Connection connection = DriverManager
                .getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SHOW server_version")) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
