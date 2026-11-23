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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.observability.BusinessObservation;
import io.americanexpress.synapse.core.observability.SynapseObservations;
import io.americanexpress.synapse.core.operation.BusinessOperation;

/**
 * Runs the reduced pipeline around {@code BusinessOperation.execute}: the {@value SynapseObservations#BUSINESS}
 * observation (or timer, by {@link BusinessObservation} mode), then validation. There is no context, deadline,
 * translation or logging step: exceptions propagate unchanged. A rejected input is {@code INTERNAL}, with the
 * {@code VALIDATION_FAILED} as its cause: the service builds a business input, so its violations are never the
 * caller's to see. Each call's rejection records a token of its own, so an operation that unwraps the
 * {@code VALIDATION_FAILED} and rethrows it still fails with {@code INTERNAL} (a rejection raised by another call).
 */
final class BusinessInterceptor implements MethodInterceptor {

    private final OperationPipeline pipeline;

    private final ObservationRegistry observationRegistry;

    private final @Nullable MeterRegistry meterRegistry;

    private final BusinessObservation mode;

    BusinessInterceptor(OperationPipeline pipeline, ObservationRegistry observationRegistry,
            @Nullable MeterRegistry meterRegistry, BusinessObservation mode) {
        this.pipeline = pipeline;
        this.observationRegistry = observationRegistry;
        this.meterRegistry = meterRegistry;
        this.mode = mode;
    }

    @Override
    public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
        Object target = invocation.getThis();
        if (!(target instanceof BusinessOperation<?, ?>)
                || !pipeline.isBusinessExecute(target.getClass(), invocation.getMethod())) {
            return invocation.proceed();
        }
        String name = pipeline.businessDescriptor(target.getClass()).name();
        return switch (mode) {
            case SPANS -> observed(invocation, name);
            case METRICS -> meterRegistry == null ? execute(invocation) : timed(invocation, name, meterRegistry);
            case OFF -> execute(invocation);
        };
    }

    private @Nullable Object execute(MethodInvocation invocation) throws Throwable {
        try {
            // A token of this call's own, so a caller that unwraps the rejection and rethrows it gets INTERNAL.
            pipeline.validate(invocation.getArguments()[0], new Object());
        } catch (SynapseException rejected) {
            // The service built this input, so a violation is its bug, never the caller's.
            throw new SynapseException(SynapseErrors.INTERNAL, rejected);
        }
        return invocation.proceed();
    }

    private @Nullable Object observed(MethodInvocation invocation, String name) throws Throwable {
        Observation observation = Observation
                .createNotStarted(SynapseObservations.BUSINESS, observationRegistry)
                .contextualName(name)
                .lowCardinalityKeyValue(SynapseObservations.BUSINESS_KEY, name)
                .start();
        Throwable failure = null;
        try (Observation.Scope scope = observation.openScope()) {
            return execute(invocation);
        } catch (Throwable thrown) {
            failure = thrown;
            observation.error(thrown);
            throw thrown;
        } finally {
            observation
                    .lowCardinalityKeyValue(SynapseObservations.OUTCOME_KEY, outcome(failure))
                    .lowCardinalityKeyValue(SynapseObservations.ERROR_TYPE_KEY, errorType(failure));
            observation.stop();
        }
    }

    private @Nullable Object timed(MethodInvocation invocation, String name, MeterRegistry registry)
            throws Throwable {
        Timer.Sample sample = Timer.start(registry);
        Throwable failure = null;
        try {
            return execute(invocation);
        } catch (Throwable thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            sample
                    .stop(Timer
                            .builder(SynapseObservations.BUSINESS)
                            .tag(SynapseObservations.BUSINESS_KEY, name)
                            .tag(SynapseObservations.OUTCOME_KEY, outcome(failure))
                            .tag(SynapseObservations.ERROR_TYPE_KEY, errorType(failure))
                            .register(registry));
        }
    }

    private static String outcome(@Nullable Throwable failure) {
        return failure == null ? SynapseObservations.OUTCOME_SUCCESS : SynapseObservations.OUTCOME_ERROR;
    }

    /**
     * Returns the {@value SynapseObservations#ERROR_TYPE_KEY} value. Business exceptions are not translated, so only a
     * {@link SynapseException} has a code; a JVM {@link Error} and any other exception get fixed values, which keeps
     * the key bounded.
     */
    private static String errorType(@Nullable Throwable failure) {
        if (failure == null) {
            return SynapseObservations.NONE;
        }
        if (failure instanceof SynapseException synapse) {
            return synapse.code();
        }
        return failure instanceof Error ? SynapseObservations.JVM_ERROR : SynapseObservations.OTHER_ERROR;
    }
}
