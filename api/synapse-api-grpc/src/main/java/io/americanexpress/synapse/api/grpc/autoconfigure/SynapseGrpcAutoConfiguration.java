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
package io.americanexpress.synapse.api.grpc.autoconfigure;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.grpc.BindableService;
import io.grpc.Metadata;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnThreading;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerAutoConfiguration;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerExecutorProvider;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerProperties;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerServicesAutoConfiguration;
import org.springframework.boot.thread.Threading;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.GrpcServerFactory;
import org.springframework.util.ClassUtils;

import io.americanexpress.synapse.api.grpc.GrpcBindings;
import io.americanexpress.synapse.api.grpc.internal.aot.ReflectionAotCheck;
import io.americanexpress.synapse.api.grpc.internal.edge.GrpcConcurrencyLimit;
import io.americanexpress.synapse.api.grpc.internal.edge.GrpcDeadlinePolicy;
import io.americanexpress.synapse.api.grpc.internal.edge.MetadataMasking;
import io.americanexpress.synapse.api.grpc.internal.edge.SynapseGrpcContextInterceptor;
import io.americanexpress.synapse.api.grpc.internal.edge.SynapseGrpcEdgeInterceptor;
import io.americanexpress.synapse.api.grpc.internal.error.GrpcStatusWriter;
import io.americanexpress.synapse.api.grpc.internal.service.ExposureInventory;
import io.americanexpress.synapse.api.grpc.internal.service.GrpcBindingsCompiler;
import io.americanexpress.synapse.api.grpc.internal.service.ServiceCompiler;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapseContextAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseContextProperties;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseCorrelationProperties;
import io.americanexpress.synapse.core.autoconfigure.SynapseOperationAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapsePagingProperties;
import io.americanexpress.synapse.core.internal.aot.BuildTimeSwitch;
import io.americanexpress.synapse.core.internal.context.ContextBinder;
import io.americanexpress.synapse.core.internal.context.SynapseBaggageFields;
import io.americanexpress.synapse.core.internal.paging.PageCursors;
import io.americanexpress.synapse.core.operation.ServiceOperation;

/**
 * Auto-configuration for the gRPC API plugin: its properties and the beans Synapse adds to Spring Boot's gRPC server.
 * Synapse never defines Boot's own gRPC beans; Boot's server auto-configuration consumes the ones defined here. Active
 * when Spring gRPC and grpc-java are on the classpath and {@code spring.grpc.server.enabled} is not false.
 */
@AutoConfiguration(before = {GrpcServerAutoConfiguration.class, GrpcServerServicesAutoConfiguration.class}, after = {
        SynapseCoreAutoConfiguration.class, SynapseContextAutoConfiguration.class,
        SynapseOperationAutoConfiguration.class})
@ConditionalOnClass({GrpcServerFactory.class, BindableService.class})
@ConditionalOnBooleanProperty(name = "spring.grpc.server.enabled", matchIfMissing = true)
@EnableConfigurationProperties(SynapseGrpcProperties.class)
public final class SynapseGrpcAutoConfiguration {

    private static final String SERVLET_REGISTRATION = "org.springframework.boot.grpc.server.GrpcServletRegistration";

    private static final String SERVLET = "jakarta.servlet.Servlet";

    private static final String REST_CONTEXT_FILTER = "synapseRestContextFilter";

    private static final String REMOTE_FIELDS = "management.tracing.baggage.remote-fields";

    /**
     * The outermost gRPC interceptor: it hides or strips inbound trace and baggage metadata before Boot's observation
     * interceptor (order 0) reads it, puts the call's {@code GrpcCallState} in the call's gRPC context, and echoes the
     * resolved correlation id. When inbound context is not trusted it hides the trace keys, {@code baggage} and every
     * remote-field key; when it is trusted it strips only the Synapse fields. Define a bean with the same name to
     * replace it.
     *
     * @param context the context properties, for {@code synapse.context.inbound.trusted}
     * @param correlation the correlation properties: the metadata key is {@code synapse.correlation.header} in lower
     *     case
     * @param environment the environment, to read the Synapse fields and {@value #REMOTE_FIELDS} (empty when unset)
     * @return the interceptor
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @GlobalServerInterceptor
    @ConditionalOnMissingBean(name = "synapseGrpcEdgeInterceptor")
    SynapseGrpcEdgeInterceptor synapseGrpcEdgeInterceptor(SynapseContextProperties context,
            SynapseCorrelationProperties correlation, Environment environment) {
        Binder binder = Binder.get(environment);
        Set<String> synapseFields = SynapseBaggageFields.of(binder);
        Set<String> remoteFields = new LinkedHashSet<>(
                binder.bind(REMOTE_FIELDS, Bindable.listOf(String.class)).orElse(List.of()));
        Metadata.Key<String> correlationKey = Metadata.Key
                .of(correlation.header().toLowerCase(Locale.ROOT), Metadata.ASCII_STRING_MARSHALLER);
        return new SynapseGrpcEdgeInterceptor(
                new MetadataMasking(context.inbound().trusted(), synapseFields, remoteFields), correlationKey);
    }

    /**
     * The policy that turns a call's gRPC deadline into the deadline of its request context
     * ({@code synapse.api.grpc.deadline}).
     *
     * @param properties the gRPC properties
     * @param clock the application clock
     * @return the policy
     */
    @Bean
    @ConditionalOnMissingBean
    GrpcDeadlinePolicy grpcDeadlinePolicy(SynapseGrpcProperties properties, Clock clock) {
        SynapseGrpcProperties.Deadline deadline = properties.deadline();
        return new GrpcDeadlinePolicy(deadline.defaultTimeout(), deadline.max(), clock);
    }

