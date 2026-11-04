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

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.aopalliance.intercept.MethodInterceptor;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import io.americanexpress.synapse.core.error.ExceptionTranslator;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.core.internal.context.ContextBinder;
import io.americanexpress.synapse.core.internal.context.CorrelationIdPolicy;
import io.americanexpress.synapse.core.masking.LogMasking;
import io.americanexpress.synapse.core.observability.BusinessObservation;
import io.americanexpress.synapse.core.observability.OperationObservationConvention;
import io.americanexpress.synapse.core.operation.BusinessOperation;
import io.americanexpress.synapse.core.operation.ServiceOperation;
import io.americanexpress.synapse.core.paging.PageQuery;

/**
 * The invocation pipeline (BUILD.md §5.2): the interceptor applied to every {@link ServiceOperation} and the reduced
 * interceptor applied to every {@link BusinessOperation}. Auto-configuration installs the interceptors as AOP
 * advisors; {@link #wrapOperation(Object)} and {@link #wrapBusiness(Object)} apply them to plain instances (tests).
 */
public final class OperationPipeline {

    private static final int DEFAULT_MAX_PAGE_SIZE = 100;

    private static final int DEFAULT_CORRELATION_MAX_LENGTH = 128;

    private static final int DEFAULT_MAX_CONTEXT_ENTRIES = 16;

    private static final long DEFAULT_MAX_CONTEXT_BYTES = 4096;

    private static final Method SERVICE_EXECUTE = Objects
            .requireNonNull(ReflectionUtils.findMethod(ServiceOperation.class, "execute", Object.class));

    private static final Method BUSINESS_EXECUTE = Objects
            .requireNonNull(ReflectionUtils.findMethod(BusinessOperation.class, "execute", Object.class));

    private final Validator validator;

    private final int maxPageSize;

    private final Map<Class<?>, Boolean> constrained = new ConcurrentHashMap<>();

    private final Map<Class<?>, List<Method>> pageAccessors = new ConcurrentHashMap<>();

    private final Map<Class<?>, OperationDescriptor> operations = new ConcurrentHashMap<>();

    private final Map<Class<?>, OperationDescriptor> businesses = new ConcurrentHashMap<>();

    private final Map<ExecuteKey, Boolean> executeMethods = new ConcurrentHashMap<>();

    private final OperationInterceptor operationInterceptor;

    private final BusinessInterceptor businessInterceptor;

    private OperationPipeline(Builder builder) {
        this.validator = builder.validator != null
                ? builder.validator
                : Validation.buildDefaultValidatorFactory().getValidator();
        this.maxPageSize = builder.maxPageSize;
        ContextBinder binder = builder.contextBinder != null
                ? builder.contextBinder
                : new ContextBinder(Tracer.NOOP, new CorrelationIdPolicy(DEFAULT_CORRELATION_MAX_LENGTH, builder.clock),
                        true, DEFAULT_MAX_CONTEXT_ENTRIES, DEFAULT_MAX_CONTEXT_BYTES, builder.clock);
        this.operationInterceptor = new OperationInterceptor(this, builder.clock, builder.translators,
                builder.observationRegistry, builder.observationConvention, binder, builder.payloadLogging,
                builder.logMasking, builder.inflight);
        this.businessInterceptor = new BusinessInterceptor(this, builder.observationRegistry, builder.meterRegistry,
                builder.businessObservation);
    }

    /**
     * Starts building a pipeline. Every setting has a default, so {@code builder().build()} is a working pipeline.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the interceptor for {@link ServiceOperation} beans. It runs the pipeline for {@code execute(I)} and
     * passes every other method straight through.
     *
     * @return the interceptor
     */
    public MethodInterceptor operationInterceptor() {
        return operationInterceptor;
    }

    /**
     * Returns the interceptor for {@link BusinessOperation} beans. It runs the reduced pipeline (validation and
     * observation) for {@code execute(I)} and passes every other method straight through.
     *
     * @return the interceptor
     */
    public MethodInterceptor businessInterceptor() {
        return businessInterceptor;
    }

    /**
     * Wraps an operation instance in a class-based (CGLIB) proxy that runs the pipeline, so it can still be used as
     * its concrete type.
     *
     * @param <T> the operation type
     * @param target the operation; its class must not be final
     * @return the proxy
     * @throws IllegalArgumentException if the target is not a {@link ServiceOperation}
     */
    public <T> T wrapOperation(T target) {
        if (!(target instanceof ServiceOperation<?, ?>)) {
            throw new IllegalArgumentException(target.getClass().getName() + " is not a ServiceOperation");
        }
        operationDescriptor(target.getClass());
        return proxy(target, operationInterceptor);
    }

