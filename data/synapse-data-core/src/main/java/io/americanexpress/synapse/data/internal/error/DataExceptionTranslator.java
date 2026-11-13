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

import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;

import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.error.ExceptionTranslator;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.data.CommitOutcomeUnknownException;
import io.americanexpress.synapse.data.DataErrors;

/**
 * Translates Spring's data access exceptions into Synapse errors: an optimistic lock failure into
 * {@code STALE_VERSION}, a unique-constraint violation into {@code ALREADY_EXISTS}, a query timeout into
 * {@code DEADLINE_EXCEEDED}, and a lost connection (see {@link LostConnections}: Spring's connection exceptions, the
 * JDBC connection exception types, and class-08 and session-ended states) into {@code UNAVAILABLE}. An unknown commit
 * outcome comes first, anywhere in the chain: a {@link CommitOutcomeUnknownException}, which a store's transaction
 * manager throws for a commit that lost its connection, or an {@code SQLException} of state {@code 08007} (the SQL
 * standard's transaction_resolution_unknown), however Spring wrapped it. It is {@code COMMIT_OUTCOME_UNKNOWN}
 * ({@code INTERNAL}), not {@code UNAVAILABLE}: the database may have committed, so a retry could apply the write twice.
 * Any other exception is left to the pipeline, which reports it as {@code INTERNAL}.
 * <p>
 * The translator looks at the thrown exception and up to {@value CauseChain#MAX_DEPTH} of its causes (see
 * {@link CauseChain}), so a failure wrapped at commit (for example in a {@code TransactionSystemException}) is still
 * found; the outermost match wins. A rollback that failed after the application's failure is decided by that failure
 * alone, which Spring keeps as the {@code TransactionSystemException}'s application exception. The Synapse error
 * carries only its own message: the thrown exception is its cause, logged by the pipeline on the server side, and
 * never shown to the caller.
 * <p>
 * It is {@link Ordered} at {@value #ORDER}: before the JPA translator and before any translator that declares no order.
 */
public final class DataExceptionTranslator implements ExceptionTranslator, Ordered {

    /** The translator's order: {@code Ordered.LOWEST_PRECEDENCE - 100}. */
    public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 100;

    @Override
    public @Nullable SynapseException translate(Throwable exception) {
        return CauseChain.translate(exception, DataExceptionTranslator::errorFor);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    private static @Nullable ErrorDetails errorFor(Throwable exception) {
        if (LostConnections.outcomeUnknown(exception)) {
            return DataErrors.COMMIT_OUTCOME_UNKNOWN;
        }
        if (exception instanceof OptimisticLockingFailureException) {
            return SynapseErrors.STALE_VERSION;
        }
        if (exception instanceof DuplicateKeyException) {
            return SynapseErrors.ALREADY_EXISTS;
        }
        if (exception instanceof QueryTimeoutException) {
            return SynapseErrors.DEADLINE_EXCEEDED;
        }
        if (LostConnections.isLostConnection(exception)) {
            return SynapseErrors.UNAVAILABLE;
        }
        return null;
    }
}
