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

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.support.SQLStateSQLExceptionTranslator;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.ExceptionTranslator;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.data.CommitOutcomeUnknownException;
import io.americanexpress.synapse.data.DataErrors;

import static io.americanexpress.synapse.test.assertj.SynapseAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

class DataExceptionTranslatorTest {

    private static final String SQL = "INSERT INTO book (isbn) VALUES ('978-0-00-000000-1')";

    private final DataExceptionTranslator translator = new DataExceptionTranslator();

    static Stream<Arguments> springDataAccessExceptions() {
        SQLException driver = new SQLException("Unique index BOOK_ISBN violated: '978-0-00-000000-1'", "23505");
        return Stream
                .of(Arguments
                        .of(new OptimisticLockingFailureException("Row was updated by another transaction"),
                                SynapseErrors.STALE_VERSION),
                        Arguments.of(new DuplicateKeyException(SQL, driver), SynapseErrors.ALREADY_EXISTS),
                        Arguments.of(new QueryTimeoutException(SQL), SynapseErrors.DEADLINE_EXCEEDED),
                        Arguments
                                .of(new CannotGetJdbcConnectionException("jdbc:h2:tcp://db/books refused"),
                                        SynapseErrors.UNAVAILABLE),
                        Arguments
                                .of(new DataAccessResourceFailureException("connection reset"),
                                        SynapseErrors.UNAVAILABLE),
                        Arguments
                                .of(new TransientDataAccessResourceException("resource busy"),
                                        SynapseErrors.UNAVAILABLE),
                        Arguments
                                .of(new CannotCreateTransactionException("Could not open JDBC Connection"),
                                        SynapseErrors.UNAVAILABLE));
    }

    @ParameterizedTest
    @MethodSource("springDataAccessExceptions")
    void mapsSpringDataAccessExceptions(RuntimeException thrown, SynapseErrors expected) {
        assertThat(translated(thrown)).hasCode(expected).hasCategory(expected.category());
    }

    @ParameterizedTest
    @MethodSource("springDataAccessExceptions")
    void theMessageIsTheTemplateOnly(RuntimeException thrown, SynapseErrors expected) {
        SynapseException translated = translated(thrown);

        assertThat(translated.getMessage()).isEqualTo(expected.messageTemplate());
        assertThat(translated.args()).isEmpty();
    }

    @Test
    void translatesThroughTheCauseChain() {
        DuplicateKeyException duplicate = new DuplicateKeyException(SQL);
        TransactionSystemException atCommit = new TransactionSystemException("Could not commit", duplicate);

        SynapseException translated = translated(atCommit);

        assertThat(translated).hasCode(SynapseErrors.ALREADY_EXISTS);
        assertThat(translated.getCause()).isSameAs(atCommit);
    }

    @Test
    void anOptimisticLockWrappedAtCommitIsStale() {
        TransactionSystemException atCommit = new TransactionSystemException("Could not commit",
                new OptimisticLockingFailureException("Row was updated by another transaction"));

        assertThat(translated(atCommit)).hasCode(SynapseErrors.STALE_VERSION);
    }

    @Test
    void theOutermostMatchWins() {
        CannotCreateTransactionException begin = new CannotCreateTransactionException("Could not open JDBC Connection",
                new DuplicateKeyException(SQL));

        assertThat(translated(begin)).hasCode(SynapseErrors.UNAVAILABLE);
    }

    /**
     * Hikari's own exception for a call on a connection it has closed. Hikari closes its proxy after a lost connection,
     * but also after other states it treats as fatal (PostgreSQL's 0A000, for example) and after use-after-close, so on
     * its own it says nothing about the connection.
     */
    @Test
    void aCallOnAConnectionThePoolClosedIsNotALostConnection() throws SQLException {
        SQLException closed = closedConnectionFailure();

        assertThat(closed.getSQLState()).isNull();
        assertThat(closed.getMessage()).isEqualTo("Connection is closed");
        assertThat(translation(new TransactionSystemException("JDBC rollback failed", closed))).isEmpty();
        assertThat(translation(new RuntimeException("Unable to rollback against JDBC Connection", closed))).isEmpty();
    }

