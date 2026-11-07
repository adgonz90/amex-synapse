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
package io.americanexpress.synapse.data.jpa.internal;

import java.sql.SQLException;
import java.util.Optional;
import java.util.stream.Stream;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.RollbackException;

import org.hibernate.PessimisticLockException;
import org.hibernate.QueryTimeoutException;
import org.hibernate.StaleObjectStateException;
import org.hibernate.StaleStateException;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.jpa.JpaSystemException;

import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;

import static io.americanexpress.synapse.test.assertj.SynapseAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

class JpaExceptionTranslatorTest {

    private static final String SQL = "insert into book (isbn,id) values (?,?)";

    private final JpaExceptionTranslator translator = new JpaExceptionTranslator();

    static Stream<Arguments> jpaAndHibernateExceptions() {
        return Stream
                .of(Arguments
                        .of(new OptimisticLockException("Row was updated by another transaction"),
                                SynapseErrors.STALE_VERSION),
                        Arguments
                                .of(new StaleStateException("Batch update returned unexpected row count"),
                                        SynapseErrors.STALE_VERSION),
                        Arguments.of(new StaleObjectStateException("Book", "019b7d40"), SynapseErrors.STALE_VERSION),
                        Arguments.of(constraintViolation(ConstraintKind.UNIQUE), SynapseErrors.ALREADY_EXISTS),
                        Arguments
                                .of(new jakarta.persistence.QueryTimeoutException("Query timed out"),
                                        SynapseErrors.DEADLINE_EXCEEDED),
                        Arguments.of(hibernateQueryTimeout(), SynapseErrors.DEADLINE_EXCEEDED));
    }

    /**
     * A native query through the EntityManager that PostgreSQL cancels (57014): Hibernate's exception, inside the
     * plain {@link PersistenceException} the EntityManager throws (observed on PostgreSQL 18.6 in synapse-data-postgres's
     * {@code PostgresErrorTranslationIT}).
     */
    @Test
    void aQueryTimeoutInsideAPersistenceExceptionIsDeadlineExceeded() {
        PersistenceException thrown = new PersistenceException("JDBC exception executing SQL",
                hibernateQueryTimeout());

        SynapseException translated = translated(thrown);

        assertThat(translated).hasCode(SynapseErrors.DEADLINE_EXCEEDED);
        assertThat(translated.getCause()).isSameAs(thrown);
    }

    /**
     * Lock failures stay with the pipeline (INTERNAL) until the maintainers decide on a retryable code: JPA's
     * {@code LockTimeoutException}, and what Hibernate's {@code PostgreSQLDialect} raises for 55P03, its
     * {@link org.hibernate.exception.LockTimeoutException} (a {@code PessimisticLockException}), alone and inside JPA's.
     */
    @Test
    void lockFailuresAreLeftToThePipeline() {
        org.hibernate.exception.LockTimeoutException hibernate = new org.hibernate.exception.LockTimeoutException(
                "could not obtain lock", new SQLException("canceling statement due to lock timeout", "55P03"),
                "select b1_0.id from book b1_0 where b1_0.id=? for no key update");

        assertThat(hibernate).isInstanceOf(PessimisticLockException.class);
        assertThat(translation(new LockTimeoutException("lock timeout"))).isEmpty();
        assertThat(translation(hibernate)).isEmpty();
        assertThat(translation(new LockTimeoutException("lock timeout", hibernate))).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("jpaAndHibernateExceptions")
    void mapsJpaAndHibernateExceptions(RuntimeException thrown, SynapseErrors expected) {
        SynapseException translated = translated(thrown);

        assertThat(translated).hasCode(expected).hasCategory(expected.category());
        assertThat(translated.getMessage()).isEqualTo(expected.messageTemplate());
        assertThat(translated.args()).isEmpty();
        assertThat(translated.getCause()).isSameAs(thrown);
    }

    @ParameterizedTest
    @EnumSource(value = ConstraintKind.class, names = "UNIQUE", mode = EnumSource.Mode.EXCLUDE)
    void otherConstraintKindsAreLeftToThePipeline(ConstraintKind kind) {
        assertThat(translation(constraintViolation(kind))).isEmpty();
    }

    @Test
    void aUniqueViolationWrappedAtCommitIsAlreadyExists() {
        ConstraintViolationException unique = constraintViolation(ConstraintKind.UNIQUE);
        DataIntegrityViolationException atCommit = new DataIntegrityViolationException("could not execute statement",
                unique);

        SynapseException translated = translated(atCommit);

        assertThat(translated).hasCode(SynapseErrors.ALREADY_EXISTS);
        assertThat(translated.getCause()).isSameAs(atCommit);
    }

    @Test
    void anOptimisticLockWrappedAtCommitIsStale() {
        JpaSystemException atCommit = new JpaSystemException(
                new RollbackException("Error while committing", new OptimisticLockException("stale")));

        assertThat(translated(atCommit)).hasCode(SynapseErrors.STALE_VERSION);
    }

    @Test
    void theOutermostMatchWins() {
        OptimisticLockException stale = new OptimisticLockException("stale",
                constraintViolation(ConstraintKind.UNIQUE));

        assertThat(translated(stale)).hasCode(SynapseErrors.STALE_VERSION);
    }

    @Test
    void otherExceptionsAreLeftToThePipeline() {
        assertThat(translation(new IllegalStateException("not a data failure"))).isEmpty();
        assertThat(translation(new DataIntegrityViolationException("not null"))).isEmpty();
    }

    @Test
    void translatesAtDepthTen() {
        assertThat(translated(wrapped(constraintViolation(ConstraintKind.UNIQUE), 10)))
                .hasCode(SynapseErrors.ALREADY_EXISTS);
    }

    @Test
    void stopsAtDepthTen() {
        assertThat(translation(wrapped(constraintViolation(ConstraintKind.UNIQUE), 11))).isEmpty();
    }

    @Test
    void aCauseCycleEnds() {
        IllegalStateException first = new IllegalStateException("first");
        IllegalStateException second = new IllegalStateException("second", first);
        first.initCause(second);

        assertThat(translation(first)).isEmpty();
    }

    private static ConstraintViolationException constraintViolation(ConstraintKind kind) {
        return new ConstraintViolationException("could not execute statement",
                new SQLException("Unique index or primary key violation: '978-0-00-000000-1'", "23505"), SQL, kind,
                "book_isbn_key");
    }

    private static QueryTimeoutException hibernateQueryTimeout() {
        return new QueryTimeoutException("JDBC exception executing SQL",
                new SQLException("canceling statement due to statement timeout", "57014"), "SELECT 1 FROM pg_sleep(5)");
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
