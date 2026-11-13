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
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.hibernate.FlushMode;
import org.hibernate.Session;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.americanexpress.synapse.data.CommitOutcomeUnknownException;
import io.americanexpress.synapse.data.internal.error.LostConnections;

/**
 * A JPA store's transaction manager: Spring's {@link JpaTransactionManager}, except for two failures.
 * <ul>
 * <li>A rollback that fails is a {@link TransactionSystemException}, as {@code JdbcTransactionManager} reports one.
 * {@code JpaTransactionManager} reports it as its dialect's {@code DataAccessException} (a {@code JpaSystemException}
 * for Hibernate's {@code TransactionException}), and Spring's transaction interceptor and {@code TransactionTemplate}
 * then throw it without the failure the rollback replaced. With a {@code TransactionSystemException} they keep that
 * failure as its application exception, which the data translators decide by, so a JPA store and a JDBC store report
 * the same failure the same way.</li>
 * <li>A commit that loses its connection is reported as the data module's {@code COMMIT_OUTCOME_UNKNOWN} (category
 * {@code INTERNAL}), not as a retryable {@code UNAVAILABLE}: the database may have applied the COMMIT and lost only its
 * reply. The manager throws a {@link io.americanexpress.synapse.data.CommitOutcomeUnknownException} for it. So that
 * this is only the commit itself, the manager first flushes the transaction's {@code EntityManager} (unless the
 * transaction is read-only or its session flushes manually). A connection lost in that flush is {@code UNAVAILABLE},
 * like any statement's: nothing was committed, and the transaction completes as rolled back, so after-rollback
 * callbacks run. Any other flush failure is translated as the commit's own flush failure is.</li>
 * </ul>
 * A lost connection is what the data translator reports as {@code UNAVAILABLE} everywhere else: Spring's connection
 * exceptions, the JDBC connection exception types, and SQLState class 08 or PostgreSQL's 57P01-57P03 among the
 * failure's causes. Spring doesn't roll back after a commit that lost its connection, so the manager rolls the
 * connection back itself unless it is closed (as Hikari closes one that reported a lost connection): a pooled
 * connection never goes back with an open transaction.
 * <p>
 * Synapse builds one for every JPA store. It is the class to extend when a store class replaces its
 * {@code <prefix>TransactionManager}: a replacement that isn't one loses both, and the data stores' startup report
 * warns about it. A subclass that overrides {@link #doCommit} or {@link #doRollback} calls {@code super}.
 */
public class SynapseJpaTransactionManager extends JpaTransactionManager {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the transaction manager.
     *
     * @param entityManagerFactory the store's entity manager factory
     */
    public SynapseJpaTransactionManager(EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        flushBeforeCommit(status);
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

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        try {
            super.doRollback(status);
        } catch (TransactionException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new TransactionSystemException("Could not roll back JPA transaction", ex);
        }
    }

    /**
     * Flushes the transaction's pending changes, so a statement's failure is not taken for the commit's. A connection
     * lost in the flush ends Hibernate's transaction first: a rollback, which succeeds on a connection that is still
     * open and fails without harm on one the pool closed. Spring then finds no active transaction to roll back, so the
     * transaction completes as rolled back (its true outcome: nothing was committed) and the flush's failure is thrown,
     * not a rollback's.
     */
    private void flushBeforeCommit(DefaultTransactionStatus status) {
        EntityManager entityManager = transactionalEntityManager();
        if (status.isReadOnly() || entityManager == null
                || entityManager.unwrap(Session.class).getHibernateFlushMode() == FlushMode.MANUAL) {
            return;
        }
        try {
            entityManager.flush();
        } catch (RuntimeException ex) {
            RuntimeException failure = DataAccessUtils.translateIfNecessary(ex, getJpaDialect());
            if (LostConnections.causedBy(ex)) {
                endTransaction(entityManager, failure);
            }
            throw failure;
        }
    }

    /** Rolls Hibernate's transaction back, keeping a failed rollback as suppressed by the failure. */
    private static void endTransaction(EntityManager entityManager, RuntimeException failure) {
        try {
            if (entityManager.getTransaction().isActive()) {
                entityManager.getTransaction().rollback();
            }
        } catch (RuntimeException rollback) {
            failure.addSuppressed(rollback);
        }
    }

    /**
     * Rolls the transaction back, unless its connection is closed: a connection that is still open goes back to the
     * pool, so it must not keep the transaction open. It is called after a failed commit, which has ended Hibernate's
     * transaction (failed commit, rolled back or failed rollback), so the JDBC connection is rolled back. A failed
     * rollback is kept as suppressed by the failure.
     */
    private void rollBackUnlessClosed(DefaultTransactionStatus status, RuntimeException failure) {
        EntityManager entityManager = transactionalEntityManager();
        if (entityManager == null) {
            return;
        }
        try {
            Session session = entityManager.unwrap(Session.class);
            if (Boolean.TRUE.equals(session.doReturningWork(Connection::isClosed))) {
                return;
            }
            // Defensive: Hibernate 7.4 never leaves its transaction active after a failed commit. Should a later
            // version do so, Spring's rollback ends it properly.
            if (entityManager.getTransaction().isActive()) {
                super.doRollback(status);
            } else {
                session.doWork(Connection::rollback);
            }
        } catch (RuntimeException rollback) {
            failure.addSuppressed(rollback);
        }
    }

    private @Nullable EntityManager transactionalEntityManager() {
        return TransactionSynchronizationManager
                .getResource(obtainEntityManagerFactory()) instanceof EntityManagerHolder holder
                        ? holder.getEntityManager()
                        : null;
    }
}