    /**
     * The statement fails on a lost connection, then the rollback fails on the connection the pool closed: Spring keeps
     * the statement's failure as the rollback failure's application exception, not as its cause.
     */
    @Test
    void aLostConnectionHiddenByItsRollbackIsUnavailable() throws SQLException {
        TransactionSystemException rollback = rollbackFailure(closedConnectionFailure(),
                new DataAccessResourceFailureException("FATAL: terminating connection due to administrator command"));

        SynapseException translated = translated(rollback);

        assertThat(translated).hasCode(SynapseErrors.UNAVAILABLE);
        assertThat(translated.getMessage()).isEqualTo(SynapseErrors.UNAVAILABLE.messageTemplate());
        assertThat(translated.getCause()).isSameAs(rollback);
    }

    /**
     * A deterministic failure that makes Hikari close the connection (0A000, feature_not_supported) is not a lost
     * connection: its rollback then fails on the closed proxy, and the failure stays INTERNAL.
     */
    @Test
    void aDeterministicFailureHiddenByItsRollbackIsLeftToThePipeline() throws SQLException {
        UncategorizedSQLException unsupported = new UncategorizedSQLException("query", SQL,
                new SQLException("FOR UPDATE is not allowed with aggregate functions", "0A000"));

        assertThat(translation(rollbackFailure(closedConnectionFailure(), unsupported))).isEmpty();
        assertThat(translation(rollbackFailure(new SQLException("This connection has been closed.", "08003"),
                unsupported))).isEmpty();
    }

    /** A driver's connection exception (SQLState class 08) or ended session that no Spring translator saw. */
    @Test
    void aConnectionExceptionIsUnavailable() {
        assertThat(translated(new TransactionSystemException("JDBC rollback failed",
                new SQLException("This connection has been closed.", "08003")))).hasCode(SynapseErrors.UNAVAILABLE);
        for (String ended : new String[]{"57P01", "57P02", "57P03"}) {
            assertThat(translated(new RuntimeException("wrapper", new SQLException("FATAL", ended))))
                    .as(ended)
                    .hasCode(SynapseErrors.UNAVAILABLE);
        }
        assertThat(translation(new SQLException("syntax error", "42601"))).isEmpty();
        assertThat(translation(new SQLException("not supported", "0A000"))).isEmpty();
    }

    /** A rollback failure that isn't about the connection doesn't hide the lost connection that caused it. */
    @Test
    void aLostConnectionIsUnavailableWhateverItsRollbackFailure() {
        TransactionSystemException rollback = rollbackFailure(new SQLException("internal error", "XX000"),
                new DataAccessResourceFailureException("I/O error"));

        assertThat(translated(rollback)).hasCode(SynapseErrors.UNAVAILABLE);
    }

    /**
     * The failure the rollback replaced decides alone, whatever made the rollback fail: it keeps its own code, and
     * without one it is left to the pipeline.
     */
    @Test
    void theReplacedFailureDecides() throws SQLException {
        assertThat(translated(rollbackFailure(closedConnectionFailure(), new DuplicateKeyException(SQL))))
                .hasCode(SynapseErrors.ALREADY_EXISTS);
        assertThat(translated(rollbackFailure(new SQLException("internal error", "XX000"),
                new DuplicateKeyException(SQL)))).hasCode(SynapseErrors.ALREADY_EXISTS);
        assertThat(translation(rollbackFailure(closedConnectionFailure(), new IllegalStateException("not data"))))
                .isEmpty();
    }