    /**
     * Wraps a business operation instance in a class-based (CGLIB) proxy that runs the reduced pipeline.
     *
     * @param <T> the business operation type
     * @param target the business operation; its class must not be final
     * @return the proxy
     * @throws IllegalArgumentException if the target is not a {@link BusinessOperation}
     */
    public <T> T wrapBusiness(T target) {
        if (!(target instanceof BusinessOperation<?, ?>)) {
            throw new IllegalArgumentException(target.getClass().getName() + " is not a BusinessOperation");
        }
        businessDescriptor(target.getClass());
        return proxy(target, businessInterceptor);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(T target, MethodInterceptor interceptor) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        return (T) factory.getProxy(ClassUtils.getDefaultClassLoader());
    }

    OperationDescriptor operationDescriptor(Class<?> type) {
        return operations.computeIfAbsent(ClassUtils.getUserClass(type), OperationDescriptor::ofOperation);
    }

    OperationDescriptor businessDescriptor(Class<?> type) {
        return businesses.computeIfAbsent(ClassUtils.getUserClass(type), OperationDescriptor::ofBusiness);
    }

    /**
     * Returns whether the invoked method is the target class's implementation of {@code ServiceOperation.execute}.
     * Overloads such as {@code execute(Other)} do not match; the bridge method of a generic implementation does.
     */
    boolean isOperationExecute(Class<?> targetClass, Method method) {
        return isContractExecute(targetClass, method, SERVICE_EXECUTE);
    }

    /**
     * Returns whether the invoked method is the target class's implementation of {@code BusinessOperation.execute}.
     */
    boolean isBusinessExecute(Class<?> targetClass, Method method) {
        return isContractExecute(targetClass, method, BUSINESS_EXECUTE);
    }

    private boolean isContractExecute(Class<?> targetClass, Method method, Method contract) {
        if (!method.getName().equals(contract.getName()) || method.getParameterCount() != 1) {
            return false;
        }
        return executeMethods
                .computeIfAbsent(new ExecuteKey(ClassUtils.getUserClass(targetClass), method, contract),
                        key -> implementation(key.type(), key.contract())
                                .equals(implementation(key.type(), key.method())));
    }

    /** Resolves a method to the user-declared method that implements it in the class, looking through bridges. */
    private static Method implementation(Class<?> type, Method method) {
        return BridgeMethodResolver.findBridgedMethod(ClassUtils.getMostSpecificMethod(method, type));
    }

    private record ExecuteKey(Class<?> type, Method method, Method contract) {
    }

    /**
     * Validates the input: Jakarta constraints (only for constrained types), then the paging rules of a
     * {@link PageQuery} input or of the input record's {@code PageQuery} components.
     *
     * @throws SynapseException {@code VALIDATION_FAILED} with every violation found
     */
    void validate(@Nullable Object input) {
        if (input == null) {
            return;
        }
        Class<?> type = input.getClass();
        List<Violation> violations = new ArrayList<>();
        if (constrained.computeIfAbsent(type, this::isConstrained)) {
            List<Violation> constraints = new ArrayList<>();
            for (ConstraintViolation<Object> violation : validator.validate(input)) {
                constraints.add(new Violation(violation.getPropertyPath().toString(), violation.getMessage()));
            }
            constraints.sort(Comparator.comparing(Violation::field).thenComparing(Violation::message));
            violations.addAll(constraints);
        }
        if (input instanceof PageQuery page) {
            violations.addAll(page.violations(maxPageSize));
        }
        for (Method accessor : pageAccessors.computeIfAbsent(type, OperationPipeline::pageAccessors)) {
            PageQuery page = (PageQuery) ReflectionUtils.invokeMethod(accessor, input);
            if (page != null) {
                for (Violation violation : page.violations(maxPageSize)) {
                    violations.add(new Violation(accessor.getName() + "." + violation.field(), violation.message()));
                }
            }
        }
        if (!violations.isEmpty()) {
            throw SynapseException.ofViolations(SynapseErrors.VALIDATION_FAILED, violations);
        }
    }

    private boolean isConstrained(Class<?> type) {
        return validator.getConstraintsForClass(type).isBeanConstrained();
    }

    private static List<Method> pageAccessors(Class<?> type) {
        RecordComponent[] components = type.getRecordComponents();
        if (components == null) {
            return List.of();
        }
        List<Method> accessors = new ArrayList<>();
        for (RecordComponent component : components) {
            if (component.getType() == PageQuery.class) {
                Method accessor = component.getAccessor();
                ReflectionUtils.makeAccessible(accessor);
                accessors.add(accessor);
            }
        }
        return List.copyOf(accessors);
    }

    /**
     * Builds an {@link OperationPipeline}.
     */
    public static final class Builder {

        private Clock clock = Clock.systemUTC();

