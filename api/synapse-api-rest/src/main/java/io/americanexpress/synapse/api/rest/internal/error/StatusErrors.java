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

import io.americanexpress.synapse.api.rest.RestErrors;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.error.SynapseErrors;

/**
 * The one table that maps an HTTP status reported by the container or by a Spring {@code ErrorResponse} to a Synapse
 * error.
 */
final class StatusErrors {

    private StatusErrors() {
    }

    /**
     * Returns the error for a status.
     *
     * @param status the HTTP status
     * @param container whether the status comes from the servlet container's error dispatch, where a 404 means that no
     *     route matched
     * @return the error; any other 4xx is {@code MALFORMED_REQUEST} and any other status {@code INTERNAL}
     */
    static ErrorDetails forStatus(int status, boolean container) {
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
            case 503 -> SynapseErrors.UNAVAILABLE;
            case 504 -> SynapseErrors.DEADLINE_EXCEEDED;
            default -> status >= 400 && status < 500 ? SynapseErrors.MALFORMED_REQUEST : SynapseErrors.INTERNAL;
        };
    }
}