    /**
     * A commit that lost its connection, as a store's transaction manager reports it, has an unknown outcome: not the
     * retryable {@code UNAVAILABLE} its cause would give, because the commit may have been applied.
     */
    @Test
    void aCommitThatLostItsConnectionHasAnUnknownOutcome() {
        CommitOutcomeUnknownException commit = new CommitOutcomeUnknownException(
                new DataAccessResourceFailureException(
                        "JDBC commit; An I/O error occurred while sending to the backend."));

        SynapseException translated = translated(new RuntimeException("wrapper", commit));

        assertThat(translated).hasCode(DataErrors.COMMIT_OUTCOME_UNKNOWN).hasCategory(ErrorCategory.INTERNAL);
        assertThat(translated.getMessage()).isEqualTo(DataErrors.COMMIT_OUTCOME_UNKNOWN.messageTemplate());
        assertThat(translated.args()).isEmpty();
    }

    /**
     * Spring attaches an application exception to a failed commit too, after a failure the transaction doesn't roll
     * back for. The unknown outcome still wins: the commit may have been applied.
     */
    @Test
    void anUnknownCommitOutcomeWinsOverTheFailureBeforeIt() {
        CommitOutcomeUnknownException commit = new CommitOutcomeUnknownException(
                new DataAccessResourceFailureException("JDBC commit; I/O error"));
        commit.initApplicationException(new DuplicateKeyException(SQL));

        assertThat(translated(commit)).hasCode(DataErrors.COMMIT_OUTCOME_UNKNOWN);
    }

    /** SQLState 08007 is the SQL standard's transaction_resolution_unknown: the commit's outcome is unknown. */
    @Test
    void aTransactionResolutionUnknownIsCommitOutcomeUnknown() {
        assertThat(translated(new RuntimeException("wrapper",
                new SQLException("transaction resolution unknown", "08007"))))
                .hasCode(DataErrors.COMMIT_OUTCOME_UNKNOWN);
    }

    /**
     * Spring translates every class-08 state, 08007 included, to a {@code DataAccessResourceFailureException}, which
     * would be {@code UNAVAILABLE}: the 08007 below it still wins.
     */
    @Test
    void aTransactionResolutionUnknownWinsThroughSpringsTranslation() {
        DataAccessException spring = Objects
                .requireNonNull(new SQLStateSQLExceptionTranslator()
                        .translate("commit", null, new SQLException("transaction resolution unknown", "08007")));

        assertThat(spring).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(translated(spring)).hasCode(DataErrors.COMMIT_OUTCOME_UNKNOWN);
    }

    /** The JDBC connection exception types are lost connections whatever their state, as Spring translates them. */
    @Test
    void connectionExceptionTypesAreUnavailable() {
        assertThat(translated(new SQLNonTransientConnectionException("The connection was reset")))
                .hasCode(SynapseErrors.UNAVAILABLE);
        assertThat(translated(new SQLTransientConnectionException("Connection is not available")))
                .hasCode(SynapseErrors.UNAVAILABLE);
    }

    /**
     * The data translator runs before an application's translators that declare no order, and an unknown commit
     * outcome is the first thing it looks for.
     */
    @Test
    void theDataTranslatorIsOrdered() {
        ExceptionTranslator application = exception -> new SynapseException(SynapseErrors.INTERNAL, exception);
        List<ExceptionTranslator> translators = new ArrayList<>(List.of(application, translator));

        AnnotationAwareOrderComparator.sort(translators);

        assertThat(translators).containsExactly(translator, application);
    }

    /**
     * An operation's own Synapse failure, such as a domain rejection, that the rollback replaced keeps its code and
     * arguments; the rollback's failure is its cause. One with violations is the operation's own exception.
     */
    @Test
    void aSynapseFailureHiddenByItsRollbackKeepsItsCode() throws SQLException {
        SynapseException rejected = new SynapseException(SynapseErrors.CONFLICT, "order", 42);
        TransactionSystemException rollback = rollbackFailure(closedConnectionFailure(),
                new RuntimeException("wrapper", rejected));

        SynapseException translated = translated(rollback);

        assertThat(translated).hasCode(SynapseErrors.CONFLICT);
        assertThat(translated.args()).containsExactly("order", 42);
        assertThat(translated.getCause()).isSameAs(rollback);
        SynapseException invalid = SynapseException
                .ofViolations(SynapseErrors.VALIDATION_FAILED, List.of(new Violation("amount", "must be positive")));
        assertThat(translated(rollbackFailure(closedConnectionFailure(), invalid))).isSameAs(invalid);
    }

