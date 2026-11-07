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
package io.americanexpress.synapse.api.grpc.internal.error;

import java.text.MessageFormat;
import java.util.Locale;

import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;

/**
 * The declared {@code .notFound} error of an empty {@code Optional}, raised by the gRPC edge when the code's message
 * template has argument placeholders. The edge has no arguments for them (a gRPC request has no path variables), so
 * {@link GrpcStatusWriter} describes it with {@link SynapseErrors#NOT_FOUND}'s message, localized like any other,
 * instead of the code's own template or {@code MessageSource} entry, which would be shown unformatted. The code stays
 * the declared one: it is the {@code ErrorInfo} reason, and it selects the status and its overrides.
 */
public final class NotFoundWithoutArgumentsException extends SynapseException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the error.
     *
     * @param declared the method's {@code .notFound} code
     */
    public NotFoundWithoutArgumentsException(ErrorDetails declared) {
        super(declared);
    }

    /**
     * Returns whether a not-found code needs message arguments: its template has argument placeholders ({@code {0}};
     * quoted braces are text), or it is not a valid {@code MessageFormat} pattern, which would be shown as written.
     *
     * @param code the code
     * @return {@code true} when the code's message can't be shown without arguments
     */
    public static boolean needsArguments(ErrorDetails code) {
        try {
            return new MessageFormat(code.messageTemplate(), Locale.ROOT).getFormatsByArgumentIndex().length > 0;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }
}
