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

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.api.rest.RestErrors;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.error.SynapseErrors;

/**
 * Decides the HTTP status of an error. In order, the first that applies wins: the status configured for the
 * operation, the status configured for every operation, the fixed status of a {@link RestErrors} code, 428 for
 * {@link SynapseErrors#PRECONDITION_REQUIRED}, and the status of the error's category.
 */
public final class RestErrorStatuses {

    private static final String GLOBAL_KEY = "synapse.api.rest.errors.status";

    private final Map<String, Integer> global;

    private final Map<String, Map<String, Integer>> perOperation;

    /**
     * Creates the table.
     *
     * @param global statuses by error code for every operation
     * @param perOperation statuses by error code, keyed by operation name
     * @throws SynapseConfigurationException when a status is outside 400-599
     */
    public RestErrorStatuses(Map<String, Integer> global, Map<String, Map<String, Integer>> perOperation) {
        global.forEach((code, status) -> validate(GLOBAL_KEY, code, status));
        Map<String, Map<String, Integer>> copy = new HashMap<>();
        perOperation.forEach((operation, statuses) -> {
            statuses
                    .forEach((code, status) -> validate(
                            "synapse.api.rest.operations." + operation + ".errors.status", code, status));
            copy.put(operation, Map.copyOf(statuses));
        });
        this.global = Map.copyOf(global);
        this.perOperation = Map.copyOf(copy);
    }

    /**
     * Returns the status for an error.
     *
     * @param details the error
     * @param operationName the operation that failed, or {@code null} when none did
     * @return the HTTP status
     */
    public int statusFor(ErrorDetails details, @Nullable String operationName) {
        String code = details.code();
        if (operationName != null) {
            Map<String, Integer> own = perOperation.get(operationName);
            Integer status = own == null ? null : own.get(code);
            if (status != null) {
                return status;
            }
        }
        Integer configured = global.get(code);
        if (configured != null) {
            return configured;
        }
        if (details instanceof RestErrors restError) {
            return restError.status();
        }
        if (details == SynapseErrors.PRECONDITION_REQUIRED) {
            // Its category is PRECONDITION_FAILED (412), but HTTP has a status of its own for it (RFC 6585 §3).
            return 428;
        }
        return switch (details.category()) {
            case INVALID_INPUT -> 400;
            case UNAUTHENTICATED -> 401;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            case PRECONDITION_FAILED -> 412;
            case RATE_LIMITED -> 429;
            case UNAVAILABLE -> 503;
            case TIMEOUT -> 504;
            case INTERNAL -> 500;
        };
    }

    private static void validate(String prefix, String code, int status) {
        if (status < 400 || status > 599) {
            String key = prefix + ".[" + code + "]";
            throw new SynapseConfigurationException(
                    key + " is " + status + ", which is not an HTTP error status (400 to 599).",
                    "Set " + key + " to a status from 400 to 599, or remove it.");
        }
    }
}
