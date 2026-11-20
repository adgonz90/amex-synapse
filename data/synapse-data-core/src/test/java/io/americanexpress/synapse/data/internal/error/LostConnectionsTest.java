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
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.TransactionSystemException;

import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A chain is a lost connection when any of its exceptions says so: a JDBC connection exception type, or an
 * {@code SQLException} whose own state is a lost connection. One of Spring's resource failure types is decided by the
 * first {@code SQLException} below it that has a state (or is a connection type), and by its type only without one.
 * Spring translates SQLState classes 53, 54, 57 and 58 to a {@code DataAccessResourceFailureException} as well as
 * class 08, so the type alone would count a full disk as a lost connection.
 */
class LostConnectionsTest {

    @ParameterizedTest
    @ValueSource(strings = {"53100", "53300", "54001", "57000", "57P04"})
    void resourceFailuresWithAStateAreNotLost(String state) {
        DataAccessResourceFailureException failure = resourceFailure(state);

        assertThat(LostConnections.isLostConnection(failure)).isFalse();
        assertThat(LostConnections.causedBy(failure)).isFalse();
        assertThat(LostConnections.causedBy(new TransactionSystemException("Could not commit", failure))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"08006", "08001", "57P01", "57P05"})
    void connectionStatesAreLost(String state) {
        DataAccessResourceFailureException failure = resourceFailure(state);

        assertThat(LostConnections.isLostConnection(failure)).isTrue();
        assertThat(LostConnections.causedBy(failure)).isTrue();
        assertThat(LostConnections.causedBy(new TransactionSystemException("Could not commit", failure))).isTrue();
        assertThat(LostConnections.isLostConnection(state)).isTrue();
    }

    /** A pool timeout, or an I/O error, without a state: the exception type decides. */
    @Test
    void aResourceFailureWithoutAStateIsLost() {
        DataAccessResourceFailureException timeout = new DataAccessResourceFailureException(
                "Connection is not available");

        assertThat(LostConnections.isLostConnection(timeout)).isTrue();
        assertThat(LostConnections.causedBy(timeout)).isTrue();
        assertThat(LostConnections
                .causedBy(new DataAccessResourceFailureException("I/O error",
                        new SQLTransientConnectionException("Connection is not available, request timed out"))))
                .isTrue();
    }

    /**
     * A JDBC connection exception type is the driver's own word, whatever its state: H2 reports a refused connection as
     * a {@code SQLNonTransientConnectionException} of its own state 90067, under Spring's resource failure.
     */
    @Test
    void aJdbcConnectionExceptionIsLostWhateverItsState() {
        SQLNonTransientConnectionException broken = new SQLNonTransientConnectionException("Connection is broken",
                "90067");
        CannotGetJdbcConnectionException refused = new CannotGetJdbcConnectionException(
                "Failed to obtain JDBC Connection",
                broken);

        assertThat(LostConnections.isLostConnection(broken)).isTrue();
        assertThat(LostConnections.isLostConnection(refused)).isTrue();
        assertThat(LostConnections.causedBy(refused)).isTrue();
        assertThat(LostConnections.state(refused)).isEqualTo("90067");
    }

    /** The first SQLException with a state decides, below one without. */
    @Test
    void theFirstStateInTheChainDecides() {
        assertThat(LostConnections
                .causedBy(new DataAccessResourceFailureException("first",
                        new SQLException("no state", (String) null, new SQLException("full", "53100")))))
                .isFalse();
        assertThat(LostConnections
                .state(new RuntimeException("not a resource failure",
                        new SQLException("full", "53100"))))
                .isNull();
        assertThat(LostConnections
                .causedBy(new DataAccessResourceFailureException("first",
                        new SQLException("no state", (String) null, new SQLException("reset", "08006")))))
                .isTrue();
    }

    /**
     * A stated failure that isn't about the connection (HY000) above one that is: the connection was still lost, as the
     * translator, which looks at every exception in the chain, says too.
     */
    @Test
    void aLostConnectionBelowAnotherStatedFailureIsLost() {
        assertThat(LostConnections
                .causedBy(new UncategorizedSQLException("commit", null,
                        new SQLException("general error", "HY000", new SQLException("reset", "08006")))))
                .isTrue();
        assertThat(LostConnections
                .causedBy(new UncategorizedSQLException("commit", null,
                        new SQLException("general error", "HY000",
                                new SQLNonTransientConnectionException("broken", "90067")))))
                .isTrue();
    }

    static Stream<Arguments> chains() {
        return Stream
                .of(Arguments
                        .of("HY000 above 08006",
                                new UncategorizedSQLException("commit", null,
                                        new SQLException("general error", "HY000",
                                                new SQLException("reset", "08006")))),
                        Arguments
                                .of("HY000 above a connection type",
                                        new RuntimeException("wrapper", new SQLException("general error", "HY000",
                                                new SQLNonTransientConnectionException("broken", "90067")))),
                        Arguments
                                .of("a resource failure without a state",
                                        new TransactionSystemException("commit",
                                                new DataAccessResourceFailureException("I/O"))),
                        Arguments
                                .of("a stateless SQLException above 08006", new DataAccessResourceFailureException("x",
                                        new SQLException("no state", (String) null,
                                                new SQLException("reset", "08006")))),
                        Arguments.of("54001 under a resource failure", resourceFailure("54001")),
                        Arguments.of("57P04 under a resource failure", resourceFailure("57P04")),
                        Arguments
                                .of("Hikari's closed connection",
                                        new TransactionSystemException("commit",
                                                new SQLException("Connection is closed"))),
                        Arguments
                                .of("a syntax error", new RuntimeException("wrapper", new SQLException("x", "42601"))));
    }

    /**
     * The commit check and the translator apply one rule, so for these chains a lost connection ({@code UNAVAILABLE})
     * is what the transaction managers report as an unknown commit outcome. The chains leave out the two cases where
     * the answers differ, neither of which makes a lost connection retryable: a class-53 failure alone is
     * {@code UNAVAILABLE} without being a lost connection (the database answered), and a lost connection below an
     * exception the translator maps first is an unknown outcome to the commit check and that exception's code to the
     * translator.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("chains")
    void theCommitCheckAndTheTranslatorAgree(String chain, Throwable failure) {
        SynapseException translated = new DataExceptionTranslator().translate(failure);
        boolean unavailable = translated != null && translated.code().equals(SynapseErrors.UNAVAILABLE.code());

        assertThat(LostConnections.causedBy(failure)).as(chain).isEqualTo(unavailable);
    }

    private static DataAccessResourceFailureException resourceFailure(String state) {
        return new DataAccessResourceFailureException("x", new SQLException("x", state));
    }
}
