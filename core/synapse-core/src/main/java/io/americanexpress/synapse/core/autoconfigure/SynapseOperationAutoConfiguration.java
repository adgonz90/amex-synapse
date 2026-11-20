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
package io.americanexpress.synapse.core.autoconfigure;

import java.time.Clock;
import java.util.Map;
import java.util.function.Predicate;
import jakarta.validation.Validator;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;
import org.springframework.core.env.Environment;

import io.americanexpress.synapse.core.error.ErrorMessages;
import io.americanexpress.synapse.core.error.ExceptionTranslator;
import io.americanexpress.synapse.core.internal.binding.BindingDeclarations;
import io.americanexpress.synapse.core.internal.binding.BindingParityCheck;
import io.americanexpress.synapse.core.internal.context.ContextBinder;
import io.americanexpress.synapse.core.internal.pipeline.BusinessAdvisor;
import io.americanexpress.synapse.core.internal.pipeline.ConstraintViolationTranslator;
import io.americanexpress.synapse.core.internal.pipeline.InflightTracker;
import io.americanexpress.synapse.core.internal.pipeline.OperationAdvisor;
import io.americanexpress.synapse.core.internal.pipeline.OperationBeanDefinitionChecker;
import io.americanexpress.synapse.core.internal.pipeline.OperationDrainLifecycle;
import io.americanexpress.synapse.core.internal.pipeline.OperationPipeline;
import io.americanexpress.synapse.core.internal.pipeline.OperationProxyVerifier;
import io.americanexpress.synapse.core.masking.LogMasking;
import io.americanexpress.synapse.core.observability.DefaultOperationObservationConvention;
import io.americanexpress.synapse.core.observability.OperationObservationConvention;

/**
 * Wires the invocation pipeline (BUILD.md §5.2) into the application: every {@code ServiceOperation} and
 * {@code BusinessOperation} bean becomes a class-based proxy that runs it. The advisors and the proxy checks are not
 * replaceable, so no operation can silently run without the pipeline; the pipeline and its parts are. The bindings
 * registry and the parity check between protocols are internal and back off only to a bean of the same name.
 *
 * <p>
 * This auto-configuration has no activation condition on purpose: switching it off would leave operations running
 * without the pipeline. When Spring's auto-proxying is off ({@code spring.aop.auto=false}), startup fails instead.
 */
@AutoConfiguration(after = {AopAutoConfiguration.class, ValidationAutoConfiguration.class,
        JacksonAutoConfiguration.class, MessageSourceAutoConfiguration.class, ObservationAutoConfiguration.class,
        MicrometerTracingAutoConfiguration.class, CompositeMeterRegistryAutoConfiguration.class,
        SynapseCoreAutoConfiguration.class,
        SynapseContextAutoConfiguration.class}, afterName = "org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration")
public final class SynapseOperationAutoConfiguration {

    /**
     * Marks operation and business bean definitions for class-based proxies and fails startup for classes that cannot
     * be proxied. Static, as every {@code BeanFactoryPostProcessor} bean method should be.
     *
     * @return the checker
     */
    @Bean
    static OperationBeanDefinitionChecker synapseOperationBeanDefinitionChecker() {
        return new OperationBeanDefinitionChecker();
    }

    /**
     * Applies the operation pipeline to {@code ServiceOperation} beans. Not replaceable.
     *
     * @param pipeline the pipeline, resolved on the first operation call
     * @return the advisor
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static OperationAdvisor synapseOperationAdvisor(ObjectProvider<OperationPipeline> pipeline) {
        return new OperationAdvisor(pipeline::getObject);
    }

    /**
     * Applies the reduced pipeline to {@code BusinessOperation} beans. Not replaceable.
     *
     * @param pipeline the pipeline, resolved on the first business call
     * @return the advisor
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static BusinessAdvisor synapseBusinessAdvisor(ObjectProvider<OperationPipeline> pipeline) {
        return new BusinessAdvisor(pipeline::getObject);
    }

    /**
     * Fails startup when an operation or business bean would skip the pipeline, and warns about unknown
     * {@code synapse.operations} names. Not replaceable.
     *
     * @param beanFactory the bean factory
     * @param environment the environment
     * @return the verifier
     */
    @Bean
    static OperationProxyVerifier synapseOperationProxyVerifier(ConfigurableListableBeanFactory beanFactory,
            Environment environment) {
        return new OperationProxyVerifier(beanFactory, environment);
    }

    /**
     * The bindings the API plugins compile, which they register while their beans are created. Internal: the bean
     * backs off by name only.
     *
     * @return the registry
     */
    @Bean
    @ConditionalOnMissingBean(name = "synapseBindingDeclarations")
    BindingDeclarations synapseBindingDeclarations() {
        return new BindingDeclarations();
    }

