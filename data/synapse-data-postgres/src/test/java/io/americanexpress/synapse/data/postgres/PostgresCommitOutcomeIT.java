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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.UUID;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.data.CommitOutcomeUnknownException;
import io.americanexpress.synapse.data.DataErrors;
import io.americanexpress.synapse.data.internal.error.DataExceptionTranslator;
import io.americanexpress.synapse.data.jdbc.SynapseJdbcTransactionManager;
import io.americanexpress.synapse.test.data.SynapseContainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A commit whose reply is lost (Docker): PostgreSQL applies the COMMIT, but the connection breaks before the client
 * reads the answer. A JDBC store's transaction manager reports {@code COMMIT_OUTCOME_UNKNOWN}, not a retryable
 * {@code UNAVAILABLE}, and the row is indeed there: a retry would have written it twice.
 */
class PostgresCommitOutcomeIT {

    private static final PostgreSQLContainer DATABASE = SynapseContainers.postgres("commit-outcome");

    @Test
    void aCommitWhoseReplyIsLostHasAnUnknownOutcomeThoughItWasApplied() throws Exception {
        execute("CREATE TABLE IF NOT EXISTS committed_item (name VARCHAR(255) PRIMARY KEY)");
        String name = "committed-" + UUID.randomUUID();
        Throwable thrown;
        try (ReplyCuttingProxy proxy = new ReplyCuttingProxy(DATABASE.getHost(), DATABASE.getMappedPort(5432));
                HikariDataSource pool = new HikariDataSource()) {
            pool.setJdbcUrl("jdbc:postgresql://localhost:" + proxy.port() + "/" + DATABASE.getDatabaseName());
            pool.setUsername(DATABASE.getUsername());
            pool.setPassword(DATABASE.getPassword());
            pool.setMaximumPoolSize(1);
            JdbcClient jdbc = JdbcClient.create(pool);
            TransactionTemplate template = new TransactionTemplate(new SynapseJdbcTransactionManager(pool));

            thrown = catchThrowable(() -> template.executeWithoutResult(status -> {
                jdbc.sql("INSERT INTO committed_item (name) VALUES (?)").param(name).update();
                proxy.cutReplies();
            }));
        }

        assertThat(thrown).isInstanceOf(CommitOutcomeUnknownException.class);
        SynapseException translated = Objects
                .requireNonNull(new DataExceptionTranslator().translate(Objects.requireNonNull(thrown, "failure")),
                        "translation");
        assertThat(translated.code()).isEqualTo(DataErrors.COMMIT_OUTCOME_UNKNOWN.code());
        assertThat(translated.category()).isEqualTo(ErrorCategory.INTERNAL);
        assertThat(translated.getMessage()).isEqualTo("The outcome of the request is unknown");
        assertThat(count(name)).as("the commit was applied").isOne();
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int count(String name) throws SQLException {
        try (Connection connection = connection();
                PreparedStatement query = connection
                        .prepareStatement("SELECT COUNT(*) FROM committed_item WHERE name = ?")) {
            query.setString(1, name);
            try (ResultSet result = query.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getInt(1);
            }
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
    }
}
