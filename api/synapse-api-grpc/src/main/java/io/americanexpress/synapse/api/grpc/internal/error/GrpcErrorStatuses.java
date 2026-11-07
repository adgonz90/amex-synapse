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

import java.util.HashMap;
import java.util.Map;

import io.grpc.Status;
import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.api.grpc.GrpcErrors;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.error.SynapseErrors;

/**
 * Decides the gRPC status of an error. In order, the first that applies wins: the status configured for the
 * operation, the status configured for every operation, the fixed status of a built-in code
 * ({@link SynapseErrors#ALREADY_EXISTS} is ALREADY_EXISTS, a {@link GrpcErrors} code its own status), and the status
 * of the error's category.
 */
public final class GrpcErrorStatuses {

    private static final String GLOBAL_KEY = "synapse.api.grpc.errors.status";

    private final Map<String, Status.Code> global;

    private final Map<String, Map<String, Status.Code>> perOperation;

    /**
     * Creates the table.
     *
     * @param global statuses by error code for every operation
     * @param perOperation statuses by error code, keyed by operation name
     * @throws SynapseConfigurationException when a status is OK
     */
    public GrpcErrorStatuses(Map<String, Status.Code> global, Map<String, Map<String, Status.Code>> perOperation) {
        global.forEach((code, status) -> validate(GLOBAL_KEY, code, status));
        Map<String, Map<String, Status.Code>> copy = new HashMap<>();
        perOperation.forEach((operation, statuses) -> {
            statuses
                    .forEach((code, status) -> validate(
                            "synapse.api.grpc.operations." + operation + ".errors.status", code, status));
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
     * @return the gRPC status code, never OK
     */
    public Status.Code statusFor(ErrorDetails details, @Nullable String operationName) {
        String code = details.code();
        if (operationName != null) {
            Map<String, Status.Code> own = perOperation.get(operationName);
            Status.Code status = own == null ? null : own.get(code);
            if (status != null) {
                return status;
            }
        }
        Status.Code configured = global.get(code);
        if (configured != null) {
            return configured;
        }
        if (details == SynapseErrors.ALREADY_EXISTS) {
            return Status.Code.ALREADY_EXISTS;
        }
        if (details instanceof GrpcErrors grpcError) {
            return grpcError.status();
        }
        return switch (details.category()) {
            case INVALID_INPUT -> Status.Code.INVALID_ARGUMENT;
            case UNAUTHENTICATED -> Status.Code.UNAUTHENTICATED;
            case FORBIDDEN -> Status.Code.PERMISSION_DENIED;
            case NOT_FOUND -> Status.Code.NOT_FOUND;
            case CONFLICT -> Status.Code.ABORTED;
            case PRECONDITION_FAILED -> Status.Code.FAILED_PRECONDITION;
            case RATE_LIMITED -> Status.Code.RESOURCE_EXHAUSTED;
            case UNAVAILABLE -> Status.Code.UNAVAILABLE;
            case TIMEOUT -> Status.Code.DEADLINE_EXCEEDED;
            case INTERNAL -> Status.Code.INTERNAL;
        };
    }

    private static void validate(String prefix, String code, Status.Code status) {
        if (status == Status.Code.OK) {
            String key = prefix + ".[" + code + "]";
            throw new SynapseConfigurationException(key + " is OK, which is not an error status.",
                    "Set " + key + " to a gRPC error status code, such as FAILED_PRECONDITION, or remove it.");
        }
    }
}