    /**
     * The cap on the gRPC calls handled at the same time ({@code synapse.api.grpc.max-concurrent-calls}).
     *
     * @param properties the gRPC properties
     * @return the limit
     */
    @Bean
    @ConditionalOnMissingBean
    GrpcConcurrencyLimit grpcConcurrencyLimit(SynapseGrpcProperties properties) {
        return new GrpcConcurrencyLimit(properties.maxConcurrentCalls());
    }

    /**
     * The gRPC interceptor that opens the Synapse request context (correlation id, attributes, locale and deadline)
     * around each callback of a call, and caps the calls handled at the same time. It runs inside Boot's observation
     * (order 0) and exception handling (order 1) interceptors, so the context's baggage is attached to the call's span
     * and a rejected or failed call is answered with error details. Define a bean with the same name to replace it.
     *
     * @param binder the binder that opens the context
     * @param properties the gRPC properties, for {@code synapse.api.grpc.context.metadata}
     * @param deadlines the deadline policy
     * @param limit the concurrency limit
     * @param writer writes the status of a rejected call
     * @param observations the observation registry, whose current observation is the call's server observation; a
     *     no-op registry when there is none
     * @return the interceptor
     */
    @Bean
    @Order(SynapseGrpcContextInterceptor.ORDER)
    @GlobalServerInterceptor
    @ConditionalOnMissingBean(name = "synapseGrpcContextInterceptor")
    SynapseGrpcContextInterceptor synapseGrpcContextInterceptor(ContextBinder binder, SynapseGrpcProperties properties,
            GrpcDeadlinePolicy deadlines, GrpcConcurrencyLimit limit, GrpcStatusWriter writer,
            ObjectProvider<ObservationRegistry> observations) {
        Map<String, Metadata.Key<String>> attributeKeys = new LinkedHashMap<>();
        properties
                .context()
                .metadata()
                .forEach((attribute, key) -> attributeKeys
                        .put(attribute, Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER)));
        return new SynapseGrpcContextInterceptor(binder, attributeKeys, deadlines, limit, writer,
                observations.getIfAvailable(() -> ObservationRegistry.NOOP));
    }

    /**
     * Compiles {@link GrpcBindings} into gRPC services: binds {@code .bindAllByName()} methods, maps the messages to
     * the operations' records and fails startup on a binding problem. Paged methods wrap and unwrap page tokens with
     * core's cursors, the same ones REST uses, and apply core's {@code synapse.paging} sizes and offset cap. Define a
     * bean of this type to replace it.
     *
     * @param cursors core's page cursors
     * @param paging core's paging properties
     * @return the compiler
     */
    @Bean
    @ConditionalOnMissingBean
    ServiceCompiler synapseGrpcServiceCompiler(PageCursors cursors, SynapsePagingProperties paging) {
        return new ServiceCompiler(cursors, paging);
    }

    /**
     * Compiles each {@link GrpcBindings} bean with the {@link ServiceCompiler} when it is created, so Boot's gRPC
     * server serves the compiled service. Static, as a bean post-processor must be; its dependencies are resolved
     * when the first bindings are compiled. Define a bean of this type to replace it.
     *
     * @param compiler the service compiler
     * @param operations the operation beans, for {@code .bindAllByName()}
     * @param cursors core's page cursors, for a compiler when there is none
     * @param paging core's paging properties, for a compiler when there is none
     * @return the post-processor
     */
    @Bean
    @ConditionalOnMissingBean
    static GrpcBindingsCompiler synapseGrpcBindingsCompiler(ObjectProvider<ServiceCompiler> compiler,
            ObjectProvider<ServiceOperation<?, ?>> operations, ObjectProvider<PageCursors> cursors,
            ObjectProvider<SynapsePagingProperties> paging) {
        return new GrpcBindingsCompiler(compiler, operations, cursors, paging);
    }

    /**
     * Logs every bound method with its operation once the gRPC server has started. Define a bean of this type to
     * replace it.
     *
     * @param bindings the bindings of the application
     * @return the inventory
     */
    @Bean
    @ConditionalOnMissingBean
    ExposureInventory synapseGrpcExposureInventory(ObjectProvider<GrpcBindings> bindings) {
        return new ExposureInventory(bindings);
    }

    /**
     * Runs gRPC calls on virtual threads when {@code spring.threads.virtual.enabled=true}: Boot's server builder
     * customizers apply this provider to the Netty and in-process servers. Each call gets a new virtual thread named
     * {@code grpc-<n>}. The executor is closed when the context closes, after the gRPC server has stopped: it waits
     * for calls still running in operations for at most Boot's {@code spring.grpc.server.shutdown.grace-period}, then
     * interrupts them; with a negative grace period it waits for them forever, as Boot's server does. Define a
     * {@link GrpcServerExecutorProvider} bean to replace it.
     *
     * @param serverProperties Boot's gRPC server properties, present when Boot's server auto-configuration is active;
     *     without them there is no server and Boot's default grace period is used
     * @return the executor provider
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnThreading(Threading.VIRTUAL)
    GrpcServerExecutorProvider synapseGrpcServerExecutorProvider(
            ObjectProvider<GrpcServerProperties> serverProperties) {
        Duration closeTimeout = serverProperties
                .getIfAvailable(GrpcServerProperties::new)
                .getShutdown()
                .getGracePeriod();
        return new VirtualThreadExecutorProvider(
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("grpc-", 0).factory()), closeTimeout);
    }

    /**
     * Fails startup when gRPC is served by the servlet container (a {@code GrpcServletRegistration} bean) and the REST
     * plugin is active (a bean named {@value #REST_CONTEXT_FILTER}): the REST edge filters would apply to gRPC calls.
     * The check does nothing when the Servlet API is not on the classpath. Define a bean with the same name to replace
     * it.
     *
     * @param beanFactory the bean factory, to look for both beans
     * @param resourceLoader the application's resource loader, whose class loader tells whether the Servlet API is
     *     present
     * @return the check, run once all singletons exist
     */
    @Bean
    @ConditionalOnMissingBean(name = "synapseGrpcServletCheck")
    static SmartInitializingSingleton synapseGrpcServletCheck(ListableBeanFactory beanFactory,
            ResourceLoader resourceLoader) {
        return () -> {
            ClassLoader classLoader = resourceLoader.getClassLoader();
            if (!ClassUtils.isPresent(SERVLET, classLoader) || !ClassUtils.isPresent(SERVLET_REGISTRATION, classLoader)
                    || !beanFactory.containsBean(REST_CONTEXT_FILTER)) {
                return;
            }
            Class<?> registration = ClassUtils.resolveClassName(SERVLET_REGISTRATION, classLoader);
            if (beanFactory.getBeanNamesForType(registration, true, false).length > 0) {
                throw new SynapseConfigurationException(
                        "gRPC served by the servlet container is not supported together with synapse-api-rest yet:"
                                + " the REST edge would filter gRPC calls.",
                        "Use the default Netty transport (keep io.grpc:grpc-netty), or remove synapse-api-rest.");
            }
        };
    }

    /**
     * The build-time switch for {@code spring.grpc.server.reflection.enabled}, which decided whether Boot's server
     * reflection bean exists. Core's check fails startup in AOT mode when it differs from the build, with every other
     * switch that differs. Define a bean with the same name to replace it.
     *
     * @param environment the environment, for the property's value now
     * @param beanFactory the bean factory, for the services the build defined
     * @param resourceLoader the application's resource loader, whose class loader tells whether Boot's reflection
     *     service class is present
     * @return the switch
     */
    @Bean
    @ConditionalOnMissingBean(name = "synapseGrpcReflectionAotCheck")
    static BuildTimeSwitch synapseGrpcReflectionAotCheck(Environment environment,
            ListableBeanFactory beanFactory, ResourceLoader resourceLoader) {
        return new ReflectionAotCheck(environment, beanFactory, resourceLoader.getClassLoader());
    }

    /**
     * A {@link GrpcServerExecutorProvider} that owns its executor and closes it with the context.
     */
    static final class VirtualThreadExecutorProvider implements GrpcServerExecutorProvider, AutoCloseable {

        private final ExecutorService executor;

        private final Duration closeTimeout;

        /**
         * Creates the provider.
         *
         * @param executor the executor, closed with the provider
         * @param closeTimeout how long closing waits for running calls before it interrupts them; negative waits
         *     forever
         */
        VirtualThreadExecutorProvider(ExecutorService executor, Duration closeTimeout) {
            this.executor = executor;
            this.closeTimeout = closeTimeout;
        }

        @Override
        public Executor getExecutor() {
            return executor;
        }

        /**
         * Returns how long closing waits for running calls.
         *
         * @return the timeout
         */
        Duration closeTimeout() {
            return closeTimeout;
        }

        /**
         * Closes the executor: it stops accepting tasks, waits up to the close timeout for the running ones to finish,
         * then interrupts those still running. With a negative timeout it waits until they finish
         * ({@link ExecutorService#close()}). If the closing thread is interrupted while it waits, it interrupts them
         * at once and keeps its interrupt status.
         */
        @Override
        public void close() {
            if (closeTimeout.isNegative()) {
                executor.close();
                return;
            }
            executor.shutdown();
            try {
                if (!executor.awaitTermination(closeTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException ex) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
