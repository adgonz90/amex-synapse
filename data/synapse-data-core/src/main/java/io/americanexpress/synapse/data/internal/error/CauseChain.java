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

import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.springframework.transaction.TransactionSystemException;

import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.data.CommitOutcomeUnknownException;

/**
 * The cause-chain walk every data translator shares: it looks at the thrown exception and up to {@value #MAX_DEPTH}
 * of its causes, so a failure wrapped at commit is still found, and the outermost match wins. The Synapse error
 * carries only its own message; the thrown exception is its cause, logged by the pipeline on the server side and never
 * shown to the caller.
 * <p>
 * A {@link TransactionSystemException} with an {@link TransactionSystemException#getApplicationException() application
 * exception} is a rollback that failed after the application's own failure, which Spring keeps there instead of as a
 * cause. Unless the exception itself has an error, that failure alone decides the error, its chain walked the same
 * way. What made the rollback fail is not looked at: a connection the pool closed, for example, says nothing about why
 * the application failed. A {@link SynapseException} in that failure's chain (an operation's own rejection) keeps its
 * code and arguments, with the thrown exception as its cause; one with violations is returned as it is. A
 * {@link CommitOutcomeUnknownException} is never decided by its application exception (which Spring attaches after a
 * failure the transaction doesn't roll back for): the commit's unknown outcome is what the caller must learn.
 */
public final class CauseChain {

    /** How many causes below the thrown exception are looked at. */
    public static final int MAX_DEPTH = 10;

    /** How many failures, each replaced by the failed rollback of an enclosing transaction, are followed. */
    private static final int MAX_REPLACED = 3;

    private CauseChain() {
    }

    /**
     * Translates the first exception in the chain that has an error.
     *
     * @param exception the thrown exception
     * @param errors the error of an exception in the chain, or {@code null} when it has none
     * @return the translation, or {@code null} when no exception in the chain has an error
     */
    public static @Nullable SynapseException translate(Throwable exception,
            Function<Throwable, ? extends @Nullable ErrorDetails> errors) {
        Match match = match(exception, errors, 0);
        if (match == null) {
            return null;
        }
        SynapseException original = match.original();
        if (original == null) {
            return new SynapseException(match.details(), exception);
        }
        if (!original.violations().isEmpty()) {
            return original;
        }
        return new SynapseException(original.details(), exception, original.args().toArray());
    }

    private static @Nullable Match match(Throwable exception,
            Function<Throwable, ? extends @Nullable ErrorDetails> errors,
            int replaced) {
        Throwable current = exception;
        for (int depth = 0; depth <= MAX_DEPTH && current != null; depth++) {
            if (replaced > 0 && current instanceof SynapseException original) {
                return new Match(original.details(), original);
            }
            ErrorDetails error = errors.apply(current);
            if (error != null) {
                return new Match(error, null);
            }
            if (replaced < MAX_REPLACED && current instanceof TransactionSystemException rollback
                    && !(rollback instanceof CommitOutcomeUnknownException)) {
                Throwable application = rollback.getApplicationException();
                if (application != null) {
                    return match(application, errors, replaced + 1);
                }
            }
            current = current.getCause();
        }
        return null;
    }

    /** The error found, and the operation's own exception when it is one. */
    private record Match(ErrorDetails details, @Nullable SynapseException original) {
    }
}
