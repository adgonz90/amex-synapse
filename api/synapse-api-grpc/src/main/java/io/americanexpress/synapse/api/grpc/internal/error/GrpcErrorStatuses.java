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
import io.americanexpress.synapse.core.error.SynapseException;

/**
 * Decides the gRPC status of an error. In order, the first that applies wins: the status configured for the
 * operation, the status configured for every operation, the fixed status of a built-in code
 * ({@link SynapseErrors#ALREADY_EXISTS} is ALREADY_EXISTS, a {@link GrpcErrors} code its own status), and the status
 * of the error's category. A stale {@code etag} is {@code ABORTED} unless a status is configured for its code (see
 * {@link #statusFor(SynapseException, String)}). {@link #codeFor(Status.Code)} maps the other way, for a status that
 * Synapse didn't write.
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
     * Returns the status for an error, as {@link #statusFor(ErrorDetails, String)} does for its code, with one
     * exception: a stale {@code etag} is {@code ABORTED} (AIP-154), unless a {@code [PRECONDITION_FAILED]} status is
     * configured for the operation or for every operation. A stale {@code etag} is the
     * {@link SynapseErrors#PRECONDITION_FAILED} the edge answers when the operation reports
     * {@link SynapseErrors#STALE_VERSION} on a request that sent one: its direct cause is that {@code STALE_VERSION}.
     * Every other {@code PRECONDITION_FAILED} (a weak or unbound tag, or one the operation throws itself without a
     * {@code STALE_VERSION} as its direct cause; with one, it is {@code ABORTED} too) keeps the
     * category's {@code FAILED_PRECONDITION}, and a {@code [STALE_VERSION]} status doesn't apply to it, since the code
     * the caller sees is {@code PRECONDITION_FAILED}.
     *
     * @param error the error
     * @param operationName the operation that failed, or {@code null} when none did
     * @return the gRPC status code, never OK
     */
    public Status.Code statusFor(SynapseException error, @Nullable String operationName) {
        ErrorDetails details = error.details();
        if (details == SynapseErrors.PRECONDITION_FAILED && error.getCause() instanceof SynapseException cause
                && cause.details() == SynapseErrors.STALE_VERSION
                && configured(details.code(), operationName) == null) {
            return Status.Code.ABORTED;
        }
        return statusFor(details, operationName);
    }

    /**
     * Returns the status for an error.
     *
     * @param details the error
     * @param operationName the operation that failed, or {@code null} when none did
     * @return the gRPC status code, never OK
     */
    public Status.Code statusFor(ErrorDetails details, @Nullable String operationName) {
        Status.Code configured = configured(details.code(), operationName);
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

    /**
     * Returns the Synapse code that a gRPC status maps back to: the code of a status that Synapse didn't write, such
     * as a hand-written service's own or a downstream call's failure it forwards. {@code INVALID_ARGUMENT} and
     * {@code OUT_OF_RANGE} are {@link SynapseErrors#VALIDATION_FAILED} (with no violations), {@code ABORTED} is
     * {@link SynapseErrors#CONFLICT}, {@code FAILED_PRECONDITION} is {@link SynapseErrors#PRECONDITION_FAILED},
     * {@code PERMISSION_DENIED} is {@link SynapseErrors#FORBIDDEN}, {@code RESOURCE_EXHAUSTED} is
     * {@link SynapseErrors#RATE_LIMITED}; {@code UNAUTHENTICATED}, {@code NOT_FOUND}, {@code ALREADY_EXISTS},
     * {@code UNAVAILABLE} and {@code DEADLINE_EXCEEDED} are the code of the same name; any other status is
     * {@link SynapseErrors#INTERNAL}.
     *
     * @param code the gRPC status code
     * @return the Synapse code, never {@code null}
     */
    public static ErrorDetails codeFor(Status.Code code) {
        return switch (code) {
            case INVALID_ARGUMENT, OUT_OF_RANGE -> SynapseErrors.VALIDATION_FAILED;
            case UNAUTHENTICATED -> SynapseErrors.UNAUTHENTICATED;
            case PERMISSION_DENIED -> SynapseErrors.FORBIDDEN;
            case NOT_FOUND -> SynapseErrors.NOT_FOUND;
            case ALREADY_EXISTS -> SynapseErrors.ALREADY_EXISTS;
            case ABORTED -> SynapseErrors.CONFLICT;
            case FAILED_PRECONDITION -> SynapseErrors.PRECONDITION_FAILED;
            case RESOURCE_EXHAUSTED -> SynapseErrors.RATE_LIMITED;
            case UNAVAILABLE -> SynapseErrors.UNAVAILABLE;
            case DEADLINE_EXCEEDED -> SynapseErrors.DEADLINE_EXCEEDED;
            // CANCELLED, UNKNOWN, UNIMPLEMENTED, INTERNAL, DATA_LOSS, and any code a later grpc-java adds.
            default -> SynapseErrors.INTERNAL;
        };
    }

    /** The status configured for the code: for the operation first, then for every operation; {@code null} if none. */
    private Status.@Nullable Code configured(String code, @Nullable String operationName) {
        if (operationName != null) {
            Map<String, Status.Code> own = perOperation.get(operationName);
            Status.Code status = own == null ? null : own.get(code);
            if (status != null) {
                return status;
            }
        }
        return global.get(code);
    }

    private static void validate(String prefix, String code, Status.Code status) {
        if (status == Status.Code.OK) {
            String key = prefix + ".[" + code + "]";
            throw new SynapseConfigurationException(key + " is OK, which is not an error status.",
                    "Set " + key + " to a gRPC error status code, such as FAILED_PRECONDITION, or remove it.");
        }
    }
}
