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

import jakarta.persistence.OptimisticLockException;

import org.hibernate.QueryTimeoutException;
import org.hibernate.StaleStateException;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.core.error.ExceptionTranslator;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.data.internal.error.CauseChain;

/**
 * Translates JPA's and Hibernate's own exceptions, which reach the pipeline when Spring didn't translate them (or
 * translated them into a type that loses their meaning), into Synapse errors: an optimistic lock failure
 * ({@link OptimisticLockException}, {@link StaleStateException}) into {@code STALE_VERSION}, a unique-constraint
 * violation (a Hibernate {@link ConstraintViolationException} of kind {@link ConstraintKind#UNIQUE}, which Spring ORM
 * reports as a plain {@code DataIntegrityViolationException}) into {@code ALREADY_EXISTS}, and a query timeout (JPA's
 * {@link jakarta.persistence.QueryTimeoutException}, or Hibernate's {@link QueryTimeoutException}, which a native
 * query through the {@code EntityManager} throws inside a plain {@code PersistenceException}) into
 * {@code DEADLINE_EXCEEDED}. Any other exception, a lock timeout included, is left to the other translators and the
 * pipeline.
 * <p>
 * The translator looks at the thrown exception and up to {@value CauseChain#MAX_DEPTH} of its causes (see
 * {@link CauseChain}), so a failure wrapped at commit is still found; the outermost match wins. The Synapse error
 * carries only its own message: the thrown exception is its cause, logged by the pipeline on the server side, and
 * never shown to the caller.
 */
public final class JpaExceptionTranslator implements ExceptionTranslator {

    @Override
    public @Nullable SynapseException translate(Throwable exception) {
        return CauseChain.translate(exception, JpaExceptionTranslator::errorFor);
    }

    private static @Nullable SynapseErrors errorFor(Throwable exception) {
        if (exception instanceof OptimisticLockException || exception instanceof StaleStateException) {
            return SynapseErrors.STALE_VERSION;
        }
        if (exception instanceof ConstraintViolationException violation
                && violation.getKind() == ConstraintKind.UNIQUE) {
            return SynapseErrors.ALREADY_EXISTS;
        }
        if (exception instanceof jakarta.persistence.QueryTimeoutException
                || exception instanceof QueryTimeoutException) {
            return SynapseErrors.DEADLINE_EXCEEDED;
        }
        return null;
    }
}
