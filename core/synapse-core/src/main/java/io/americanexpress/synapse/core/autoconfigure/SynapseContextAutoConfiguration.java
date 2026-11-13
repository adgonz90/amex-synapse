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

import io.micrometer.context.ContextSnapshot;
import io.micrometer.tracing.Tracer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.NoopTracerAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.internal.context.ContextBinder;
import io.americanexpress.synapse.core.internal.context.CorrelationIdPolicy;
import io.americanexpress.synapse.core.operation.ServiceOperation;

/**
 * Auto-configuration of the Synapse request context: the correlation id policy, the context binder, and the task
 * decorator that carries the context to {@code @Async} and executor tasks. It also fails startup when operations
 * exist but tracing cannot carry the context (no real tracer, or baggage disabled), unless
 * {@code synapse.observability.require-tracing=false}.
 */
@AutoConfiguration(after = {SynapseCoreAutoConfiguration.class, NoopTracerAutoConfiguration.class,
        MicrometerTracingAutoConfiguration.class}, before = TaskExecutionAutoConfiguration.class)
@ConditionalOnClass({Tracer.class, ContextSnapshot.class})
@EnableConfigurationProperties({SynapseContextProperties.class, SynapseCorrelationProperties.class})
public final class SynapseContextAutoConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(SynapseContextAutoConfiguration.class);

    private static final String BAGGAGE_ENABLED = "management.tracing.baggage.enabled";

    private static final String REQUIRE_TRACING = "synapse.observability.require-tracing";

    /** The property source Boot 4.1 adds for the {@code OTEL_*} environment variables it maps. */
    private static final String OTEL_ENVIRONMENT_VARIABLES = "openTelemetryEnvironmentVariables";

    /**
     * The policy that accepts or replaces inbound correlation ids.
     *
     * @param properties the correlation properties
     * @param clock the application clock
     * @return the policy
     */
    @Bean
    @ConditionalOnMissingBean
    CorrelationIdPolicy correlationIdPolicy(SynapseCorrelationProperties properties, Clock clock) {
        return new CorrelationIdPolicy(properties.maxLength(), clock);
    }

    /**
     * The binder that opens the request context and its tracing baggage.
     *
     * @param tracer the tracer; {@link Tracer#NOOP} when there is no single one (startup then fails if operations
     *     exist)
     * @param policy the correlation id policy
     * @param properties the context properties
     * @param clock the application clock
     * @return the binder
     */
    @Bean
    @ConditionalOnMissingBean
    ContextBinder contextBinder(ObjectProvider<Tracer> tracer, CorrelationIdPolicy policy,
            SynapseContextProperties properties, Clock clock) {
        SynapseContextProperties.Inbound inbound = properties.inbound();
        return new ContextBinder(tracer.getIfUnique(() -> Tracer.NOOP), policy, inbound.trusted(),
                inbound.maxEntries(), inbound.maxSize().toBytes(), clock);
    }

    /**
     * The task decorator that carries the request context (and tracing) to executor and {@code @Async} tasks. Boot's
     * task execution auto-configuration applies it to the executors it builds, composed with any other
     * {@code TaskDecorator} beans, so it backs off only when another {@link ContextPropagatingTaskDecorator} bean is
     * defined. The return type is the concrete class so that the bean type is known without creating the bean. When
     * the user turns on Boot's own decorator ({@code spring.task.execution.propagate-context=true}), Boot's does the
     * work and this one is not registered, so the bean names don't clash and the context isn't captured twice.
     *
     * @return the decorator
     */
    @Bean
    @ConditionalOnMissingBean(ContextPropagatingTaskDecorator.class)
    @ConditionalOnProperty(name = "spring.task.execution.propagate-context", havingValue = "false", matchIfMissing = true)
    ContextPropagatingTaskDecorator synapseContextPropagatingTaskDecorator() {
        return new ContextPropagatingTaskDecorator();
    }

    /**
     * Fails startup when service operations exist but tracing cannot carry their context. With
     * {@code synapse.observability.require-tracing=false} it logs one INFO line about the problem instead. Define a
     * bean with the same name to replace it.
     *
     * @param beanFactory the bean factory, to look for operations
     * @param tracer the tracer
     * @param environment the environment, to read Boot's baggage switch and where its value came from
     * @param properties the observability properties, for {@code require-tracing}
     * @return the check, run once all singletons exist
     */
    @Bean
    @ConditionalOnMissingBean(name = "synapseTracingRequirementCheck")
    static SmartInitializingSingleton synapseTracingRequirementCheck(ListableBeanFactory beanFactory,
            ObjectProvider<Tracer> tracer, Environment environment,
            ObjectProvider<SynapseObservabilityProperties> properties) {
        return () -> {
            if (beanFactory.getBeanNamesForType(ServiceOperation.class, true, false).length == 0) {
                return;
            }
            TracingProblem problem = tracingProblem(tracer.getIfUnique(), environment);
            if (problem == null) {
                return;
            }
            SynapseObservabilityProperties observability = properties.getIfAvailable();
            if (observability == null || observability.requireTracing()) {
                throw problem;
            }
            LOGGER
                    .info("Synapse's tracing check is off (" + REQUIRE_TRACING + "=false): {}, so the correlation id "
                            + "and context attributes reach neither the logs nor outbound calls.", problem.reason());
        };
    }

    // Tracer.NOOP is a singleton; identity is the intended check.
    @SuppressWarnings("ReferenceEquality")
    private static @Nullable TracingProblem tracingProblem(@Nullable Tracer tracer, Environment environment) {
        if (tracer == null || tracer == Tracer.NOOP) {
            String reason = "the application has " + (tracer == null
                    ? "no single Tracer bean (none, or several without a primary)"
                    : "only the no-op Tracer (Tracer.NOOP)");
            return new TracingProblem(reason,
                    "Synapse service operations need a tracer to carry the request context, but " + reason + ".",
                    "Add the OpenTelemetry tracing bridge, org.springframework.boot:spring-boot-starter-opentelemetry, "
                            + "to the service module. If you run the OpenTelemetry Java agent, keep that starter and "
                            + "define an OpenTelemetry bean that returns GlobalOpenTelemetry.get(), so the bridge uses "
                            + "the agent's SDK. To start without the check, set " + REQUIRE_TRACING + "=false; the "
                            + "correlation id and context attributes then reach neither the logs nor outbound calls.");
        }
        if (environment.getProperty(BAGGAGE_ENABLED, Boolean.class, true)) {
            return null;
        }
        String description = "Synapse service operations carry the correlation id and context attributes as tracing "
                + "baggage, but baggage is disabled";
        if (fromOtelEnvironmentVariables(environment)) {
            return new TracingProblem("baggage is disabled by the OTEL_PROPAGATORS environment variable",
                    description + ": Spring Boot set " + BAGGAGE_ENABLED + "=false from the OTEL_PROPAGATORS "
                            + "environment variable, which does not include 'baggage'.",
                    "Add 'baggage' to OTEL_PROPAGATORS (for example tracecontext,baggage). If the variable is meant "
                            + "for the OpenTelemetry Java agent, configure the agent with -Dotel.propagators instead "
                            + "and set management.opentelemetry.map-environment-variables=false.");
        }
        return new TracingProblem("baggage is disabled (" + BAGGAGE_ENABLED + "=false)",
                description + " (" + BAGGAGE_ENABLED + "=false).",
                "Re-enable baggage: remove " + BAGGAGE_ENABLED + "=false or set it to true.");
    }

    /**
     * Whether Boot's baggage switch came from the property source that Boot 4.1 builds from {@code OTEL_*}
     * environment variables. Boot adds that source first, so a value in it is the one in effect.
     */
    private static boolean fromOtelEnvironmentVariables(Environment environment) {
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            return false;
        }
        PropertySource<?> source = configurable.getPropertySources().get(OTEL_ENVIRONMENT_VARIABLES);
        return source != null && "false".equalsIgnoreCase(String.valueOf(source.getProperty(BAGGAGE_ENABLED)));
    }

    /** A tracing problem: a short reason for the INFO line, and the startup failure. */
    private static final class TracingProblem extends SynapseConfigurationException {

        private static final long serialVersionUID = 1L;

        private final String reason;

        TracingProblem(String reason, String description, String action) {
            super(description, action);
            this.reason = reason;
        }

        String reason() {
            return reason;
        }
    }
}
