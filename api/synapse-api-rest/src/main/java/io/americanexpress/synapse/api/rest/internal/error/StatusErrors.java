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
import io.americanexpress.synapse.core.error.SynapseErrors;

/**
 * The one table that maps an HTTP status reported by the container or by Spring MVC (an {@code ErrorResponse} or a
 * {@code @ResponseStatus}) to a Synapse error. A status outside the table is kept: any other 4xx is
 * {@link RestErrors#CLIENT_ERROR} and any other 5xx {@link RestErrors#SERVER_ERROR}, with that status.
 */
final class StatusErrors {

    private StatusErrors() {
    }

    /**
     * Returns the error for a status in the table.
     *
     * @param status the HTTP status
     * @param container whether the status comes from the servlet container's error dispatch, where a 404 means that no
     *     route matched
     * @return the error, or {@code null} for a status outside the table
     */
    static @Nullable ErrorDetails forStatus(int status, boolean container) {
        return switch (status) {
            case 400 -> SynapseErrors.MALFORMED_REQUEST;
            case 401 -> SynapseErrors.UNAUTHENTICATED;
            case 403 -> SynapseErrors.FORBIDDEN;
            case 404 -> container ? RestErrors.ROUTE_NOT_FOUND : SynapseErrors.NOT_FOUND;
            case 405 -> RestErrors.METHOD_NOT_ALLOWED;
            case 406 -> RestErrors.NOT_ACCEPTABLE;
            case 409 -> SynapseErrors.CONFLICT;
            case 412 -> SynapseErrors.PRECONDITION_FAILED;
            case 413 -> RestErrors.PAYLOAD_TOO_LARGE;
            case 415 -> RestErrors.UNSUPPORTED_MEDIA_TYPE;
            case 429 -> SynapseErrors.RATE_LIMITED;
            case 500 -> SynapseErrors.INTERNAL;
            case 503 -> SynapseErrors.UNAVAILABLE;
            case 504 -> SynapseErrors.DEADLINE_EXCEEDED;
            default -> null;
        };
    }

    /**
     * Returns the error a status is written as, with the headers and reason of the exception that answered it.
     *
     * @param status the HTTP status
     * @param container whether the status comes from the servlet container's error dispatch
     * @param headers the response headers of the exception, by name
     * @param reason the reason of the exception, or {@code null}
     * @param cause the exception, or {@code null} for a bare status
     * @return the error: the table's code without a status of its own, or {@code CLIENT_ERROR} or
     *     {@code SERVER_ERROR} keeping the status; {@code null} for 500 and for a status outside 400-599, which are
     *     {@code INTERNAL}
     */
    static @Nullable KeptStatusException keep(int status, boolean container, Map<String, String> headers,
            @Nullable String reason, @Nullable Throwable cause) {
        ErrorDetails details = forStatus(status, container);
        if (details == SynapseErrors.INTERNAL) {
            return null;
        }
        if (details != null) {
            return new KeptStatusException(details, null, headers, reason, cause);
        }
        if (status >= 400 && status <= 499) {
            return new KeptStatusException(RestErrors.CLIENT_ERROR, status, headers, reason, cause);
        }
        if (status >= 500 && status <= 599) {
            return new KeptStatusException(RestErrors.SERVER_ERROR, status, headers, reason, cause);
        }
        return null;
    }
}
