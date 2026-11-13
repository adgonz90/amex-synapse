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
package io.americanexpress.synapse.core.internal.pipeline;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;

import io.americanexpress.synapse.core.context.RequestContext;
import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.ExceptionTranslator;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.internal.context.ContextBinder;
import io.americanexpress.synapse.core.internal.context.ContextScope;
import io.americanexpress.synapse.core.internal.context.InboundContext;
import io.americanexpress.synapse.core.masking.LogMasking;
import io.americanexpress.synapse.core.observability.DefaultOperationObservationConvention;
import io.americanexpress.synapse.core.observability.OperationObservationContext;
import io.americanexpress.synapse.core.observability.OperationObservationConvention;
import io.americanexpress.synapse.core.observability.SynapseObservations;
import io.americanexpress.synapse.core.operation.ServiceOperation;

/**
 * Runs the operation pipeline around {@code ServiceOperation.execute}, outermost step first: observation, context,
 * in-flight tracking (outermost call on the thread only), deadline, validation, payload logging, execution, then
 * translation and failure logging (outermost call on the thread only).
 *
 * <p>
 * Admission while draining: an outermost call that finds no request context (the pipeline opens one, as for
 * {@code @Scheduled} methods) is admitted with {@link InflightTracker#tryEnter()} and rejected with
 * {@code UNAVAILABLE} once draining starts. An outermost call that finds a context (opened by an inbound edge, or
 * inherited from an admitted request on another thread) is counted with {@link InflightTracker#enter()} and never
 * rejected, so the async fan-out of an admitted request completes during the drain.
 *
 * <p>
 * Releasing the call's resources never replaces its outcome: a failure while exiting the tracker, closing the
 * context or observation scope, or stopping the observation is logged at WARN and the result or the recorded
 * {@code SynapseException} is still returned or thrown.
 */
final class OperationInterceptor implements MethodInterceptor {

    private static final Logger FAILURES = LoggerFactory.getLogger("io.americanexpress.synapse.operation");

    /** A sibling of the failure logger, not a child, so filters on the failure logger never apply to payloads. */
    private static final Logger PAYLOADS = LoggerFactory.getLogger("io.americanexpress.synapse.payload");

    private static final Logger LOGGER = LoggerFactory.getLogger(OperationInterceptor.class);

    private static final OperationObservationConvention DEFAULT_CONVENTION = new DefaultOperationObservationConvention();

    /** Operation calls on the current thread; deliberately not propagated, so each thread has its own outermost. */
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private final OperationPipeline pipeline;

    private final Clock clock;

    private final List<ExceptionTranslator> translators;

    private final ObservationRegistry observationRegistry;

    private final @Nullable OperationObservationConvention convention;

    private final ContextBinder binder;

    private final Predicate<String> payloadLogging;

    private final LogMasking logMasking;

    private final InflightTracker inflight;

    OperationInterceptor(OperationPipeline pipeline, Clock clock, List<ExceptionTranslator> translators,
            ObservationRegistry observationRegistry, @Nullable OperationObservationConvention convention,
            ContextBinder binder, Predicate<String> payloadLogging, LogMasking logMasking, InflightTracker inflight) {
        this.pipeline = pipeline;
        this.clock = clock;
        this.translators = translators;
        this.observationRegistry = observationRegistry;
        this.convention = convention;
        this.binder = binder;
        this.payloadLogging = payloadLogging;
        this.logMasking = logMasking;
        this.inflight = inflight;
    }

    /** Returns the number of operation calls in progress on the current thread. */
    static int depth() {
        return DEPTH.get()[0];
    }

    @Override
    public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
        Object target = invocation.getThis();
        if (!(target instanceof ServiceOperation<?, ?>)
                || !pipeline.isOperationExecute(target.getClass(), invocation.getMethod())) {
            return invocation.proceed();
        }
        OperationDescriptor descriptor = pipeline.operationDescriptor(target.getClass());
        String name = descriptor.name();
        Object input = invocation.getArguments()[0];

        int[] depth = DEPTH.get();
        int previousDepth = depth[0];
        boolean outermost = previousDepth == 0;