    /** Outside a replaced failure, a wrapped Synapse failure is left to the pipeline, as before. */
    @Test
    void aWrappedSynapseFailureIsLeftToThePipeline() {
        assertThat(translation(new RuntimeException("wrapper", new SynapseException(SynapseErrors.CONFLICT))))
                .isEmpty();
    }

    /** A rollback that fails with no failure to replace is left to the pipeline. */
    @Test
    void aGenuineRollbackFailureIsLeftToThePipeline() {
        assertThat(translation(new TransactionSystemException("JDBC rollback failed",
                new SQLException("internal error", "XX000")))).isEmpty();
        assertThat(translation(new TransactionSystemException("JDBC rollback failed",
                new SQLException("Connection is closed", "XX000")))).isEmpty();
    }

    @Test
    void otherDataAccessFailuresAreLeftToThePipeline() {
        assertThat(translation(new BadSqlGrammarException("query", SQL, new SQLException("syntax")))).isEmpty();
        assertThat(translation(new DataIntegrityViolationException("not null"))).isEmpty();
        assertThat(translation(new IllegalStateException("not a data failure"))).isEmpty();
    }

    @Test
    void keepsTheCause() {
        DuplicateKeyException thrown = new DuplicateKeyException(SQL);

        assertThat(translated(thrown).getCause()).isSameAs(thrown);
    }

    @Test
    void translatesAtDepthTen() {
        assertThat(translated(wrapped(new DuplicateKeyException(SQL), 10))).hasCode(SynapseErrors.ALREADY_EXISTS);
    }

    @Test
    void stopsAtDepthTen() {
        assertThat(translation(wrapped(new DuplicateKeyException(SQL), 11))).isEmpty();
    }

    @Test
    void aCauseCycleEnds() {
        IllegalStateException first = new IllegalStateException("first");
        IllegalStateException second = new IllegalStateException("second", first);
        first.initCause(second);

        assertThat(translation(first)).isEmpty();
    }

    private Optional<SynapseException> translation(Throwable thrown) {
        return Optional.ofNullable(translator.translate(thrown));
    }

    private SynapseException translated(Throwable thrown) {
        return translation(thrown)
                .orElseThrow(() -> new AssertionError("Expected a translation of " + thrown.getClass().getName()));
    }

    /** A rollback that failed, after the application's failure, as Spring's transaction interceptor throws it. */
    private static TransactionSystemException rollbackFailure(SQLException rollback, Throwable application) {
        TransactionSystemException failure = new TransactionSystemException("JDBC rollback failed", rollback);
        failure.initApplicationException(application);
        return failure;
    }

    /** What Hikari throws for a rollback on a pooled connection that it has closed. */
    private static SQLException closedConnectionFailure() throws SQLException {
        try (HikariDataSource pool = new HikariDataSource()) {
            pool.setJdbcUrl("jdbc:h2:mem:closed-connection");
            Connection connection = pool.getConnection();
            connection.setAutoCommit(false);
            connection.close();
            try {
                connection.rollback();
            } catch (SQLException closed) {
                return closed;
            }
        }
        throw new AssertionError("A rollback on a closed pooled connection succeeded");
    }

    /** Wraps the exception in {@code depth} runtime exceptions, so it is that many causes deep. */
    private static RuntimeException wrapped(RuntimeException exception, int depth) {
        RuntimeException current = exception;
        for (int i = 0; i < depth; i++) {
            current = new RuntimeException("wrapper " + i, current);
        }
        return current;
    }
}
