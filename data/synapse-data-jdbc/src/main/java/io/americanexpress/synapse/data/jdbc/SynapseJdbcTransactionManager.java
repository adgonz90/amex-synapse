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
import javax.sql.DataSource;

import org.springframework.jdbc.datasource.JdbcTransactionObjectSupport;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;

import io.americanexpress.synapse.data.CommitOutcomeUnknownException;
import io.americanexpress.synapse.data.internal.error.LostConnections;

/**
 * A JDBC store's transaction manager: Spring's {@link JdbcTransactionManager}, except that a commit that loses its
 * connection is reported as the data module's {@code COMMIT_OUTCOME_UNKNOWN} (category {@code INTERNAL}), not as a
 * retryable {@code UNAVAILABLE}. A lost connection follows the rule the data translator uses everywhere else: any of
 * the failure's causes is a JDBC connection exception type, has a class-08 state or PostgreSQL's 57P01-57P03 or
 * 57P05, or is one of Spring's connection exceptions without another state below it. A commit the database rejected
 * with another state, such as 53100 (disk full), has a known outcome and is reported as before.
 * <p>
 * The commit is the one step whose outcome a lost connection leaves unknown: the database may have applied the COMMIT
 * and lost only its reply. {@code JdbcTransactionManager} reports that failure as a {@code DataAccessException}, then
 * rolls back, and the rollback, on the closed connection, fails in its place. This manager instead throws a
 * {@link CommitOutcomeUnknownException}, a {@code TransactionSystemException}: Spring's own way to complete a
 * transaction with an unknown status. The data translator reports it as {@code COMMIT_OUTCOME_UNKNOWN}. Before it
 * does, it rolls the connection back unless the connection is closed (as Hikari closes one that reported a lost
 * connection), so a pooled connection never goes back with an open transaction. Any other commit failure is reported
 * as before.
 * <p>
 * A rollback that fails is always a {@code TransactionSystemException}, which Spring's transaction interceptor and
 * {@code TransactionTemplate} keep the failure the rollback replaced in, so that failure decides the code.
 * {@code JdbcTransactionManager} reports one whose SQLState Spring translates as that {@code DataAccessException}
 * instead, without the failure it replaced.
 * <p>
 * Synapse builds one for every JDBC store. It is the class to extend when a store class replaces its
 * {@code <prefix>TransactionManager}: a replacement that isn't one loses both, and the data
 * stores' startup report warns about it. A subclass that overrides {@link #doCommit} or {@link #doRollback} calls
 * {@code super}.
 */
public class SynapseJdbcTransactionManager extends JdbcTransactionManager {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the transaction manager.
     *
     * @param dataSource the store's data source
     */
    public SynapseJdbcTransactionManager(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        try {
            super.doCommit(status);
        } catch (RuntimeException ex) {
            if (LostConnections.causedBy(ex)) {
                CommitOutcomeUnknownException unknown = new CommitOutcomeUnknownException(ex);
                rollBackUnlessClosed(status, unknown);
                throw unknown;
            }
            throw ex;
        }
    }

    /**
     * Rolls back as {@code JdbcTransactionManager} does, but a rollback that fails is always a
     * {@code TransactionSystemException}: {@code JdbcTransactionManager} reports one whose SQLState it translates (an I/O
     * error, say) as that {@code DataAccessException}, and Spring's transaction interceptor then throws it without the
     * failure the rollback replaced.
     */
    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        try {
            super.doRollback(status);
        } catch (TransactionException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new TransactionSystemException("JDBC rollback failed", ex);
        }
    }

    /**
     * Rolls the transaction's connection back, unless it is closed: a connection that is still open goes back to the
     * pool, so it must not keep the transaction open. A failed rollback is kept as suppressed by the failure.
     */
    private static void rollBackUnlessClosed(DefaultTransactionStatus status, RuntimeException failure) {
        if (!(status.getTransaction() instanceof JdbcTransactionObjectSupport transaction)
                || !transaction.hasConnectionHolder()) {
            return;
        }
        Connection connection = transaction.getConnectionHolder().getConnection();
        try {
            if (!connection.isClosed()) {
                connection.rollback();
            }
        } catch (SQLException | RuntimeException rollback) {
            failure.addSuppressed(rollback);
        }
    }
}