        OperationObservationContext observationContext = new OperationObservationContext(name,
                Objects.requireNonNull(descriptor.kind(), "kind"));
        Observation observation = Observation
                .createNotStarted(convention, DEFAULT_CONVENTION, () -> observationContext, observationRegistry)
                .start();
        Observation.@Nullable Scope observationScope = null;
        ContextScope contextScope = null;
        boolean entered = false;
        String correlationId = null;
        depth[0] = previousDepth + 1;
        try {
            observationScope = observation.openScope();

            Optional<RequestContext> current = RequestContext.find();
            RequestContext context;
            if (current.isPresent()) {
                context = current.get();
            } else {
                contextScope = binder.open(InboundContext.empty());
                context = contextScope.context();
            }
            correlationId = context.correlationId();
            observation.highCardinalityKeyValue(SynapseObservations.CORRELATION_ID_KEY, correlationId);

            if (outermost) {
                if (contextScope == null) {
                    // An edge opened the context, or it was inherited from admitted work: count, never reject.
                    inflight.enter();
                    entered = true;
                } else {
                    entered = inflight.tryEnter();
                    if (!entered) {
                        throw new SynapseException(SynapseErrors.UNAVAILABLE);
                    }
                }
            }

            if (context.remaining(clock).filter(Duration::isZero).isPresent()) {
                throw new SynapseException(SynapseErrors.DEADLINE_EXCEEDED);
            }

            pipeline.validate(input);

            boolean logPayloads = PAYLOADS.isDebugEnabled() && payloadLogging.test(name);
            if (logPayloads) {
                logPayload("input", name, correlationId, input);
            }
            Object result = invocation.proceed();
            if (logPayloads) {
                logPayload("output", name, correlationId, result instanceof Optional<?> optional
                        ? optional.orElse(null)
                        : result);
            }
            return result;
        } catch (Error error) {
            // JVM errors are never translated or logged here; the observation still records them.
            observation.error(error);
            throw error;
        } catch (Throwable thrown) {
            SynapseException failure = translate(thrown);
            observation.error(failure);
            if (outermost) {
                logFailure(name, correlationId, failure);
            }
            throw failure;
        } finally {
            if (outermost) {
                DEPTH.remove();
            } else {
                depth[0] = previousDepth;
            }
            release(name, entered, contextScope, observationScope, observation);
        }
    }

    /**
     * Releases everything the call acquired, in reverse order. A {@code RuntimeException} from one step is logged and
     * swallowed so it never replaces the call's outcome; the nested {@code finally} blocks run the remaining steps
     * even if a step throws an {@link Error}.
     */
    private void release(String name, boolean entered, @Nullable ContextScope contextScope,
            Observation.@Nullable Scope observationScope, Observation observation) {
        try {
            if (entered) {
                quietly(name, "in-flight count", inflight::exit);
            }
        } finally {
            try {
                if (contextScope != null) {
                    quietly(name, "context scope", contextScope::close);
                }
            } finally {
                try {
                    if (observationScope != null) {
                        quietly(name, "observation scope", observationScope::close);
                    }
                } finally {
                    quietly(name, "observation", observation::stop);
                }
            }
        }
    }

    private static void quietly(String name, String resource, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException failure) {
            FAILURES
                    .atWarn()
                    .setCause(failure)
                    .addKeyValue("operation", name)
                    .log("Operation {} could not release its {}", name, resource);
        }
    }

    private SynapseException translate(Throwable thrown) {
        if (thrown instanceof SynapseException synapse) {
            return synapse;
        }
        for (ExceptionTranslator translator : translators) {
            try {
                SynapseException translated = translator.translate(thrown);
                if (translated != null) {
                    return translated;
                }
            } catch (RuntimeException broken) {
                LOGGER
                        .atWarn()
                        .setCause(broken)
                        .log("Exception translator {} failed; trying the next one", translator.getClass().getName());
            }
        }
        return new SynapseException(SynapseErrors.INTERNAL, thrown);
    }

    /**
     * Logs the failure: ERROR with the stack trace for {@code INTERNAL}; WARN with the cause's stack trace for
     * {@code UNAVAILABLE} and {@code TIMEOUT} when a translator kept a cause (a downstream fault, not the caller's);
     * WARN without a stack trace otherwise, which includes the pipeline's own drain rejection and deadline check.
     * <p>
     * The code and category keys have no dot: Spring Boot's ECS format nests dotted keys, and it writes the logged
     * throwable as the {@code error} object, so a key under {@code error.} would be a second {@code error} member and
     * the writer would drop the whole line.
     * <p>
     * The line always carries the correlation id, but as a key-value only when the MDC doesn't hold it (see
     * {@link #withCorrelationId}).
     */
    private static void logFailure(String name, @Nullable String correlationId, SynapseException failure) {
        ErrorCategory category = failure.category();
        Throwable cause = failure.getCause();
        LoggingEventBuilder event;
        if (category == ErrorCategory.INTERNAL) {
            event = FAILURES.atError().setCause(failure);
        } else if ((category == ErrorCategory.UNAVAILABLE || category == ErrorCategory.TIMEOUT) && cause != null) {
            event = FAILURES.atWarn().setCause(cause);
        } else {
            event = FAILURES.atWarn();
        }
        withCorrelationId(event, correlationId)
                .addKeyValue("operation", name)
                .addKeyValue("errorCode", failure.code())
                .addKeyValue("errorCategory", failure.category().name())
                .log("Operation {} failed with {} ({}): {}", name, failure.code(), failure.category(),
                        failure.getMessage());
    }

    /** Logs a payload, with the correlation id as the failure line has it. */
    private void logPayload(String direction, String name, @Nullable String correlationId, @Nullable Object payload) {
        withCorrelationId(PAYLOADS.atDebug(), correlationId)
                .addKeyValue("operation", name)
                .log("Operation {} {}: {}", name, direction, logMasking.toJson(payload));
    }

    /**
     * Adds the correlation id as a key-value only when the MDC doesn't hold it. With tracing on, Boot's correlation
     * fields put it in the MDC, and Boot's Logback structured formats write the MDC and the key-values into one
     * object, so a key-value of the same name would make the encoder throw and the line would be lost. Without it in
     * the MDC (tracing or baggage off, or a thread the MDC wasn't propagated to), the key-value carries it, so a line
     * always has the correlation id. No other key-value here may share a name with an MDC entry.
     */
    private static LoggingEventBuilder withCorrelationId(LoggingEventBuilder event, @Nullable String correlationId) {
        if (correlationId != null && MDC.get(ContextBinder.CORRELATION_BAGGAGE_FIELD) == null) {
            return event.addKeyValue(ContextBinder.CORRELATION_BAGGAGE_FIELD, correlationId);
        }
        return event;
    }
}
