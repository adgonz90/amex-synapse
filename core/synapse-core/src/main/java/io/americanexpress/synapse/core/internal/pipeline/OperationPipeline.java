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
import java.util.Set;
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

import io.americanexpress.synapse.core.conditional.VersionCondition;
import io.americanexpress.synapse.core.error.ExceptionTranslator;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.core.internal.binding.ResolvedComponent;
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

    private static final String VERSION_REQUIRED = "is required; pass the version read with the resource, or"
            + " VersionCondition.any()";

    private static final Method SERVICE_EXECUTE = Objects
            .requireNonNull(ReflectionUtils.findMethod(ServiceOperation.class, "execute", Object.class));

    private static final Method BUSINESS_EXECUTE = Objects
            .requireNonNull(ReflectionUtils.findMethod(BusinessOperation.class, "execute", Object.class));

    private final Validator validator;

    private final int maxPageSize;

    private final Map<Class<?>, Boolean> constrained = new ConcurrentHashMap<>();

    private final Map<Class<?>, List<Method>> pageAccessors = new ConcurrentHashMap<>();

    private final Map<Class<?>, List<RecordComponent>> versionComponents = new ConcurrentHashMap<>();

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
     * @param input the input
     * @param call the token of the call that validates it, recorded on the rejection
     * @throws SynapseException {@code VALIDATION_FAILED} with every violation found; its cause is a
     *         {@link SanitizedViolations} with each violation's path and message template, for the log of a
     *         rejection that isn't the caller's (a business or nested input), and the call's token
     */
    void validate(@Nullable Object input, Object call) {
        if (input == null) {
            return;
        }
        Class<?> type = input.getClass();
        List<Violation> violations = new ArrayList<>();
        Set<ConstraintViolation<Object>> found = Set.of();
        if (constrained.computeIfAbsent(type, this::isConstrained)) {
            found = validator.validate(input);
            List<Violation> constraints = new ArrayList<>();
            for (ConstraintViolation<Object> violation : found) {
                constraints.add(new Violation(ViolationPaths.of(violation.getPropertyPath()), violation.getMessage()));
            }
            constraints.sort(Comparator.comparing(Violation::field).thenComparing(Violation::message));
            violations.addAll(constraints);
        }
        List<Violation> paging = new ArrayList<>();
        if (input instanceof PageQuery page) {
            paging.addAll(page.violations(maxPageSize));
        }
        for (Method accessor : pageAccessors.computeIfAbsent(type, OperationPipeline::pageAccessors)) {
            PageQuery page = (PageQuery) ReflectionUtils.invokeMethod(accessor, input);
            if (page != null) {
                for (Violation violation : page.violations(maxPageSize)) {
                    paging.add(new Violation(accessor.getName() + "." + violation.field(), violation.message()));
                }
            }
        }
        violations.addAll(paging);
        if (!violations.isEmpty()) {
            throw rejected(SynapseErrors.VALIDATION_FAILED, violations, found, paging, call);
        }
    }

    /**
     * The pipeline's rejection, caused by its sanitized form (paging texts never repeat a value), which records the
     * call that raised it.
     */
    private static SynapseException rejected(SynapseErrors error, List<Violation> violations,
            Set<? extends ConstraintViolation<?>> constraints, List<Violation> fixed, Object call) {
        SynapseException rejection = SynapseException.ofViolations(error, violations);
        SanitizedViolations cause = SanitizedViolations
                .ofRejection(constraints,
                        fixed.stream().map(violation -> violation.field() + ": " + violation.message()).toList());
        cause.markCall(call);
        rejection.initCause(cause);
        return rejection;
    }

    /**
     * Tells whether a failure is a pipeline rejection raised by another call than this one: its cause is a
     * {@link SanitizedViolations} that recorded a call other than this one, by identity. The pipeline records the call
     * on its own rejections ({@code rejected}) and on the translation of a call's hand validation of its own input
     * (the operation interceptor). A {@code SynapseException} that code builds itself, or one that wraps a rejection,
     * is not one.
     *
     * @param failure the failure the call received
     * @param call the receiving call's token
     * @return {@code true} if another call's pipeline raised it
     */
    @SuppressWarnings("ReferenceEquality") // The token is an identity: only the same call's token is the same call.
    static boolean raisedByAnotherCall(SynapseException failure, Object call) {
        if (!(failure.getCause() instanceof SanitizedViolations sanitized)) {
            return false;
        }
        Object raisedBy = sanitized.call();
        return raisedBy != null && raisedBy != call;
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
     * Checks that a {@link ServiceOperation} input record carries every {@link VersionCondition} it requires: a
     * component typed {@code VersionCondition} that isn't {@code @Nullable}, the rule the API plugins apply. It runs
     * after {@link #validate(Object, Object)}, so the operation never sees a request without a required condition,
     * whoever calls it. {@link VersionCondition#any()} counts as a condition.
     *
     * @param input the input
     * @param call the token of the call that checks it, recorded on the rejection
     * @throws SynapseException {@code PRECONDITION_REQUIRED} with one violation per required component that is
     *     {@code null}
     */
    void requirePreconditions(@Nullable Object input, Object call) {
        if (input == null) {
            return;
        }
        List<RecordComponent> required = requiredVersionComponents(input.getClass());
        if (required.isEmpty()) {
            return;
        }
        List<Violation> missing = new ArrayList<>();
        for (RecordComponent component : required) {
            if (ReflectionUtils.invokeMethod(component.getAccessor(), input) == null) {
                missing.add(new Violation(component.getName(), VERSION_REQUIRED));
            }
        }
        if (!missing.isEmpty()) {
            throw rejected(SynapseErrors.PRECONDITION_REQUIRED, missing, Set.of(), missing, call);
        }
    }

    /**
     * Returns the components of an input record that are a required {@link VersionCondition}, in declaration order.
     * They are computed once per type, so every call for a type returns the same list.
     *
     * @return the components; empty for a type that isn't a record
     */
    List<RecordComponent> requiredVersionComponents(Class<?> type) {
        return versionComponents.computeIfAbsent(type, OperationPipeline::findRequiredVersionComponents);
    }

    private static List<RecordComponent> findRequiredVersionComponents(Class<?> type) {
        RecordComponent[] components = type.getRecordComponents();
        if (components == null) {
            return List.of();
        }
        List<RecordComponent> required = new ArrayList<>();
        for (RecordComponent component : components) {
            if (component.getType() == VersionCondition.class && !new ResolvedComponent(type, component).nullable()) {
                ReflectionUtils.makeAccessible(component.getAccessor());
                required.add(component);
            }
        }
        return List.copyOf(required);
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