    /**
     * Fails startup when two protocols bind an operation with different {@code .notFound}, {@code .etag} or
     * {@code .sortable} declarations, once every singleton (and so every compiled binding) exists. Internal: the bean
     * backs off by name only.
     *
     * @param declarations the compiled bindings
     * @return the check
     */
    @Bean
    @ConditionalOnMissingBean(name = "synapseBindingParityCheck")
    BindingParityCheck synapseBindingParityCheck(BindingDeclarations declarations) {
        return new BindingParityCheck(declarations);
    }

    /**
     * Counts in-flight operation calls, for draining at shutdown.
     *
     * @return the tracker
     */
    @Bean
    @ConditionalOnMissingBean
    InflightTracker inflightTracker() {
        return new InflightTracker();
    }

    /**
     * Rejects new context-less calls and waits for in-flight operations when the application shuts down.
     *
     * @param inflight the in-flight tracker
     * @return the lifecycle bean
     */
    @Bean
    @ConditionalOnMissingBean
    OperationDrainLifecycle operationDrainLifecycle(InflightTracker inflight) {
        return new OperationDrainLifecycle(inflight);
    }

    /**
     * Resolves error messages, with {@code MessageSource} entries keyed by error code overriding the templates.
     *
     * @param messageSource the application's message source
     * @return the error messages
     */
    @Bean
    @ConditionalOnMissingBean
    ErrorMessages errorMessages(MessageSource messageSource) {
        return new ErrorMessages(messageSource);
    }

    /**
     * Translates a {@code ConstraintViolationException} about the caller's own input into {@code VALIDATION_FAILED}.
     *
     * @return the translator
     */
    @Bean
    @ConditionalOnMissingBean
    ConstraintViolationTranslator constraintViolationTranslator() {
        return new ConstraintViolationTranslator();
    }

    /**
     * The default operation observation convention; define another {@link OperationObservationConvention} bean to
     * replace it.
     *
     * @return the convention
     */
    @Bean
    @ConditionalOnMissingBean(OperationObservationConvention.class)
    DefaultOperationObservationConvention defaultOperationObservationConvention() {
        return new DefaultOperationObservationConvention();
    }

    /**
     * The invocation pipeline.
     *
     * @param clock the clock deadlines are checked against
     * @param validator the Jakarta validator, if there is a single one
     * @param translators the exception translators, in order
     * @param observationRegistry the observation registry, if any
     * @param meterRegistry the meter registry for business timers, if there is a single one
     * @param contextBinder the binder that opens a context for calls without one
     * @param inflight the in-flight tracker
     * @param logMasking the masking for payload log lines
     * @param convention the operation observation convention, if there is a single one
     * @param paging the paging properties
     * @param observability the observability properties
     * @param operations the per-operation properties
     * @return the pipeline
     */
    @Bean
    @ConditionalOnMissingBean
    OperationPipeline operationPipeline(Clock clock, ObjectProvider<Validator> validator,
            ObjectProvider<ExceptionTranslator> translators, ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<MeterRegistry> meterRegistry, ObjectProvider<ContextBinder> contextBinder,
            InflightTracker inflight, LogMasking logMasking, ObjectProvider<OperationObservationConvention> convention,
            SynapsePagingProperties paging, SynapseObservabilityProperties observability,
            SynapseOperationsProperties operations) {
        OperationPipeline.Builder builder = OperationPipeline
                .builder()
                .clock(clock)
                .translators(translators.orderedStream().toList())
                .observationRegistry(observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP))
                .meterRegistry(meterRegistry.getIfUnique())
                .inflight(inflight)
                .logMasking(logMasking)
                .maxPageSize(paging.maxSize())
                .businessObservation(observability.business())
                .payloadLogging(payloadLogging(operations.operations()))
                .observationConvention(customConvention(convention.getIfUnique()));
        validator.ifUnique(builder::validator);
        contextBinder.ifAvailable(builder::contextBinder);
        return builder.build();
    }

    private static Predicate<String> payloadLogging(
            Map<String, SynapseOperationsProperties.Operation> operations) {
        return name -> {
            SynapseOperationsProperties.Operation operation = operations.get(name);
            return operation != null && operation.logging().payloads();
        };
    }

    /**
     * Passes on only a convention that replaces the default one, so that a {@code GlobalObservationConvention}
     * registered with the observation registry still applies when the user has not defined their own.
     */
    private static @Nullable OperationObservationConvention customConvention(
            @Nullable OperationObservationConvention convention) {
        return convention == null || convention.getClass() == DefaultOperationObservationConvention.class
                ? null
                : convention;
    }
}
