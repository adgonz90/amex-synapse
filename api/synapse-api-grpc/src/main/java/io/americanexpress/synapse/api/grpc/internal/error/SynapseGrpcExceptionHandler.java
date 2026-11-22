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

import java.util.Map;
import java.util.UUID;

import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.grpc.server.exception.GrpcExceptionHandler;

import io.americanexpress.synapse.api.grpc.internal.edge.GrpcCallState;
import io.americanexpress.synapse.core.context.RequestContext;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.internal.context.ContextBinder;

/**
 * Turns a failure of a gRPC call into its status. Spring gRPC asks every {@link GrpcExceptionHandler} in order and
 * uses the first answer; this one is ordered last, so user and Spring Security handlers answer first, and it always
 * answers:
 * <ul>
 * <li>a {@link StatusException} or {@link StatusRuntimeException} passes through unchanged: it is an escape hatch's
 * explicit choice;</li>
 * <li>a {@link SynapseException} is written by the {@link GrpcStatusWriter}, and not logged here (the operation
 * pipeline logged it);</li>
 * <li>anything else is {@code INTERNAL}, logged once at ERROR with its stack trace on
 * {@code io.americanexpress.synapse.api.grpc}, with the call's correlation id in the MDC.</li>
 * </ul>
 * Exception text goes to the log only; the status carries none of it.
 */
public final class SynapseGrpcExceptionHandler implements GrpcExceptionHandler, Ordered {

    private static final Logger LOG = LoggerFactory.getLogger("io.americanexpress.synapse.api.grpc");

    private final GrpcStatusWriter writer;

    /**
     * Creates the handler.
     *
     * @param writer writes Synapse errors as statuses
     */
    public SynapseGrpcExceptionHandler(GrpcStatusWriter writer) {
        this.writer = writer;
    }

    /**
     * Returns the status for the failure.
     *
     * @param failure the failure
     * @return the status, never {@code null}
     */
    @Override
    public StatusException handleException(Throwable failure) {
        if (failure instanceof StatusException status) {
            return status;
        }
        if (failure instanceof StatusRuntimeException status) {
            return new StatusException(status.getStatus(), status.getTrailers());
        }
        GrpcCallState state = GrpcCallState.current();
        if (failure instanceof SynapseException synapse) {
            return writer.write(synapse, state);
        }
        // Resolve the id first, so the logged line carries the id the caller receives.
        GrpcCallState resolved = state == null ? new GrpcCallState(null, Map.of()) : state;
        String correlationId = resolved.correlationId();
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
            resolved.correlationId(correlationId);
        }
        logUnhandled(resolved.operationName(), correlationId, failure);
        return writer.write(new SynapseException(SynapseErrors.INTERNAL), resolved);
    }

    /**
     * Returns the order: last.
     *
     * @return {@link Ordered#LOWEST_PRECEDENCE}
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @SuppressWarnings("try") // The MDC entry is the resource; the body never needs it.
    private static void logUnhandled(@Nullable String operation, String correlationId, Throwable failure) {
        try (MDC.MDCCloseable ignored = correlationMdc(correlationId)) {
            LOG.error("Unhandled error in gRPC call{}", operation == null ? "" : " to " + operation, failure);
        }
    }

    /**
     * Puts the correlation id the caller receives in the MDC when no request context is open. The failure may be
     * handled after the call's context closed (Spring gRPC handles it in the listener of an outer interceptor), and
     * then the MDC entry that tracing maintains is gone.
     *
     * @return the entry to close once the failure is logged, or {@code null} when a context is open (tracing owns the
     *     MDC entry then)
     */
    private static MDC.@Nullable MDCCloseable correlationMdc(String correlationId) {
        return RequestContext.find().isPresent()
                ? null
                : MDC.putCloseable(ContextBinder.CORRELATION_BAGGAGE_FIELD, correlationId);
    }
}
