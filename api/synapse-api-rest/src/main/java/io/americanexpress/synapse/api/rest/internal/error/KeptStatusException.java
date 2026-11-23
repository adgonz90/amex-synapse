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
package io.americanexpress.synapse.api.rest.internal.error;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.api.rest.RestErrors;
import io.americanexpress.synapse.core.error.ErrorDetails;

/**
 * A status that Spring MVC or the servlet container answered, as Synapse writes it: a {@code ResponseStatusException}
 * or another {@code ErrorResponse}, an exception annotated with {@code @ResponseStatus}, or a bare status of the error
 * dispatch. It carries the exception's response headers and its reason. A status in Synapse's table has that table's
 * code and no {@link #status()} of its own; any other 4xx is {@link RestErrors#CLIENT_ERROR} and any other 5xx except
 * 500 {@link RestErrors#SERVER_ERROR}, and both keep the status, unless one is configured for the code
 * ({@link RestErrorStatuses#statusFor(io.americanexpress.synapse.core.error.SynapseException, String)}).
 */
public final class KeptStatusException extends RestProtocolException {

    private static final long serialVersionUID = 1L;

    private final @Nullable Integer status;

    private final @Nullable String reason;

    /**
     * Creates the exception.
     *
     * @param details the error
     * @param status the status to keep: a 4xx for {@link RestErrors#CLIENT_ERROR}, a 5xx other than 500 for
     *     {@link RestErrors#SERVER_ERROR}, and {@code null} for every other code
     * @param headers the response headers of the exception, by name
     * @param reason the exception's reason, written as the problem's detail member only when
     *     {@code synapse.errors.include-detail} is {@code true}
     * @param cause the exception, or {@code null} for a bare status of the error dispatch
     * @throws IllegalArgumentException when the status doesn't fit the code
     */
    public KeptStatusException(ErrorDetails details, @Nullable Integer status, Map<String, String> headers,
            @Nullable String reason, @Nullable Throwable cause) {
        super(details, headers, cause);
        if (!fits(details, status)) {
            throw new IllegalArgumentException("A kept status " + status + " doesn't fit " + details.code()
                    + ": only CLIENT_ERROR (4xx) and SERVER_ERROR (5xx other than 500) keep one, and they must");
        }
        this.status = status;
        this.reason = reason;
    }

    private static boolean fits(ErrorDetails details, @Nullable Integer status) {
        if (details == RestErrors.CLIENT_ERROR) {
            return status != null && status >= 400 && status <= 499;
        }
        if (details == RestErrors.SERVER_ERROR) {
            return status != null && status >= 501 && status <= 599;
        }
        return status == null;
    }

    /**
     * Returns the status to keep.
     *
     * @return the status, or {@code null} when the code's own status applies
     */
    public @Nullable Integer status() {
        return status;
    }

    /**
     * Returns the reason of the exception.
     *
     * @return the reason, or {@code null} when it has none
     */
    public @Nullable String reason() {
        return reason;
    }
}
