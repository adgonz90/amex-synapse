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
package io.americanexpress.synapse.data.jpa;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.PersistenceException;

import org.hibernate.FlushMode;
import org.hibernate.Session;
import org.hibernate.jdbc.Work;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.data.CommitOutcomeUnknownException;
import io.americanexpress.synapse.data.internal.error.DataExceptionTranslator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The JPA store's transaction manager against an {@code EntityManagerFactory} whose transaction fails on demand, as a
 * transaction does on a connection the pool has closed.
 */
class SynapseJpaTransactionManagerTest {

    private final EntityManagerFactory entityManagerFactory = mock(EntityManagerFactory.class);

    private final EntityManager entityManager = mock(EntityManager.class);

    private final EntityTransaction transaction = mock(EntityTransaction.class);

    private final Session session = mock(Session.class);

    private final TransactionTemplate template = new TransactionTemplate(
            new SynapseJpaTransactionManager(entityManagerFactory));

    @BeforeEach
    void entityManagers() {
        when(entityManagerFactory.createEntityManager()).thenReturn(entityManager);
        when(entityManager.getTransaction()).thenReturn(transaction);
        when(transaction.isActive()).thenReturn(true);
        when(entityManager.unwrap(Session.class)).thenReturn(session);
        when(session.getHibernateFlushMode()).thenReturn(FlushMode.AUTO);
        // Hikari has closed the connection, as it does after a class-08 state.
        when(session.<Boolean>doReturningWork(any())).thenReturn(true);
    }

    /**
     * A failed rollback is a {@code TransactionSystemException}, as {@code JdbcTransactionManager} reports it, so Spring
     * keeps the failure the rollback replaced as its application exception. {@code JpaTransactionManager} reports it
     * as a {@code JpaSystemException}, and Spring then drops that failure.
     */
    @Test
    void aFailedRollbackKeepsTheFailureItReplaced() {
        PersistenceException rollback = new PersistenceException("Unable to rollback against JDBC Connection",
                new SQLException("Connection is closed"));
        doThrow(rollback).when(transaction).rollback();
        IllegalStateException statement = new IllegalStateException("the statement failed");

        Throwable thrown = catchThrowable(() -> template.executeWithoutResult(status -> {
            throw statement;
        }));

        assertThat(thrown).isInstanceOfSatisfying(TransactionSystemException.class, failure -> {
            assertThat(failure.getApplicationException()).isSameAs(statement);
            assertThat(failure.getCause())
                    .isInstanceOf(JpaSystemException.class)
                    .hasRootCauseInstanceOf(SQLException.class);
        });
    }

    /** A rollback that succeeds throws the failure it rolled back, as before. */
    @Test
    void aRollbackThatSucceedsThrowsTheFailure() {
        IllegalStateException statement = new IllegalStateException("the statement failed");

        Throwable thrown = catchThrowable(() -> template.executeWithoutResult(status -> {
            throw statement;
        }));

        assertThat(thrown).isSameAs(statement);
    }

    /**
     * A commit that loses its connection has an unknown outcome: the database may have committed and lost only the
     * reply. The transaction manager doesn't roll back, and reports the commit's own failure as the cause.
     */
    @Test
    void aCommitThatLosesItsConnectionHasAnUnknownOutcome() {
        doThrow(new PersistenceException("Unable to commit against JDBC Connection",
                new SQLException("An I/O error occurred while sending to the backend.", "08006")))
                .when(transaction)
                .commit();

        Throwable thrown = catchThrowable(() -> template.executeWithoutResult(status -> {
        }));

        assertThat(thrown)
                .isInstanceOf(CommitOutcomeUnknownException.class)
                .cause()
                .isInstanceOf(JpaSystemException.class);
        verify(transaction, never()).rollback();
    }

    /**
     * A commit the server rejects for a full disk (53100, insufficient resources) didn't lose its connection, so
     * nothing was committed: the transaction is rolled back, and the failure is the server's transient
     * {@code UNAVAILABLE}, as for a JDBC store.
     */
    @Test
    void aCommitRejectedForDiskFullIsNotOutcomeUnknown() {
        doThrow(new PersistenceException("Unable to commit against JDBC Connection",
                new SQLException("could not extend file: No space left on device", "53100")))
                .when(transaction)
                .commit();

        Throwable thrown = catchThrowable(() -> template.executeWithoutResult(status -> {
        }));

        assertThat(thrown).isInstanceOf(JpaSystemException.class).isNotInstanceOf(CommitOutcomeUnknownException.class);
        verify(transaction).rollback();
        SynapseException translated = new DataExceptionTranslator().translate(Objects.requireNonNull(thrown));
        assertThat(Objects.requireNonNull(translated).code()).isEqualTo(SynapseErrors.UNAVAILABLE.code());
    }

    /** A commit that fails on a connection that wasn't lost is reported as before. */
    @Test
    void anotherCommitFailureIsReportedAsBefore() {
        doThrow(new PersistenceException("constraint", new SQLException("duplicate key", "23505")))
                .when(transaction)
                .commit();

        assertThat(catchThrowable(() -> template.executeWithoutResult(status -> {
        }))).isInstanceOf(JpaSystemException.class);
    }

