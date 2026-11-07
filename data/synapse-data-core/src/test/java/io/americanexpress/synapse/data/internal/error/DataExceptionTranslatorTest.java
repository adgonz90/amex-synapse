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
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;

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

    /** Wraps the exception in {@code depth} runtime exceptions, so it is that many causes deep. */
    private static RuntimeException wrapped(RuntimeException exception, int depth) {
        RuntimeException current = exception;
        for (int i = 0; i < depth; i++) {
            current = new RuntimeException("wrapper " + i, current);
        }
        return current;
    }
}
