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
package io.americanexpress.synapse.api.grpc.internal.edge;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.binder.grpc.GrpcServerObservationContext;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.americanexpress.synapse.api.grpc.internal.error.GrpcStatusWriter;
import io.americanexpress.synapse.core.context.RequestContext;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.internal.context.ContextBinder;
import io.americanexpress.synapse.core.internal.context.ContextScope;
import io.americanexpress.synapse.core.internal.context.InboundContext;
import io.americanexpress.synapse.core.observability.SynapseObservations;

/**
 * Opens the Synapse request context for each gRPC call and caps the calls handled at the same time. It runs inside
 * Boot's observation interceptor (order 0) and exception handling interceptor (order 1), so the context's baggage is
 * attached to the call's span and a failure is answered by the exception handlers. For each call it:
 * <ol>
 * <li>reads what the caller sent: the correlation candidate the edge recorded in the {@link GrpcCallState}; each
 * context attribute, in configured order, from its mapped metadata key (first value), else from the trusted baggage
 * candidate; the locale from {@code accept-language} (the first language range by weight), only when present; the
 * deadline from the call's gRPC deadline, through the {@link GrpcDeadlinePolicy};</li>
 * <li>opens the context once ({@link ContextBinder}), which validates or generates the correlation id and applies the
 * trust rule and the limits. It records the resolved id in the state (the edge echoes it) and tags the server
 * observation with it ({@value SynapseObservations#CORRELATION_ID_KEY}), and keeps the attributes that passed;</li>
 * <li>takes a concurrency permit, unless the service is never counted ({@link GrpcConcurrencyLimit#isUncounted}). When
 * none is free, the call is closed with {@code UNAVAILABLE}, code {@code UNAVAILABLE} and a {@code RetryInfo} of 1 s,
 * and nothing behind this interceptor runs;</li>
 * <li>around each listener callback ({@code onMessage}, {@code onHalfClose}, {@code onReady}, {@code onCancel},
 * {@code onComplete}), opens the context again with the resolved id and the kept attributes, and closes it on the same
 * thread when the callback returns or throws. Validation and its warnings happen once per call, not per
 * callback;</li>
 * <li>closes the call inside the call's gRPC {@link Context} when it is closed on a thread where that context is not
 * attached. A hand-written service may complete its call from a thread of its own (a future's callback, a streaming
 * producer), and Spring gRPC's exception handlers, which run when the call is closed, find the call's
 * {@link GrpcCallState} (its correlation id and locale) only through that context;</li>
 * <li>rewrites a status that anything inside it closes the call with (the service, or a server interceptor ordered
 * after this one), other than OK, so that it keeps only its code unless {@code synapse.errors.include-detail} is
 * {@code true} and carries Synapse's {@code ErrorInfo} ({@link GrpcStatusWriter#writeForeign}). {@code UNKNOWN} with a
 * cause that isn't a status ({@code onError} with an exception) is left to the exception handlers.</li>
 * </ol>
 * The permit is released once, when the call completes or is cancelled, or when starting the call throws. grpc-java
 * delivers {@code onCancel} only after the callback running the operation returns, so a cancelled or expired call
 * holds its permit until its handler returns: an operation that blocks should observe the call's gRPC
 * {@link Context} cancellation or its {@link RequestContext} deadline.
 */
public final class SynapseGrpcContextInterceptor implements ServerInterceptor {

    /** The order of this interceptor among the global server interceptors: inside Boot's (0 and 1). */
    public static final int ORDER = 100;

    /** How long a rejected caller is told to wait before retrying. */
    static final Duration RETRY_DELAY = Duration.ofSeconds(1);

    private static final Logger LOG = LoggerFactory.getLogger("io.americanexpress.synapse.api.grpc");

    private static final Metadata.Key<String> ACCEPT_LANGUAGE = Metadata.Key
            .of("accept-language", Metadata.ASCII_STRING_MARSHALLER);

    private final ContextBinder binder;

    private final Map<String, Metadata.Key<String>> attributeKeys;

    private final GrpcDeadlinePolicy deadlines;

    private final GrpcConcurrencyLimit limit;

    private final GrpcStatusWriter writer;

    private final ObservationRegistry observations;

