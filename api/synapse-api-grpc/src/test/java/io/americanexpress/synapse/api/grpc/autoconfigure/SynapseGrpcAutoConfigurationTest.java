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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.grpc.BindableService;
import io.grpc.Deadline;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.micrometer.core.instrument.binder.grpc.ObservationGrpcServerInterceptor;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.LazyInitializationExcludeFilter;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.grpc.server.GrpcServletRegistration;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerAutoConfiguration;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerExecutorProvider;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerObservationAutoConfiguration;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerServicesAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.grpc.internal.ApplicationContextBeanLookupUtils;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.GrpcServerFactory;
import org.springframework.grpc.server.exception.GrpcExceptionHandlerInterceptor;

import io.americanexpress.synapse.api.grpc.GrpcBindings;
import io.americanexpress.synapse.api.grpc.fixture.proto.CatalogServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.DocServiceGrpc;
import io.americanexpress.synapse.api.grpc.internal.aot.ReflectionAotCheck;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures;
import io.americanexpress.synapse.api.grpc.internal.edge.GrpcConcurrencyLimit;
import io.americanexpress.synapse.api.grpc.internal.edge.GrpcDeadlinePolicy;
import io.americanexpress.synapse.api.grpc.internal.edge.MetadataMasking;
import io.americanexpress.synapse.api.grpc.internal.edge.SynapseGrpcContextInterceptor;
import io.americanexpress.synapse.api.grpc.internal.edge.SynapseGrpcEdgeInterceptor;
import io.americanexpress.synapse.api.grpc.internal.service.Compilers;
import io.americanexpress.synapse.api.grpc.internal.service.ExposureInventory;
import io.americanexpress.synapse.api.grpc.internal.service.GrpcBindingsCompiler;
import io.americanexpress.synapse.api.grpc.internal.service.ServiceCompiler;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapseContextAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseOperationAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapsePagingProperties;
import io.americanexpress.synapse.core.error.ErrorMessages;
import io.americanexpress.synapse.core.internal.aot.BuildTimeSwitch;
import io.americanexpress.synapse.core.internal.binding.BindingDeclaration;
import io.americanexpress.synapse.core.internal.binding.BindingDeclarations;
import io.americanexpress.synapse.core.internal.paging.PageCursors;
import io.americanexpress.synapse.core.internal.pipeline.OperationSettingsPrefix;
import io.americanexpress.synapse.core.operation.ServiceOperation;

import static org.assertj.core.api.Assertions.assertThat;

class SynapseGrpcAutoConfigurationTest {

    private static final String SERVLET_CHECK = "synapseGrpcServletCheck";

    private static final String REFLECTION_AOT_CHECK = "synapseGrpcReflectionAotCheck";

    private static final String REST_CONTEXT_FILTER = "synapseRestContextFilter";

    private static final String EDGE_INTERCEPTOR = "synapseGrpcEdgeInterceptor";

