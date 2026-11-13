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
package io.americanexpress.synapse.data.internal.error;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.CannotCreateTransactionException;

import io.americanexpress.synapse.data.CommitOutcomeUnknownException;

/**
 * The one rule for what says that the connection to the database was lost (or can't be had), shared by the data
 * translator, which reports it as {@code UNAVAILABLE}, and by the stores' transaction managers, which report it during
 * a commit as an unknown outcome instead, so a commit-phase failure is never a retryable {@code UNAVAILABLE}.
 * <ul>
 * <li>Spring's {@code DataAccessResourceFailureException}, {@code TransientDataAccessResourceException} and
 * {@code CannotCreateTransactionException}.</li>
 * <li>The JDBC connection exception types, {@code SQLNonTransientConnectionException} and
 * {@code SQLTransientConnectionException}, whatever their state.</li>
 * <li>An {@code SQLException} of the SQL standard's class {@code 08} (connection exception), or of PostgreSQL's
 * {@code 57P01} (the server ended the session, as {@code pg_terminate_backend} or a fast shutdown does), {@code 57P02}
 * (crash shutdown) or {@code 57P03} (the server can't accept connections yet).</li>
 * </ul>
 * Hikari closes a connection on more states than these, PostgreSQL's {@code 0A000} (feature_not_supported) among
 * them, and afterwards answers every call with {@code SQLException: Connection is closed}, without a state. That
 * exception therefore never counts as a lost connection: only the failure that made Hikari close the connection says
 * whether it was lost.
 * <p>
 * {@code 08007} (transaction_resolution_unknown) is the standard's own unknown outcome. Spring translates it, like every
 * class-08 state, to a {@code DataAccessResourceFailureException}, so {@link #outcomeUnknown} looks for it, or for a
 * {@link CommitOutcomeUnknownException}, anywhere in the chain.
 */
public final class LostConnections {

    /** SQLState class of a connection exception (SQL standard). */
    private static final String CONNECTION_EXCEPTION_CLASS = "08";

    /** The SQL standard's state for a connection lost while the outcome of its transaction is unknown. */
    private static final String TRANSACTION_RESOLUTION_UNKNOWN = "08007";

    /** PostgreSQL's states for a session the server ended or can't open yet. */
    private static final Set<String> SESSION_ENDED = Set.of("57P01", "57P02", "57P03");

    private LostConnections() {
    }

    /**
     * Returns whether a SQLState says that the connection was lost.
     *
     * @param sqlState the state, or {@code null} when the exception has none
     * @return whether the connection was lost
     */
    public static boolean isLostConnection(@Nullable String sqlState) {
        if (sqlState == null) {
            return false;
        }
        return sqlState.startsWith(CONNECTION_EXCEPTION_CLASS) || SESSION_ENDED.contains(sqlState);
    }

    /**
     * Returns whether an exception itself, not its causes, says that the connection was lost.
     *
     * @param exception the exception
     * @return whether it is a lost connection
     */
    public static boolean isLostConnection(Throwable exception) {
        return exception instanceof DataAccessResourceFailureException
                || exception instanceof TransientDataAccessResourceException
                || exception instanceof CannotCreateTransactionException
                || exception instanceof SQLNonTransientConnectionException
                || exception instanceof SQLTransientConnectionException
                || (exception instanceof SQLException sql && isLostConnection(sql.getSQLState()));
    }

    /**
     * Returns whether a failure, or one of its first {@value CauseChain#MAX_DEPTH} causes, says that the connection was
     * lost.
     *
     * @param failure the failure
     * @return whether the failure is a lost connection
     */
    public static boolean causedBy(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; depth <= CauseChain.MAX_DEPTH && current != null; depth++) {
            if (isLostConnection(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Returns whether a failure, or one of its first {@value CauseChain#MAX_DEPTH} causes, says that the outcome of its
     * transaction is unknown: a {@link CommitOutcomeUnknownException}, or an {@code SQLException} of state
     * {@code 08007}, however Spring wrapped it.
     *
     * @param failure the failure
     * @return whether the outcome is unknown
     */
    public static boolean outcomeUnknown(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; depth <= CauseChain.MAX_DEPTH && current != null; depth++) {
            if (current instanceof CommitOutcomeUnknownException || (current instanceof SQLException sql
                    && TRANSACTION_RESOLUTION_UNKNOWN.equals(sql.getSQLState()))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
