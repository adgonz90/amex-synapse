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

import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;

/**
 * The cause-chain walk every data translator shares: it looks at the thrown exception and up to {@value #MAX_DEPTH}
 * of its causes, so a failure wrapped at commit is still found, and the outermost match wins. The Synapse error
 * carries only its own message; the thrown exception is its cause, logged by the pipeline on the server side and never
 * shown to the caller.
 */
public final class CauseChain {

    /** How many causes below the thrown exception are looked at. */
    public static final int MAX_DEPTH = 10;

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
            Function<Throwable, @Nullable SynapseErrors> errors) {
        Throwable current = exception;
        for (int depth = 0; depth <= MAX_DEPTH && current != null; depth++) {
            SynapseErrors error = errors.apply(current);
            if (error != null) {
                return new SynapseException(error, exception);
            }
            current = current.getCause();
        }
        return null;
    }
}