    private static final String CONTEXT_INTERCEPTOR = "synapseGrpcContextInterceptor";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations
                    .of(SynapseCoreAutoConfiguration.class,
                            SynapseContextAutoConfiguration.class, SynapseGrpcAutoConfiguration.class,
                            SynapseGrpcErrorAutoConfiguration.class, GrpcServerServicesAutoConfiguration.class,
                            GrpcServerAutoConfiguration.class))
            .withBean(ErrorMessages.class, () -> new ErrorMessages(new StaticMessageSource()));

    @Test
    void activatesWithGrpcOnTheClasspath() {
        runner
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(SynapseGrpcProperties.class)
                        .hasBean(SERVLET_CHECK)
                        .hasBean(REFLECTION_AOT_CHECK)
                        .getBean(REFLECTION_AOT_CHECK)
                        .isInstanceOf(ReflectionAotCheck.class));
    }

    @Test
    void absentWithoutGrpc() {
        runner
                .withClassLoader(new FilteredClassLoader(GrpcServerFactory.class))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(SynapseGrpcProperties.class)
                        .doesNotHaveBean(SERVLET_CHECK)
                        .doesNotHaveBean(REFLECTION_AOT_CHECK));
        runner
                .withClassLoader(new FilteredClassLoader(BindableService.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(SynapseGrpcProperties.class));
    }

    @Test
    void absentWhenTheGrpcServerIsDisabled() {
        runner
                .withPropertyValues("spring.grpc.server.enabled=false")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(SynapseGrpcProperties.class)
                        .doesNotHaveBean(SERVLET_CHECK)
                        .doesNotHaveBean(REFLECTION_AOT_CHECK));
    }

    @Test
    void bindsProperties() {
        runner
                .withPropertyValues("synapse.api.grpc.context.metadata.tenant=x-tenant",
                        "synapse.api.grpc.deadline.default=5s", "synapse.api.grpc.deadline.max=20s",
                        "synapse.api.grpc.max-concurrent-calls=50",
                        "synapse.api.grpc.errors.status.[BOOK_NOT_FOUND]=FAILED_PRECONDITION",
                        "synapse.api.grpc.operations.get-book.errors.status.[BOOK_NOT_FOUND]=OUT_OF_RANGE")
                .run(context -> {
                    SynapseGrpcProperties properties = context.getBean(SynapseGrpcProperties.class);
                    assertThat(properties.context().metadata()).isEqualTo(Map.of("tenant", "x-tenant"));
                    assertThat(properties.deadline().defaultTimeout()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(properties.deadline().max()).isEqualTo(Duration.ofSeconds(20));
                    assertThat(properties.maxConcurrentCalls()).isEqualTo(50);
                    assertThat(properties.errors().status())
                            .isEqualTo(Map.of("BOOK_NOT_FOUND", Status.Code.FAILED_PRECONDITION));
                    assertThat(properties.operations()).containsOnlyKeys("get-book");
                    assertThat(Objects.requireNonNull(properties.operations().get("get-book")).errors().status())
                            .isEqualTo(Map.of("BOOK_NOT_FOUND", Status.Code.OUT_OF_RANGE));
                });
    }

    @Test
    void virtualThreadExecutorOnlyWithVirtualThreads() {
        AtomicReference<Executor> executor = new AtomicReference<>();
        runner.withPropertyValues("spring.threads.virtual.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(GrpcServerExecutorProvider.class);
            executor.set(context.getBean(GrpcServerExecutorProvider.class).getExecutor());
            CompletableFuture<Thread> ran = new CompletableFuture<>();
            executor.get().execute(() -> ran.complete(Thread.currentThread()));
            Thread thread = ran.get(5, TimeUnit.SECONDS);
            assertThat(thread.isVirtual()).isTrue();
            assertThat(thread.getName()).startsWith("grpc-");
        });
        // The executor is closed with the context.
        assertThat(executor.get())
                .isInstanceOfSatisfying(ExecutorService.class,
                        service -> assertThat(service.isShutdown()).isTrue());

        runner.run(context -> assertThat(context).doesNotHaveBean(GrpcServerExecutorProvider.class));
    }

    @Test
    void executorWaitsAtMostTheShutdownGracePeriod() {
        // Without a service Boot's server (and its properties bean) is absent: Boot's default grace period.
        runner
                .withPropertyValues("spring.threads.virtual.enabled=true")
                .run(context -> assertThat(closeTimeout(context.getBean(GrpcServerExecutorProvider.class)))
                        .isEqualTo(Duration.ofSeconds(30)));
        ApplicationContextRunner withServer = serverRunner().withPropertyValues("spring.threads.virtual.enabled=true");
        withServer
                .run(context -> assertThat(closeTimeout(context.getBean(GrpcServerExecutorProvider.class)))
                        .isEqualTo(Duration.ofSeconds(30)));
        // Read from Boot's GrpcServerProperties, so a value without a unit is in seconds, as for Boot's server.
        withServer
                .withPropertyValues("spring.grpc.server.shutdown.grace-period=2")
                .run(context -> assertThat(closeTimeout(context.getBean(GrpcServerExecutorProvider.class)))
                        .isEqualTo(Duration.ofSeconds(2)));
        withServer
                .withPropertyValues("spring.grpc.server.shutdown.grace-period=-1")
                .run(context -> assertThat(closeTimeout(context.getBean(GrpcServerExecutorProvider.class)))
                        .isNegative());
    }

    @Test
    void negativeTimeoutWaitsForRunningCalls() throws Exception {
        ExecutorService service = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        SynapseGrpcAutoConfiguration.VirtualThreadExecutorProvider provider = new SynapseGrpcAutoConfiguration.VirtualThreadExecutorProvider(
                service, Duration.ofSeconds(-1));
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        AtomicBoolean interrupted = new AtomicBoolean();
        service.execute(() -> {
            started.countDown();
            try {
                Thread.sleep(300);
                finished.set(true);
            } catch (InterruptedException ex) {
                interrupted.set(true);
            }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        provider.close();

        assertThat(service.isTerminated()).isTrue();
        assertThat(finished).isTrue();
        assertThat(interrupted).isFalse();
    }

    private ApplicationContextRunner serverRunner() {
        return runner
                .withConfiguration(AutoConfigurations.of(SslAutoConfiguration.class))
                .withPropertyValues("spring.grpc.server.port=0")
                .withBean("testService", BindableService.class,
                        () -> () -> ServerServiceDefinition.builder("test.TestService").build());
    }

    private static Duration closeTimeout(GrpcServerExecutorProvider provider) {
        assertThat(provider).isInstanceOf(SynapseGrpcAutoConfiguration.VirtualThreadExecutorProvider.class);
        return ((SynapseGrpcAutoConfiguration.VirtualThreadExecutorProvider) provider).closeTimeout();
    }

    @Test
    void closeWaitsForRunningCallsWithinTheTimeout() throws Exception {
        ExecutorService service = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        SynapseGrpcAutoConfiguration.VirtualThreadExecutorProvider provider = new SynapseGrpcAutoConfiguration.VirtualThreadExecutorProvider(
                service, Duration.ofSeconds(10));
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        AtomicBoolean interrupted = new AtomicBoolean();
        service.execute(() -> {
            started.countDown();
            try {
                Thread.sleep(200);
                finished.set(true);
            } catch (InterruptedException ex) {
                interrupted.set(true);
            }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        provider.close();

        assertThat(service.isTerminated()).isTrue();
        assertThat(finished).isTrue();
        assertThat(interrupted).isFalse();
    }

    @Test
    void closeInterruptsCallsStillRunningAfterTheTimeout() throws Exception {
        ExecutorService service = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        SynapseGrpcAutoConfiguration.VirtualThreadExecutorProvider provider = new SynapseGrpcAutoConfiguration.VirtualThreadExecutorProvider(
                service, Duration.ofMillis(100));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        service.execute(() -> {
            started.countDown();
            try {
                never.await();
            } catch (InterruptedException ex) {
                interrupted.countDown();
            }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        provider.close();

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(service.isShutdown()).isTrue();
        assertThat(interrupted.await(5, TimeUnit.SECONDS)).as("the blocked call was interrupted").isTrue();
    }

    @Test
    void closeStopsWaitingWhenInterrupted() throws Exception {
        ExecutorService service = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        SynapseGrpcAutoConfiguration.VirtualThreadExecutorProvider provider = new SynapseGrpcAutoConfiguration.VirtualThreadExecutorProvider(
                service, Duration.ofMinutes(5));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        service.execute(() -> {
            started.countDown();
            try {
                never.await();
            } catch (InterruptedException ex) {
                interrupted.countDown();
            }
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        AtomicBoolean stillInterrupted = new AtomicBoolean();

        Thread closer = Thread.ofPlatform().start(() -> {
            Thread.currentThread().interrupt();
            provider.close();
            stillInterrupted.set(Thread.currentThread().isInterrupted());
        });
        closer.join(5_000);

        assertThat(closer.isAlive()).isFalse();
        assertThat(stillInterrupted).as("the interrupt status is kept").isTrue();
        assertThat(interrupted.await(5, TimeUnit.SECONDS)).as("the blocked call was interrupted").isTrue();
    }

    @Test
    void executorBacksOffToAUserProvider() {
        Executor direct = Runnable::run;
        GrpcServerExecutorProvider user = () -> direct;
        runner
                .withPropertyValues("spring.threads.virtual.enabled=true")
                .withBean("userExecutorProvider", GrpcServerExecutorProvider.class, () -> user)
                .run(context -> {
                    assertThat(context).hasSingleBean(GrpcServerExecutorProvider.class);
                    assertThat(context.getBean(GrpcServerExecutorProvider.class)).isSameAs(user);
                });
    }

    @Test
    void servletTransportWithRestFailsStartup() {
        runner
                .withBean(GrpcServletRegistration.class, SynapseGrpcAutoConfigurationTest::unusedRegistration,
                        definition -> definition.setLazyInit(true))
                .withBean(REST_CONTEXT_FILTER, Object.class, Object::new)
                .run(context -> {
                    assertThat(context).hasFailed();
                    SynapseConfigurationException failure = configurationFailure(context.getStartupFailure());
                    assertThat(failure.description())
                            .isEqualTo(
                                    "gRPC served by the servlet container is not supported together with synapse-api-rest"
                                            + " yet: the REST edge would filter gRPC calls.");
                    assertThat(failure.action())
                            .isEqualTo(
                                    "Use the default Netty transport (keep io.grpc:grpc-netty), or remove synapse-api-rest.");
                });
    }

    private static SynapseConfigurationException configurationFailure(@Nullable Throwable failure) {
        Throwable cause = failure;
        while (cause != null && !(cause instanceof SynapseConfigurationException)) {
            cause = cause.getCause();
        }
        assertThat(cause).as("a SynapseConfigurationException in %s", failure).isNotNull();
        return (SynapseConfigurationException) Objects.requireNonNull(cause);
    }

    /**
     * Supplies the servlet registration bean, which is registered lazily so it is never created: the check finds it by
     * type, and creating a real one (or a Mockito mock) needs grpc-servlet-jakarta.
     */
    private static GrpcServletRegistration unusedRegistration() {
        throw new IllegalStateException("The servlet registration bean is lazy and must not be created");
    }

    @Test
    void servletTransportAloneOrRestAloneStarts() {
        runner
                .withBean(GrpcServletRegistration.class, SynapseGrpcAutoConfigurationTest::unusedRegistration,
                        definition -> definition.setLazyInit(true))
                .run(context -> assertThat(context).hasNotFailed());
        runner
                .withBean(REST_CONTEXT_FILTER, Object.class, Object::new)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void servletCheckIsSkippedWithoutTheServletApi() {
        // With the registration and the REST filter both present, only the Servlet API check keeps startup going.
        runner
                .withClassLoader(new FilteredClassLoader("jakarta.servlet."))
                .withBean(GrpcServletRegistration.class, SynapseGrpcAutoConfigurationTest::unusedRegistration,
                        definition -> definition.setLazyInit(true))
                .withBean(REST_CONTEXT_FILTER, Object.class, Object::new)
                .run(context -> assertThat(context).hasNotFailed().hasBean(SERVLET_CHECK));
    }

    @Test
    void bindingsCompileDuringStartupUnderLazyInitialization() {
        // A REST binding of the same operation, registered up front: the gRPC method must register before the check
        // runs. No gRPC server here, so nothing else would ask for the bindings during startup.
        lazyWithoutServer()
                .withBean("synapseBindingDeclarations", BindingDeclarations.class, () -> restBinding(null))
                .withBean("docs", GrpcBindings.class, SynapseGrpcAutoConfigurationTest::docs)
                .run(context -> assertThat(context).hasNotFailed());
        lazyWithoutServer()
                .withBean("synapseBindingDeclarations", BindingDeclarations.class, () -> restBinding("version"))
                .withBean("docs", GrpcBindings.class, SynapseGrpcAutoConfigurationTest::docs)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .isInstanceOf(SynapseConfigurationException.class)
                        .hasMessage("Operation 'get-doc' is bound differently over REST (GET /docs/{id}) and gRPC "
                                + "(synapse.fixture.v1.DocService/GetDoc): etag is version over REST and none over "
                                + "gRPC."));
    }

    @Test
    void aBindingMistakeFailsStartupUnderLazyInitialization() {
        lazyWithoutServer()
                .withBean("unbound", GrpcBindings.class, () -> GrpcBindings
                        .service(DocServiceGrpc.getServiceDescriptor())
                        .unary(DocServiceGrpc.getGetDocMethod(), new BindingFixtures.GetDocOperation())
                        .build())
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasStackTraceContaining("no operation is bound"));
    }

    @Test
    void bindingsLazyInitializationExcludeFilterIsPresentAndBacksOffByName() {
        runner.run(context -> {
            LazyInitializationExcludeFilter filter = context
                    .getBean("synapseGrpcBindingsLazyInitializationExcludeFilter",
                            LazyInitializationExcludeFilter.class);
            assertThat(filter.isExcluded("docs", new RootBeanDefinition(), GrpcBindings.class)).isTrue();
            assertThat(filter.isExcluded("other", new RootBeanDefinition(), BindableService.class)).isFalse();
        });
        LazyInitializationExcludeFilter user = (name, definition, type) -> false;
        runner
                .withBean("synapseGrpcBindingsLazyInitializationExcludeFilter", LazyInitializationExcludeFilter.class,
                        () -> user)
                .run(context -> assertThat(context.getBean("synapseGrpcBindingsLazyInitializationExcludeFilter"))
                        .isSameAs(user));
    }

    /**
     * The plugin and core's parity check under {@code spring.main.lazy-initialization=true}, without Boot's gRPC
     * server, which would otherwise ask for the bindings when it starts.
     */
    private static ApplicationContextRunner lazyWithoutServer() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations
                        .of(SynapseCoreAutoConfiguration.class, SynapseContextAutoConfiguration.class,
                                SynapseOperationAutoConfiguration.class, SynapseGrpcAutoConfiguration.class,
                                SynapseGrpcErrorAutoConfiguration.class))
                .withBean(ErrorMessages.class, () -> new ErrorMessages(new StaticMessageSource()))
                .withInitializer(context -> context
                        .addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()));
    }

    private static BindingDeclarations restBinding(@Nullable String etag) {
        BindingDeclarations declarations = new BindingDeclarations();
        declarations.add(new BindingDeclaration("REST", "get-doc", "GET /docs/{id}", "NOT_FOUND", etag, null, null));
        return declarations;
    }

    private static GrpcBindings docs() {
        return GrpcBindings
                .service(DocServiceGrpc.getServiceDescriptor())
                .unary(DocServiceGrpc.getGetDocMethod(), new BindingFixtures.GetDocOperation())
                .unary(DocServiceGrpc.getCreateDocMethod(), new BindingFixtures.CreateDocOperation())
                .unary(DocServiceGrpc.getUpdateDocMethod(), new BindingFixtures.UpdateDocOperation())
                .unary(DocServiceGrpc.getDeleteDocMethod(), new BindingFixtures.DeleteDocOperation())
                .build();
    }

    @Test
    void operationSettingsPrefixIsPresentAndBacksOffByName() {
        runner
                .run(context -> assertThat(context.getBean("synapseGrpcOperationSettingsPrefix"))
                        .isEqualTo(new OperationSettingsPrefix("synapse.api.grpc.operations")));
        OperationSettingsPrefix user = new OperationSettingsPrefix("user.operations");
        runner
                .withBean("synapseGrpcOperationSettingsPrefix", OperationSettingsPrefix.class, () -> user)
                .run(context -> assertThat(context.getBeansOfType(OperationSettingsPrefix.class))
                        .containsExactly(Map.entry("synapseGrpcOperationSettingsPrefix", user)));
    }

    @Test
    void servletCheckBacksOffToAUserBean() {
        SmartInitializingSingleton user = () -> {
        };
        runner
                .withBean(SERVLET_CHECK, SmartInitializingSingleton.class, () -> user)
                .withBean(GrpcServletRegistration.class, SynapseGrpcAutoConfigurationTest::unusedRegistration,
                        definition -> definition.setLazyInit(true))
                .withBean(REST_CONTEXT_FILTER, Object.class, Object::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SERVLET_CHECK)).isSameAs(user);
                });
    }

    @Test
    void reflectionAotCheckPassesOutsideAot() {
        // Reflection on with no service of the application: Boot defines no reflection bean, and outside AOT the
        // property decides at startup, so nothing is compared.
        runner
                .withPropertyValues("spring.grpc.server.reflection.enabled=true")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasBean(REFLECTION_AOT_CHECK)
                        .doesNotHaveBean("grpcServerReflectionService"));
    }

    @Test
    void reflectionAotCheckBacksOffToAUserBean() {
        BuildTimeSwitch user = () -> null;
        runner
                .withBean(REFLECTION_AOT_CHECK, BuildTimeSwitch.class, () -> user)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(REFLECTION_AOT_CHECK)).isSameAs(user);
                });
    }

    @Test
    void edgeInterceptorIsAGlobalInterceptorAtHighestPrecedence() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SynapseGrpcEdgeInterceptor.class);
            assertThat(context.getBeansWithAnnotation(GlobalServerInterceptor.class)).containsKey(EDGE_INTERCEPTOR);
            Order order = context.findAnnotationOnBean(EDGE_INTERCEPTOR, Order.class);
            assertThat(order).isNotNull();
            assertThat(Objects.requireNonNull(order).value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
            assertThat(context.getBean(EDGE_INTERCEPTOR, SynapseGrpcEdgeInterceptor.class).correlationKey().name())
                    .isEqualTo("x-correlation-id");
        });
    }

    @Test
    void edgeInterceptorReadsTheCorrelationHeaderInLowerCase() {
        runner
                .withPropertyValues("synapse.correlation.header=X-Request-Id")
                .run(context -> assertThat(context.getBean(SynapseGrpcEdgeInterceptor.class).correlationKey().name())
                        .isEqualTo("x-request-id"));
    }

    @Test
    void edgeInterceptorMasksWithTheConfiguredFields() {
        ApplicationContextRunner configured = runner
                .withPropertyValues("management.tracing.baggage.remote-fields=region",
                        "synapse.api.grpc.context.metadata.tenant=x-tenant");
        configured.run(context -> {
            MetadataMasking.Result result = context
                    .getBean(SynapseGrpcEdgeInterceptor.class)
                    .masking()
                    .apply(metadata("baggage", "correlationId=c-1,tenant=t1,partner=p", "region", "eu"));
            assertThat(result.candidates()).isEqualTo(Map.of("correlationId", "c-1", "tenant", "t1"));
            assertThat(result.metadata().getAll(key("baggage"))).containsExactly("partner=p");
            assertThat(result.metadata().get(key("region"))).isEqualTo("eu");
        });
        configured.withPropertyValues("synapse.context.inbound.trusted=false").run(context -> {
            MetadataMasking.Result result = context
                    .getBean(SynapseGrpcEdgeInterceptor.class)
                    .masking()
                    .apply(metadata("baggage", "partner=p", "region", "eu", "x-tenant", "acme"));
            assertThat(result.candidates()).isEmpty();
            assertThat(result.metadata().keys()).containsExactly("x-tenant");
        });
    }

    @Test
    void edgeInterceptorBacksOffByName() {
        SynapseGrpcEdgeInterceptor user = new SynapseGrpcEdgeInterceptor(new MetadataMasking(true, Set.of(), Set.of()),
                key("x-user"));
        runner.withBean(EDGE_INTERCEPTOR, SynapseGrpcEdgeInterceptor.class, () -> user).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SynapseGrpcEdgeInterceptor.class);
            assertThat(context.getBean(EDGE_INTERCEPTOR)).isSameAs(user);
        });
    }

    @Test
    void edgeInterceptorAbsentWithoutGrpc() {
        runner
                .withClassLoader(new FilteredClassLoader(GrpcServerFactory.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(EDGE_INTERCEPTOR));
    }

    @Test
    void contextInterceptorAndItsPoliciesActivate() {
        runner.run(context -> {
            assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(SynapseGrpcContextInterceptor.class)
                    .hasSingleBean(GrpcDeadlinePolicy.class)
                    .hasSingleBean(GrpcConcurrencyLimit.class);
            assertThat(context.getBeansWithAnnotation(GlobalServerInterceptor.class)).containsKey(CONTEXT_INTERCEPTOR);
        });
    }

    @Test
    void contextPoliciesBindTheProperties() {
        Clock fixed = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        runner
                .withBean("testClock", Clock.class, () -> fixed)
                .withPropertyValues("synapse.api.grpc.max-concurrent-calls=1", "synapse.api.grpc.deadline.default=5s",
                        "synapse.api.grpc.deadline.max=20s")
                .run(context -> {
                    GrpcDeadlinePolicy deadlines = context.getBean(GrpcDeadlinePolicy.class);
                    assertThat(deadlines.deadline(null)).isEqualTo(Instant.parse("2026-01-01T00:00:05Z"));
                    assertThat(deadlines.deadline(Deadline.after(1, TimeUnit.HOURS)))
                            .isEqualTo(Instant.parse("2026-01-01T00:00:20Z"));
                    GrpcConcurrencyLimit limit = context.getBean(GrpcConcurrencyLimit.class);
                    assertThat(limit.tryAcquire()).isTrue();
                    assertThat(limit.tryAcquire()).isFalse();
                });
    }

    @Test
    void contextPoliciesDefaultToTenAndThirtySecondsAndTwoHundredCalls() {
        Clock fixed = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        runner.withBean("testClock", Clock.class, () -> fixed).run(context -> {
            GrpcDeadlinePolicy deadlines = context.getBean(GrpcDeadlinePolicy.class);
            assertThat(deadlines.deadline(null)).isEqualTo(Instant.parse("2026-01-01T00:00:10Z"));
            assertThat(deadlines.deadline(Deadline.after(1, TimeUnit.HOURS)))
                    .isEqualTo(Instant.parse("2026-01-01T00:00:30Z"));
            GrpcConcurrencyLimit limit = context.getBean(GrpcConcurrencyLimit.class);
            for (int i = 0; i < 200; i++) {
                assertThat(limit.tryAcquire()).as("call %d", i + 1).isTrue();
            }
            assertThat(limit.tryAcquire()).isFalse();
        });
    }

    @Test
    void contextPoliciesBackOffToUserBeans() {
        GrpcDeadlinePolicy deadlines = new GrpcDeadlinePolicy(Duration.ofSeconds(1), Duration.ofSeconds(2),
                Clock.systemUTC());
        GrpcConcurrencyLimit limit = new GrpcConcurrencyLimit(7);
        runner
                .withBean("userDeadlines", GrpcDeadlinePolicy.class, () -> deadlines)
                .withBean("userLimit", GrpcConcurrencyLimit.class, () -> limit)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(GrpcDeadlinePolicy.class)).isSameAs(deadlines);
                    assertThat(context.getBean(GrpcConcurrencyLimit.class)).isSameAs(limit);
                });
    }

    @Test
    void interceptorsBackOffByName() {
        ServerInterceptor edge = new PassThrough();
        ServerInterceptor contextInterceptor = new PassThrough();
        runner
                .withBean(EDGE_INTERCEPTOR, ServerInterceptor.class, () -> edge)
                .withBean(CONTEXT_INTERCEPTOR, ServerInterceptor.class, () -> contextInterceptor)
                .run(context -> {
                    assertThat(context)
                            .hasNotFailed()
                            .doesNotHaveBean(SynapseGrpcEdgeInterceptor.class)
                            .doesNotHaveBean(SynapseGrpcContextInterceptor.class);
                    assertThat(context.getBean(EDGE_INTERCEPTOR)).isSameAs(edge);
                    assertThat(context.getBean(CONTEXT_INTERCEPTOR)).isSameAs(contextInterceptor);
                });
    }

    @Test
    void contextInterceptorAbsentWithoutGrpc() {
        runner
                .withClassLoader(new FilteredClassLoader(GrpcServerFactory.class))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(CONTEXT_INTERCEPTOR)
                        .doesNotHaveBean(GrpcDeadlinePolicy.class)
                        .doesNotHaveBean(GrpcConcurrencyLimit.class));
    }

    @Test
    void interceptorOrderAroundBoots() {
        serverRunner()
                .withConfiguration(AutoConfigurations.of(GrpcServerObservationAutoConfiguration.class))
                .withBean(ObservationRegistry.class, ObservationRegistry::create)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // The list Boot's DefaultGrpcServiceConfigurer applies; the first is the outermost.
                    List<ServerInterceptor> global = ApplicationContextBeanLookupUtils
                            .getBeansWithAnnotation(context, ServerInterceptor.class, GlobalServerInterceptor.class);
                    assertThat(global)
                            .extracting(Object::getClass)
                            .containsExactly(SynapseGrpcEdgeInterceptor.class, ObservationGrpcServerInterceptor.class,
                                    GrpcExceptionHandlerInterceptor.class, SynapseGrpcContextInterceptor.class);
                });
    }

    @Test
    void serviceCompilationBeansActivate() {
        runner
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(ServiceCompiler.class)
                        .hasSingleBean(GrpcBindingsCompiler.class)
                        .hasSingleBean(ExposureInventory.class));
    }

    @Test
    void serviceCompilationBeansBackOffToUserBeans() {
        ServiceCompiler compiler = Compilers.compiler("In a test slice, mock it.");
        GrpcBindingsCompiler postProcessor = new GrpcBindingsCompiler(
                new StaticListableBeanFactory().getBeanProvider(ServiceCompiler.class),
                operations(), new StaticListableBeanFactory().getBeanProvider(PageCursors.class),
                new StaticListableBeanFactory().getBeanProvider(SynapsePagingProperties.class),
                new StaticListableBeanFactory().getBeanProvider(BindingDeclarations.class));
        ExposureInventory inventory = new ExposureInventory(
                new StaticListableBeanFactory().getBeanProvider(GrpcBindings.class));
        runner
                .withBean(ServiceCompiler.class, () -> compiler)
                .withBean(GrpcBindingsCompiler.class, () -> postProcessor)
                .withBean(ExposureInventory.class, () -> inventory)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ServiceCompiler.class)).isSameAs(compiler);
                    assertThat(context.getBean(GrpcBindingsCompiler.class)).isSameAs(postProcessor);
                    assertThat(context.getBean(ExposureInventory.class)).isSameAs(inventory);
                });
    }

    @Test
    void serviceCompilationBeansAbsentWithoutGrpc() {
        runner
                .withClassLoader(new FilteredClassLoader(GrpcServerFactory.class))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(ServiceCompiler.class)
                        .doesNotHaveBean(GrpcBindingsCompiler.class)
                        .doesNotHaveBean(ExposureInventory.class));
    }

    @Test
    void aGrpcBindingsBeanIsCompiledWhenItIsCreated() {
        serverRunner()
                .withBean("catalog", GrpcBindings.class, () -> catalog(new BindingFixtures.GetItemOperation()))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("catalog", GrpcBindings.class).bindService().getMethods()).hasSize(6);
                });
    }

    @Test
    void aBindingProblemFailsStartup() {
        serverRunner()
                .withBean("catalog", GrpcBindings.class, () -> catalog(new BindingFixtures.OptionalPageOperation()))
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(SynapseConfigurationException.class)
                        .hasMessageContaining("synapse.fixture.v1.CatalogService/GetItem"));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ServiceOperation<?, ?>> operations() {
        return (ObjectProvider<ServiceOperation<?, ?>>) (ObjectProvider<?>) new StaticListableBeanFactory()
                .getBeanProvider(ServiceOperation.class);
    }

    /** Every method of the fixture's catalog service bound with {@code .unary}, {@code GetItem} to the given one. */
    private static GrpcBindings catalog(ServiceOperation<BindingFixtures.GetItem, ?> getItem) {
        return GrpcBindings
                .service(CatalogServiceGrpc.getServiceDescriptor())
                .unary(CatalogServiceGrpc.getGetItemMethod(), getItem)
                .unary(CatalogServiceGrpc.getCreateItemMethod(), new BindingFixtures.CreateItemOperation())
                .context(BindingFixtures.CreateItem::channel, context -> "grpc")
                .unary(CatalogServiceGrpc.getDeleteItemMethod(), new BindingFixtures.DeleteItemOperation())
                .unary(CatalogServiceGrpc.getPingMethod(), new BindingFixtures.PingOperation())
                .unary(CatalogServiceGrpc.getListTagsMethod(), new BindingFixtures.ListTagsOperation())
                .unary(CatalogServiceGrpc.getFailMethod(), new BindingFixtures.FailOperation())
                .build();
    }

    /** An interceptor that only passes calls on. */
    private static final class PassThrough implements ServerInterceptor {

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
                ServerCallHandler<ReqT, RespT> next) {
            return next.startCall(call, headers);
        }
    }

    private static Metadata.Key<String> key(String name) {
        return Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER);
    }

    private static Metadata metadata(String... pairs) {
        Metadata metadata = new Metadata();
        for (int i = 0; i < pairs.length; i += 2) {
            metadata.put(key(pairs[i]), pairs[i + 1]);
        }
        return metadata;
    }
}