    /** The transaction manager flushes before the commit, so a statement's failure isn't a commit's. */
    @Test
    void theFlushRunsBeforeTheCommit() {
        template.executeWithoutResult(status -> {
        });

        InOrder order = inOrder(entityManager, transaction);
        order.verify(entityManager).flush();
        order.verify(transaction).commit();
    }

    /**
     * A connection lost while flushing, before the COMMIT was sent, is a statement failure: nothing was committed. It
     * is not an unknown commit outcome, the commit isn't attempted, and the transaction ends as rolled back, its true
     * outcome, so after-rollback callbacks run. Here the pool has closed the connection, so the rollback of Hibernate's
     * transaction fails, and that failure doesn't replace the flush's.
     */
    @Test
    void aConnectionLostWhileFlushingEndsTheTransactionAsRolledBack() {
        doThrow(new PersistenceException("could not execute statement",
                new SQLException("An I/O error occurred while sending to the backend.", "08006")))
                .when(entityManager)
                .flush();
        doAnswer(invocation -> {
            when(transaction.isActive()).thenReturn(false);
            throw new PersistenceException("Unable to rollback against JDBC Connection",
                    new SQLException("Connection is closed"));
        }).when(transaction).rollback();
        List<Integer> completions = new ArrayList<>();

        Throwable thrown = catchThrowable(() -> template.executeWithoutResult(status -> recordCompletion(completions)));

        assertThat(thrown)
                .isInstanceOf(JpaSystemException.class)
                .isNotInstanceOf(TransactionSystemException.class)
                .hasMessageContaining("could not execute statement");
        assertThat(completions).containsExactly(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(transaction, never()).commit();
    }

    /** Another flush failure is translated as the commit's own flush failure was, and the transaction rolled back. */
    @Test
    void anotherFlushFailureIsTranslatedAndRolledBack() {
        doThrow(new PersistenceException("constraint", new SQLException("duplicate key", "23505")))
                .when(entityManager)
                .flush();

        assertThat(catchThrowable(() -> template.executeWithoutResult(status -> {
        }))).isInstanceOf(JpaSystemException.class);
        verify(transaction, never()).commit();
        verify(transaction).rollback();
    }

    /** A read-only transaction, or one whose session flushes manually, is not flushed. */
    @Test
    void readOnlyAndManualFlushTransactionsAreNotFlushed() {
        template.setReadOnly(true);
        template.executeWithoutResult(status -> {
        });
        template.setReadOnly(false);
        when(session.getHibernateFlushMode()).thenReturn(FlushMode.MANUAL);
        template.executeWithoutResult(status -> {
        });

        verify(entityManager, never()).flush();
    }

    /**
     * A connection exception type without a class-08 state doesn't make Hikari close the connection. It is still open,
     * so the transaction manager rolls the flush's transaction back before it reports the lost connection: a pooled
     * connection never goes back with an open transaction.
     */
    @Test
    void aConnectionLostWhileFlushingOnAnOpenConnectionIsRolledBack() {
        doThrow(new PersistenceException("could not execute statement",
                new SQLNonTransientConnectionException("The connection was reset")))
                .when(entityManager)
                .flush();
        doAnswer(invocation -> {
            when(transaction.isActive()).thenReturn(false);
            return null;
        }).when(transaction).rollback();
        List<Integer> completions = new ArrayList<>();

        assertThat(catchThrowable(() -> template.executeWithoutResult(status -> recordCompletion(completions))))
                .isInstanceOf(JpaSystemException.class)
                .isNotInstanceOf(CommitOutcomeUnknownException.class);
        verify(transaction).rollback();
        assertThat(completions).containsExactly(TransactionSynchronization.STATUS_ROLLED_BACK);
    }

    /** Records how the transaction completes, as an after-rollback listener would see it. */
    private static void recordCompletion(List<Integer> completions) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

            @Override
            public void afterCompletion(int status) {
                completions.add(status);
            }
        });
    }

    /**
     * A commit that loses a connection that is still open (Hibernate's transaction is no longer active after its
     * failed commit) has the connection itself rolled back, and still reports an unknown outcome.
     */
    @Test
    void aCommitThatLosesAnOpenConnectionRollsTheConnectionBack() throws SQLException {
        Connection jdbc = mock(Connection.class);
        when(session.<Boolean>doReturningWork(any())).thenReturn(false);
        doAnswer(invocation -> {
            invocation.<Work>getArgument(0).execute(jdbc);
            return null;
        }).when(session).doWork(any());
        doAnswer(invocation -> {
            when(transaction.isActive()).thenReturn(false);
            throw new PersistenceException("Unable to commit against JDBC Connection",
                    new SQLNonTransientConnectionException("The connection was reset"));
        }).when(transaction).commit();

        assertThat(catchThrowable(() -> template.executeWithoutResult(status -> {
        }))).isInstanceOf(CommitOutcomeUnknownException.class);
        verify(jdbc).rollback();
    }
}
