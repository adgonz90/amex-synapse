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
 * a commit as an unknown outcome instead, so a commit that lost its connection is never a retryable
 * {@code UNAVAILABLE}. Both look at each exception of the chain, and the connection was lost when any one of them
 * says so:
 * <ul>
 * <li>The JDBC connection exception types, {@code SQLNonTransientConnectionException} and
 * {@code SQLTransientConnectionException}, whatever their state (H2 gives its own, {@code 90067}).</li>
 * <li>An {@code SQLException} of the SQL standard's class {@code 08} (connection exception), or of PostgreSQL's
 * {@code 57P01} (the server ended the session, as {@code pg_terminate_backend} or a fast shutdown does),
 * {@code 57P02} (crash shutdown), {@code 57P03} (the server can't accept connections yet) or {@code 57P05} (idle
 * session timeout). Any other state is not a lost connection, even under one of Spring's types below: Spring
 * translates classes {@code 53}, {@code 54}, {@code 57} and {@code 58} to a {@code DataAccessResourceFailureException}
 * too, although the database answered.</li>
 * <li>Spring's {@code DataAccessResourceFailureException}, {@code TransientDataAccessResourceException} and
 * {@code CannotCreateTransactionException} are decided by the first {@code SQLException} below them that has a state
 * or one of those types, and are a lost connection only without one (a pool timeout, an I/O error).</li>
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
    private static final Set<String> SESSION_ENDED = Set.of("57P01", "57P02", "57P03", "57P05");

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
     * Returns whether an exception itself says that the connection was lost. An {@code SQLException} decides by its
     * type or its state; one of Spring's resource failures, by the {@code SQLException} below it that has a state or
     * is a JDBC connection exception type, and without one by its own type.
     *
     * @param exception the exception
     * @return whether it is a lost connection
     */
    public static boolean isLostConnection(Throwable exception) {
        if (exception instanceof SQLException sql) {
            return driverSaysLost(sql);
        }
        if (!isSpringResourceFailure(exception)) {
            return false;
        }
        SQLException deciding = deciding(exception);
        return deciding == null || driverSaysLost(deciding);
    }

    /**
     * Returns the SQLState that decides what an exception itself says: its own, for an {@code SQLException}; for one of
     * Spring's resource failures, the state of the {@code SQLException} below it that decides (see
     * {@link #isLostConnection(Throwable)}), because Spring translates states of other classes than {@code 08} to its
     * resource failures too.
     *
     * @param exception the exception
     * @return the state, or {@code null} when it has none, or isn't one of these exceptions
     */
    public static @Nullable String state(Throwable exception) {
        SQLException deciding = exception instanceof SQLException sql
                ? sql
                : isSpringResourceFailure(exception) ? deciding(exception) : null;
        return deciding == null ? null : deciding.getSQLState();
    }

    /**
     * Returns whether a failure, or one of its first {@value CauseChain#MAX_DEPTH} causes, says that the connection was
     * lost, each by {@link #isLostConnection(Throwable)}: the rule the translator applies to every exception of the
     * chain, so a commit the translator would report as a lost connection is never a retryable {@code UNAVAILABLE}.
     * The translator's code can still be another one, without making a lost connection retryable: a class-{@code 53}
     * failure alone is {@code UNAVAILABLE} without being a lost connection (the database answered, so nothing was
     * committed), and a lost connection below an exception the translator maps first (a class-{@code 53} failure, a
     * timeout, a duplicate key) gets that exception's code from the translator and an unknown outcome here.
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

    /** A JDBC connection exception type is the driver's own word for it, whatever its state (H2's is 90067). */
    private static boolean driverSaysLost(SQLException sql) {
        return sql instanceof SQLNonTransientConnectionException || sql instanceof SQLTransientConnectionException
                || isLostConnection(sql.getSQLState());
    }

    private static boolean isSpringResourceFailure(Throwable exception) {
        return exception instanceof DataAccessResourceFailureException
                || exception instanceof TransientDataAccessResourceException
                || exception instanceof CannotCreateTransactionException;
    }

    /** The first {@code SQLException} in the chain that has a state or is a JDBC connection exception type. */
    private static @Nullable SQLException deciding(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; depth <= CauseChain.MAX_DEPTH && current != null; depth++) {
            if (current instanceof SQLException sql && (sql.getSQLState() != null
                    || sql instanceof SQLNonTransientConnectionException
                    || sql instanceof SQLTransientConnectionException)) {
                return sql;
            }
            current = current.getCause();
        }
        return null;
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
