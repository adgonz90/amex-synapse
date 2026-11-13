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
package io.americanexpress.synapse.data.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.util.Objects;
import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.data.CommitOutcomeUnknownException;
import io.americanexpress.synapse.data.internal.error.DataExceptionTranslator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The JDBC store's transaction manager against a connection whose commit fails on demand, as a commit does when the
 * connection is lost in the middle of it.
 */
class SynapseJdbcTransactionManagerTest {

    private final DataSource dataSource = mock(DataSource.class);

    private final Connection connection = mock(Connection.class);

    private final TransactionTemplate template = new TransactionTemplate(
            new SynapseJdbcTransactionManager(dataSource));

    @BeforeEach
    void connections() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
    }

    /**
     * A commit that loses its connection has an unknown outcome: the database may have committed and lost only the
     * reply. Hikari has closed the connection (as it does on a class-08 state), so the transaction manager doesn't
     * roll back, and it reports the commit's own failure as the cause.
     */
    @Test
    void aCommitThatLosesItsConnectionHasAnUnknownOutcome() throws SQLException {
        when(connection.isClosed()).thenReturn(true);
        doThrow(new SQLException("An I/O error occurred while sending to the backend.", "08006"))
                .when(connection)
                .commit();

        Throwable thrown = catchThrowable(() -> template.executeWithoutResult(status -> {
        }));

        assertThat(thrown)
                .isInstanceOf(CommitOutcomeUnknownException.class)
                .hasMessageContaining("whether it committed is unknown")
                .cause()
                .isInstanceOf(DataAccessResourceFailureException.class);
        verify(connection, never()).rollback();
    }

    /** A session the server ended (PostgreSQL's 57P01) while committing is a lost connection too. */
    @Test
    void aCommitWhoseSessionEndedHasAnUnknownOutcome() throws SQLException {
        doThrow(new SQLException("FATAL: terminating connection due to administrator command", "57P01"))
                .when(connection)
                .commit();

        assertThat(catchThrowable(() -> template.executeWithoutResult(status -> {
        }))).isInstanceOf(CommitOutcomeUnknownException.class);
    }

    /** A commit that fails on a connection that wasn't lost is rolled back and reported as before. */
    @Test
    void anotherCommitFailureIsRolledBack() throws SQLException {
        doThrow(new SQLException("could not serialize access", "40001")).when(connection).commit();

        Throwable thrown = catchThrowable(() -> template.executeWithoutResult(status -> {
        }));

        assertThat(thrown).isNotInstanceOf(CommitOutcomeUnknownException.class);
        verify(connection).rollback();
    }

    /**
     * A commit on a connection the pool already closed (Hikari's "Connection is closed", without a state) says nothing
     * about the commit's outcome: the connection was gone before it, so nothing was committed.
     */
    @Test
    void aCommitOnAClosedConnectionIsNotAnUnknownOutcome() throws SQLException {
        doThrow(new SQLException("Connection is closed")).when(connection).commit();

        Throwable thrown = catchThrowable(() -> template.executeWithoutResult(status -> {
        }));

        assertThat(thrown)
                .isInstanceOf(TransactionSystemException.class)
                .isNotInstanceOf(CommitOutcomeUnknownException.class);
    }

    /**
     * A connection exception type without a class-08 state is a lost connection too: the commit-phase check uses the
     * same rule as the translator, so no commit-phase failure is ever a retryable {@code UNAVAILABLE}. Hikari doesn't
     * close a connection for it, so it is still open, and the transaction manager rolls it back: a pooled connection
     * never goes back with an open transaction.
     */
    @Test
    void aCommitThatFailsWithAConnectionExceptionTypeHasAnUnknownOutcome() throws SQLException {
        doThrow(new SQLNonTransientConnectionException("The connection was reset")).when(connection).commit();

        assertThat(catchThrowable(() -> template.executeWithoutResult(status -> {
        }))).isInstanceOf(CommitOutcomeUnknownException.class);
        verify(connection).rollback();
    }

    /**
     * With {@code rollback-on-commit-failure}, Spring rolls back after the commit anyway. The rollback fails on the
     * connection the pool closed and replaces the exception, so the caller gets a plain {@code INTERNAL}, still not
     * retried, without the code.
     */
    @Test
    void rollbackOnCommitFailureReplacesTheUnknownOutcome() throws SQLException {
        SynapseJdbcTransactionManager manager = new SynapseJdbcTransactionManager(dataSource);
        manager.setRollbackOnCommitFailure(true);
        doThrow(new SQLException("An I/O error occurred while sending to the backend.", "08006"))
                .when(connection)
                .commit();
        when(connection.isClosed()).thenReturn(true);
        doThrow(new SQLException("Connection is closed")).when(connection).rollback();

        Throwable thrown = catchThrowable(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
        }));

        assertThat(thrown)
                .isInstanceOf(TransactionSystemException.class)
                .isNotInstanceOf(CommitOutcomeUnknownException.class)
                .hasMessage("JDBC rollback failed");
    }

    /**
     * A rollback that fails with a state Spring translates (an I/O error, 08006, on a connection the pool hadn't closed
     * yet) is a {@code TransactionSystemException}, as one without a state is, so Spring keeps the failure the rollback
     * replaced as its application exception, and that failure decides the code.
     */
    @Test
    void aRollbackThatFailsWithATranslatedStateKeepsTheFailureItReplaced() throws SQLException {
        doThrow(new SQLException("An I/O error occurred while sending to the backend.", "08006"))
                .when(connection)
                .rollback();
        DuplicateKeyException statement = new DuplicateKeyException("duplicate key value violates unique constraint");

        Throwable thrown = catchThrowable(() -> template.executeWithoutResult(status -> {
            throw statement;
        }));

        assertThat(thrown).isInstanceOfSatisfying(TransactionSystemException.class, failure -> {
            assertThat(failure.getApplicationException()).isSameAs(statement);
            assertThat(failure.getCause()).isInstanceOf(DataAccessResourceFailureException.class);
        });
        SynapseException translated = new DataExceptionTranslator().translate(Objects.requireNonNull(thrown));
        assertThat(Objects.requireNonNull(translated).code()).isEqualTo(SynapseErrors.ALREADY_EXISTS.code());
    }
}