        private @Nullable Validator validator;

        private List<ExceptionTranslator> translators = List.of();

        private ObservationRegistry observationRegistry = ObservationRegistry.NOOP;

        private @Nullable MeterRegistry meterRegistry;

        private @Nullable ContextBinder contextBinder;

        private int maxPageSize = DEFAULT_MAX_PAGE_SIZE;

        private Predicate<String> payloadLogging = name -> false;

        private LogMasking logMasking = LogMasking.standard();

        private BusinessObservation businessObservation = BusinessObservation.SPANS;

        private InflightTracker inflight = new InflightTracker();

        private @Nullable OperationObservationConvention observationConvention;

        private Builder() {
        }

        /**
         * Sets the clock deadlines are checked against. Default: {@link Clock#systemUTC()}.
         *
         * @param clock the clock
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * Sets the Jakarta validator. Default: the provider's default validator.
         *
         * @param validator the validator
         * @return this builder
         */
        public Builder validator(Validator validator) {
            this.validator = Objects.requireNonNull(validator, "validator");
            return this;
        }

        /**
         * Sets the exception translators, tried in order; the first non-null translation wins. Default: none.
         *
         * @param translators the translators
         * @return this builder
         */
        public Builder translators(List<ExceptionTranslator> translators) {
            this.translators = List.copyOf(translators);
            return this;
        }

        /**
         * Sets the observation registry. Default: {@link ObservationRegistry#NOOP}.
         *
         * @param observationRegistry the registry
         * @return this builder
         */
        public Builder observationRegistry(ObservationRegistry observationRegistry) {
            this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry");
            return this;
        }

        /**
         * Sets the meter registry used for business timers in {@link BusinessObservation#METRICS} mode. Default:
         * none (no business metrics).
         *
         * @param meterRegistry the registry, or {@code null}
         * @return this builder
         */
        public Builder meterRegistry(@Nullable MeterRegistry meterRegistry) {
            this.meterRegistry = meterRegistry;
            return this;
        }

        /**
         * Sets the binder that opens a context when a call has none. Default: a binder with {@link Tracer#NOOP} and
         * the default limits.
         *
         * @param contextBinder the binder
         * @return this builder
         */
        public Builder contextBinder(ContextBinder contextBinder) {
            this.contextBinder = Objects.requireNonNull(contextBinder, "contextBinder");
            return this;
        }

        /**
         * Sets the largest page size a {@link PageQuery} may ask for. Default: 100.
         *
         * @param maxPageSize the largest page size, at least 1
         * @return this builder
         * @throws IllegalArgumentException if {@code maxPageSize} is less than 1
         */
        public Builder maxPageSize(int maxPageSize) {
            if (maxPageSize < 1) {
                throw new IllegalArgumentException("maxPageSize must be at least 1, was " + maxPageSize);
            }
            this.maxPageSize = maxPageSize;
            return this;
        }

        /**
         * Sets which operations log their input and output (at DEBUG, masked). Default: none.
         *
         * @param operationNamesWithPayloads tests an operation name
         * @return this builder
         */
        public Builder payloadLogging(Predicate<String> operationNamesWithPayloads) {
            this.payloadLogging = Objects.requireNonNull(operationNamesWithPayloads, "operationNamesWithPayloads");
            return this;
        }

        /**
         * Sets the masking used for payload log lines. Default: {@link LogMasking#standard()}.
         *
         * @param logMasking the masking
         * @return this builder
         */
        public Builder logMasking(LogMasking logMasking) {
            this.logMasking = Objects.requireNonNull(logMasking, "logMasking");
            return this;
        }

        /**
         * Sets how business operation calls are observed. Default: {@link BusinessObservation#SPANS}.
         *
         * @param businessObservation the mode
         * @return this builder
         */
        public Builder businessObservation(BusinessObservation businessObservation) {
            this.businessObservation = Objects.requireNonNull(businessObservation, "businessObservation");
            return this;
        }

        /**
         * Sets the in-flight tracker. Default: a new tracker.
         *
         * @param inflight the tracker
         * @return this builder
         */
        public Builder inflight(InflightTracker inflight) {
            this.inflight = Objects.requireNonNull(inflight, "inflight");
            return this;
        }

        /**
         * Sets a convention that replaces the default operation observation convention. Default: none (the
         * {@code DefaultOperationObservationConvention} applies).
         *
         * @param observationConvention the convention, or {@code null}
         * @return this builder
         */
        public Builder observationConvention(@Nullable OperationObservationConvention observationConvention) {
            this.observationConvention = observationConvention;
            return this;
        }

        /**
         * Builds the pipeline.
         *
         * @return the pipeline
         */
        public OperationPipeline build() {
            return new OperationPipeline(this);
        }
    }
}