    /**
     * Creates the interceptor.
     *
     * @param binder the binder that opens the context
     * @param attributeKeys the metadata key of each context attribute, keyed by attribute name, in the order they are
     *     read ({@code synapse.api.grpc.context.metadata})
     * @param deadlines the policy that turns the gRPC deadline into the context's deadline
     * @param limit the concurrency limit
     * @param writer writes the status of a rejected call, and rewrites a status the service closes its call with
     * @param observations the registry whose current observation (the gRPC server observation) is tagged with the
     *     correlation id
     */
    public SynapseGrpcContextInterceptor(ContextBinder binder, Map<String, Metadata.Key<String>> attributeKeys,
            GrpcDeadlinePolicy deadlines, GrpcConcurrencyLimit limit, GrpcStatusWriter writer,
            ObservationRegistry observations) {
        this.binder = binder;
        this.attributeKeys = Collections.unmodifiableMap(new LinkedHashMap<>(attributeKeys));
        this.deadlines = deadlines;
        this.limit = limit;
        this.writer = writer;
        this.observations = observations;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        GrpcCallState state = GrpcCallState.current();
        if (state != null) {
            return start(call, headers, next, state);
        }
        // The edge interceptor was replaced by one that records no state: keep one here, with no candidates.
        GrpcCallState own = new GrpcCallState(null, Map.of());
        return Contexts
                .interceptCall(Context.current().withValue(GrpcCallState.KEY, own), call, headers,
                        (inner, innerHeaders) -> start(inner, innerHeaders, next, own));
    }

    private <ReqT, RespT> ServerCall.Listener<ReqT> start(ServerCall<ReqT, RespT> call, Metadata headers,
            ServerCallHandler<ReqT, RespT> next, GrpcCallState state) {
        Locale locale = locale(headers);
        if (locale != null) {
            state.locale(locale);
        }
        Instant deadline = deadlines.deadline(Context.current().getDeadline());
        InboundContext inbound = new InboundContext(state.correlationCandidate(), attributes(headers, state), null,
                locale, deadline);
        try (ContextScope scope = binder.open(inbound)) {
            RequestContext context = scope.context();
            String correlationId = context.correlationId();
            state.correlationId(correlationId);
            Observation observation = observations.getCurrentObservation();
            // Only the call's server observation: an interceptor between Boot's and this one may have opened its own.
            if (observation != null && observation.getContext() instanceof GrpcServerObservationContext) {
                observation.highCardinalityKeyValue(SynapseObservations.CORRELATION_ID_KEY, correlationId);
            }
            Permit permit = null;
            String service = call.getMethodDescriptor().getServiceName();
            if (service == null || !GrpcConcurrencyLimit.isUncounted(service)) {
                if (!limit.tryAcquire()) {
                    return reject(call, state);
                }
                permit = new Permit(limit);
            }
            try {
                // The principal comes from the verified credential, which this edge does not read yet.
                InboundContext resolved = new InboundContext(correlationId, context.attributes(), null, locale,
                        deadline);
                ServerCall<ReqT, RespT> closedInContext = new ContextClosingCall<>(call, Context.current(), state,
                        writer);
                return new ContextListener<>(next.startCall(closedInContext, headers), binder, resolved, permit);
            } catch (RuntimeException | Error ex) {
                if (permit != null) {
                    permit.release();
                }
                throw ex;
            }
        }
    }

    private Map<String, String> attributes(Metadata headers, GrpcCallState state) {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (Map.Entry<String, Metadata.Key<String>> attribute : attributeKeys.entrySet()) {
            String value = MetadataMasking.firstValue(headers, attribute.getValue());
            if (value == null || value.isEmpty()) {
                value = state.baggageCandidates().get(attribute.getKey());
            }
            if (value != null) {
                attributes.put(attribute.getKey(), value);
            }
        }
        return attributes;
    }

