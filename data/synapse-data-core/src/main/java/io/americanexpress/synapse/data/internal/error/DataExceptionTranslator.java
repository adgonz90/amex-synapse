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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.CannotCreateTransactionException;

import io.americanexpress.synapse.core.error.ExceptionTranslator;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;

/**
 * Translates Spring's data access exceptions into Synapse errors: an optimistic lock failure into
 * {@code STALE_VERSION}, a unique-constraint violation into {@code ALREADY_EXISTS}, a query timeout into
 * {@code DEADLINE_EXCEEDED}, and a connection failure (including a transaction that can't begin) into
 * {@code UNAVAILABLE}. Any other exception is left to the pipeline, which reports it as {@code INTERNAL}.
 * <p>
 * The translator looks at the thrown exception and up to {@value CauseChain#MAX_DEPTH} of its causes (see
 * {@link CauseChain}), so a failure wrapped at commit (for example in a {@code TransactionSystemException}) is still
 * found; the outermost match wins. The Synapse error carries only its own message: the thrown exception is its cause,
 * logged by the pipeline on the server side, and never shown to the caller.
 */
public final class DataExceptionTranslator implements ExceptionTranslator {

    @Override
    public @Nullable SynapseException translate(Throwable exception) {
        return CauseChain.translate(exception, DataExceptionTranslator::errorFor);
    }

    private static @Nullable SynapseErrors errorFor(Throwable exception) {
        if (exception instanceof OptimisticLockingFailureException) {
            return SynapseErrors.STALE_VERSION;
        }
        if (exception instanceof DuplicateKeyException) {
            return SynapseErrors.ALREADY_EXISTS;
        }
        if (exception instanceof QueryTimeoutException) {
            return SynapseErrors.DEADLINE_EXCEEDED;
        }
        if (exception instanceof DataAccessResourceFailureException
                || exception instanceof TransientDataAccessResourceException
                || exception instanceof CannotCreateTransactionException) {
            return SynapseErrors.UNAVAILABLE;
        }
        return null;
    }
}