    /**
     * The locale of the first language range of {@code accept-language}, by weight; {@code null} when the metadata is
     * absent, malformed or names no language.
     */
    private static @Nullable Locale locale(Metadata headers) {
        String value = MetadataMasking.firstValue(headers, ACCEPT_LANGUAGE);
        if (value == null || value.isBlank()) {
            return null;
        }
        List<Locale.LanguageRange> ranges;
        try {
            ranges = Locale.LanguageRange.parse(value);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        if (ranges.isEmpty()) {
            return null;
        }
        Locale locale = Locale.forLanguageTag(ranges.getFirst().getRange());
        return locale.getLanguage().isEmpty() ? null : locale;
    }

    private <ReqT, RespT> ServerCall.Listener<ReqT> reject(ServerCall<ReqT, RespT> call, GrpcCallState state) {
        LOG
                .debug("Rejected {}: the concurrent call limit is reached",
                        call.getMethodDescriptor().getFullMethodName());
        StatusException rejection = writer.write(new SynapseException(SynapseErrors.UNAVAILABLE), state, RETRY_DELAY);
        Metadata trailers = rejection.getTrailers();
        call.close(rejection.getStatus(), trailers == null ? new Metadata() : trailers);
        return new ServerCall.Listener<>() {
        };
    }

    /** One call's permit, released exactly once. */
    private static final class Permit {

        private final GrpcConcurrencyLimit limit;

        private final AtomicBoolean released = new AtomicBoolean();

        Permit(GrpcConcurrencyLimit limit) {
            this.limit = limit;
        }

        void release() {
            if (released.compareAndSet(false, true)) {
                limit.release();
            }
        }
    }

    /**
     * Closes the call inside the call's gRPC context when it is closed on a thread where that context is not attached.
     * On the call's own threads (every callback, and so every bound method) it closes the call directly.
     * <p>
     * Only what runs behind this interceptor closes the call through here: a hand-written service, with
     * {@code onCompleted} or {@code onError}; a server interceptor ordered after this one ({@link #ORDER}), which
     * includes any interceptor without an order; and grpc-java's own {@code ServerCalls} ({@code INTERNAL} for a
     * protocol misuse such as a half-close without a request). Synapse's own statuses close the outer call (Spring
     * gRPC's exception handling call answers a thrown failure, and the concurrency limit rejects a call before it
     * starts; an unknown method never reaches the interceptors). So every non-OK status that reaches here is not
     * Synapse's, typically a downstream call's failure a service forwards, and is rewritten by
     * {@link GrpcStatusWriter#writeForeign} (decision 24): an inner interceptor's status keeps only its code too, while
     * one ordered before this interceptor closes the outer call and keeps its description. The exception is
     * {@code UNKNOWN} with a cause that isn't a status: that is {@code onError} with an exception, whose cause Spring
     * gRPC's exception handling call hands to the exception handlers; its trailers are kept only under include-detail
     * ({@link GrpcStatusWriter#foreignTrailers}), since Spring keeps those trailers next to the handler's. Spring would
     * hand the exception handlers the cause of any {@code UNKNOWN}; rewriting one whose cause is a status here, with
     * the code {@code UNKNOWN}, is Synapse's choice, so that Synapse's handler never unwraps the inner code.
     */
    private static final class ContextClosingCall<ReqT, RespT>
            extends
                ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT> {

        private final Context context;

        private final GrpcCallState state;

        private final GrpcStatusWriter writer;

        ContextClosingCall(ServerCall<ReqT, RespT> delegate, Context context, GrpcCallState state,
                GrpcStatusWriter writer) {
            super(delegate);
            this.context = context;
            this.state = state;
            this.writer = writer;
        }

        @Override
        public void close(Status status, Metadata trailers) {
            if (GrpcCallState.current() == state) {
                closeAsTheService(status, trailers);
            } else {
                context.run(() -> closeAsTheService(status, trailers));
            }
        }

        private void closeAsTheService(Status status, Metadata trailers) {
            if (status.isOk()) {
                super.close(status, trailers);
                return;
            }
            if (isForTheHandlers(status)) {
                // Spring gRPC's exception handling call merges the handler's trailers into these and keeps them, so
                // they follow the same include-detail rule as a rewritten status's.
                super.close(status, writer.foreignTrailers(trailers));
                return;
            }
            StatusException rewritten = writer.writeForeign(status, trailers, state);
            Metadata written = rewritten.getTrailers();
            // Never null: StatusProto.toStatusException(status, metadata) always carries the trailers it was given.
            // StatusException declares them nullable, so the check is for NullAway only.
            super.close(rewritten.getStatus(), written == null ? new Metadata() : written);
        }

        /** {@code UNKNOWN} with a cause that isn't a status, which Spring gRPC hands to the exception handlers. */
        private static boolean isForTheHandlers(Status status) {
            Throwable cause = status.getCause();
            return status.getCode() == Status.Code.UNKNOWN && cause != null
                    && !(cause instanceof StatusException) && !(cause instanceof StatusRuntimeException);
        }
    }

    /**
     * Opens the call's context around each callback, on the callback's thread, and releases the call's permit when it
     * completes or is cancelled.
     */
    @SuppressWarnings("try") // The scope is the resource; the callbacks never need it.
    private static final class ContextListener<ReqT>
            extends
                ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT> {

        private final ContextBinder binder;

        private final InboundContext resolved;

        private final @Nullable Permit permit;

        ContextListener(ServerCall.Listener<ReqT> delegate, ContextBinder binder, InboundContext resolved,
                @Nullable Permit permit) {
            super(delegate);
            this.binder = binder;
            this.resolved = resolved;
            this.permit = permit;
        }

        @Override
        public void onMessage(ReqT message) {
            try (ContextScope ignored = binder.open(resolved)) {
                super.onMessage(message);
            }
        }

        @Override
        public void onHalfClose() {
            try (ContextScope ignored = binder.open(resolved)) {
                super.onHalfClose();
            }
        }

        @Override
        public void onReady() {
            try (ContextScope ignored = binder.open(resolved)) {
                super.onReady();
            }
        }

        @Override
        public void onCancel() {
            try (ContextScope ignored = binder.open(resolved)) {
                super.onCancel();
            } finally {
                release();
            }
        }

        @Override
        public void onComplete() {
            try (ContextScope ignored = binder.open(resolved)) {
                super.onComplete();
            } finally {
                release();
            }
        }

        private void release() {
            if (permit != null) {
                permit.release();
            }
        }
    }
}
