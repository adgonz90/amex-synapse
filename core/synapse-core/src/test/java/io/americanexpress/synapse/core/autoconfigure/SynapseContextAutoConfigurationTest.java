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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import org.assertj.core.api.Condition;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.NoopTracerAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.context.RequestContext;
import io.americanexpress.synapse.core.internal.context.ContextBinder;
import io.americanexpress.synapse.core.internal.context.ContextScope;
import io.americanexpress.synapse.core.internal.context.CorrelationIdPolicy;
import io.americanexpress.synapse.core.internal.context.InboundContext;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@ExtendWith(OutputCaptureExtension.class)
class SynapseContextAutoConfigurationTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations
                    .of(ObservationAutoConfiguration.class, OpenTelemetrySdkAutoConfiguration.class,
                            OpenTelemetryTracingAutoConfiguration.class, NoopTracerAutoConfiguration.class,
                            MicrometerTracingAutoConfiguration.class, SynapseCoreAutoConfiguration.class,
                            SynapseContextAutoConfiguration.class));

    private final ApplicationContextRunner noopTracerRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations
                    .of(NoopTracerAutoConfiguration.class, SynapseCoreAutoConfiguration.class,
                            SynapseContextAutoConfiguration.class));

    @Test
    void registersContextBeansAndClock() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ContextBinder.class);
            assertThat(context).hasSingleBean(CorrelationIdPolicy.class);
            assertThat(context.getBean(TaskDecorator.class)).isInstanceOf(ContextPropagatingTaskDecorator.class);
            assertThat(context.getBean("synapseContextPropagatingTaskDecorator"))
                    .isInstanceOf(ContextPropagatingTaskDecorator.class);
            assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
            assertThat(context.getBean(Tracer.class)).isInstanceOf(OtelTracer.class);
            assertThat(context.getBean(SynapseCorrelationProperties.class).header()).isEqualTo("X-Correlation-Id");
            assertThat(context.getBean(SynapseCorrelationProperties.class).maxLength()).isEqualTo(128);
            SynapseContextProperties.Inbound inbound = context.getBean(SynapseContextProperties.class).inbound();
            assertThat(inbound.trusted()).isTrue();
            assertThat(inbound.maxEntries()).isEqualTo(16);
            assertThat(inbound.maxSize().toBytes()).isEqualTo(4096);
        });
    }

    @Test
    void backsOffToUserBeans() {
        contextRunner.withUserConfiguration(UserBeans.class).run(context -> {
            assertThat(context.getBean(Clock.class)).isSameAs(UserBeans.CLOCK);
            assertThat(context.getBean(CorrelationIdPolicy.class)).isSameAs(UserBeans.POLICY);
            assertThat(context.getBean(ContextBinder.class)).isSameAs(UserBeans.BINDER);
        });
    }

    @Test
    void bindsContextAndCorrelationProperties() {
        contextRunner
                .withPropertyValues("synapse.context.inbound.max-entries=3", "synapse.context.inbound.max-size=1KB",
                        "synapse.context.inbound.trusted=false", "synapse.correlation.max-length=64",
                        "synapse.correlation.header=X-Request-Id")
                .run(context -> {
                    SynapseContextProperties.Inbound inbound = context
                            .getBean(SynapseContextProperties.class)
                            .inbound();
                    assertThat(inbound.maxEntries()).isEqualTo(3);
                    assertThat(inbound.maxSize().toBytes()).isEqualTo(1024);
                    assertThat(inbound.trusted()).isFalse();
                    assertThat(context.getBean(SynapseCorrelationProperties.class).header())
                            .isEqualTo("X-Request-Id");

                    ContextBinder binder = context.getBean(ContextBinder.class);
                    String sixtyFour = "a".repeat(64);
                    assertThat(correlationIdOf(binder, sixtyFour, Map.of())).isEqualTo(sixtyFour);
                    assertThat(correlationIdOf(binder, sixtyFour + "a", Map.of())).isNotEqualTo(sixtyFour + "a");
                    assertThat(attributesOf(binder, Map.of("tenant", "t1"))).isEmpty();
                });
    }

    @Test
    void bindsInboundLimits() {
        contextRunner
                .withPropertyValues("synapse.context.inbound.max-entries=3", "synapse.context.inbound.max-size=1KB")
                .run(context -> {
                    ContextBinder binder = context.getBean(ContextBinder.class);
                    Map<String, String> four = new LinkedHashMap<>();
                    four.put("a", "1");
                    four.put("b", "2");
                    four.put("c", "3");
                    four.put("d", "4");
                    assertThat(attributesOf(binder, four)).containsOnlyKeys("a", "b", "c");
                    assertThat(attributesOf(binder, Map.of("big", "x".repeat(1022)))).isEmpty();
                    assertThat(attributesOf(binder, Map.of("big", "x".repeat(1021)))).containsOnlyKeys("big");
                });
    }

    @Test
    void rejectsInvalidContextAndCorrelationProperties() {
        contextRunner
                .withPropertyValues("synapse.correlation.max-length=0")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("synapse.context.inbound.max-entries=-1")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("synapse.context.inbound.max-size=-1B")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("synapse.correlation.max-length=1", "synapse.context.inbound.max-entries=0",
                        "synapse.context.inbound.max-size=0B")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void failsWithoutRealTracerWhenOperationsExist() {
        noopTracerRunner.withUserConfiguration(OperationConfig.class).run(context -> {
            assertThat(context).hasFailed();
            Throwable root = rootCause(context.getStartupFailure());
            assertThat(root).isInstanceOf(SynapseConfigurationException.class);
            SynapseConfigurationException failure = (SynapseConfigurationException) root;
            assertThat(failure.description()).contains("Tracer");
            assertThat(failure.action())
                    .isEqualTo("Add the OpenTelemetry tracing bridge, "
                            + "org.springframework.boot:spring-boot-starter-opentelemetry, to the service module. If "
                            + "you run the OpenTelemetry Java agent, keep that starter and define an OpenTelemetry "
                            + "bean that returns GlobalOpenTelemetry.get(), so the bridge uses the agent's SDK. To "
                            + "start without the check, set synapse.observability.require-tracing=false; the "
                            + "correlation id and context attributes then reach neither the logs nor outbound calls.");
        });
    }

    @Test
    void tracingCheckIsRegistered() {
        contextRunner.run(context -> assertThat(context).hasBean("synapseTracingRequirementCheck"));
    }

    @Test
    void tracingCheckBacksOffToAUserBeanOfTheSameName() {
        noopTracerRunner
                .withUserConfiguration(OperationConfig.class)
                .withBean("synapseTracingRequirementCheck", SmartInitializingSingleton.class, () -> () -> {
                })
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(TestQuery.class));
    }

    @Test
    void requireTracingFalseSkipsTheCheckWithOneInfoLine(CapturedOutput output) {
        noopTracerRunner
                .withUserConfiguration(OperationConfig.class)
                .withPropertyValues("synapse.observability.require-tracing=false")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(TestQuery.class));

        assertThat(output.getOut().lines().filter(line -> line.contains("require-tracing")))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .contains(" INFO ", "Synapse's tracing check is off (synapse.observability.require-tracing="
                                + "false): the application has only the no-op Tracer (Tracer.NOOP), so the "
                                + "correlation id and context attributes reach neither the logs nor outbound calls."));
        assertThat(output.getOut()).doesNotContain(" WARN ", " ERROR ");
    }

    @Test
    void requireTracingFalseLogsNothingWhenTracingWorks(CapturedOutput output) {
        contextRunner
                .withUserConfiguration(OperationConfig.class)
                .withPropertyValues("synapse.observability.require-tracing=false")
                .run(context -> assertThat(context).hasNotFailed());

        assertThat(output.getOut()).doesNotContain("require-tracing");
    }

    @Test
    void namesOtelPropagatorsWhenItDisabledBaggage() {
        contextRunner
                .withUserConfiguration(OperationConfig.class)
                .withInitializer(context -> context
                        .getEnvironment()
                        .getPropertySources()
                        .addFirst(new MapPropertySource("openTelemetryEnvironmentVariables",
                                Map.of("management.tracing.baggage.enabled", "false"))))
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable root = rootCause(context.getStartupFailure());
                    assertThat(root).isInstanceOfSatisfying(SynapseConfigurationException.class, failure -> {
                        assertThat(failure.description())
                                .isEqualTo("Synapse service operations carry the correlation id and context "
                                        + "attributes as tracing baggage, but baggage is disabled: Spring Boot set "
                                        + "management.tracing.baggage.enabled=false from the OTEL_PROPAGATORS "
                                        + "environment variable, which does not include 'baggage'.");
                        assertThat(failure.action())
                                .isEqualTo("Add 'baggage' to OTEL_PROPAGATORS (for example tracecontext,baggage). If "
                                        + "the variable is meant for the OpenTelemetry Java agent, configure the "
                                        + "agent with -Dotel.propagators instead and set "
                                        + "management.opentelemetry.map-environment-variables=false.");
                    });
                });
    }

    @Test
    void failsWithTwoTracersWhenOperationsExist() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations
                        .of(SynapseCoreAutoConfiguration.class, SynapseContextAutoConfiguration.class))
                .withUserConfiguration(OperationConfig.class)
                .withBean("firstTracer", Tracer.class, () -> mock(Tracer.class))
                .withBean("secondTracer", Tracer.class, () -> mock(Tracer.class))
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable root = rootCause(context.getStartupFailure());
                    assertThat(root).isInstanceOf(SynapseConfigurationException.class);
                    assertThat(((SynapseConfigurationException) root).description())
                            .contains("no single Tracer bean");
                });
    }

    @Test
    void bootContextPropagationReplacesSynapseDecorator() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
                .withUserConfiguration(AsyncConfig.class)
                .withPropertyValues("spring.task.execution.propagate-context=true")
                .run(context -> {
                    assertThat(context)
                            .hasNotFailed()
                            .doesNotHaveBean("synapseContextPropagatingTaskDecorator")
                            .hasBean("contextPropagatingTaskDecorator");
                    assertThat(context.getBeansOfType(TaskDecorator.class)).hasSize(1);
                    List<String> seen = callAsync(context.getBean(ContextBinder.class),
                            context.getBean(AsyncService.class));
                    assertThat(seen).containsExactly("corr-async", "false", "null");
                });
    }

    @Test
    void failsWithoutAnyTracerWhenOperationsExist() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations
                        .of(SynapseCoreAutoConfiguration.class, SynapseContextAutoConfiguration.class))
                .withUserConfiguration(OperationConfig.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context.getStartupFailure()))
                            .isInstanceOf(SynapseConfigurationException.class);
                });
    }

    @Test
    void failsWhenBaggageDisabled() {
        contextRunner
                .withUserConfiguration(OperationConfig.class)
                .withPropertyValues("management.tracing.baggage.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable root = rootCause(context.getStartupFailure());
                    assertThat(root).isInstanceOf(SynapseConfigurationException.class);
                    SynapseConfigurationException failure = (SynapseConfigurationException) root;
                    assertThat(failure.description()).contains("management.tracing.baggage.enabled");
                    assertThat(failure.action()).contains("management.tracing.baggage.enabled");
                });
    }

    @Test
    void startsWithRealTracerAndOperations() {
        contextRunner
                .withUserConfiguration(OperationConfig.class)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(TestQuery.class));
    }

    @Test
    void noTracingCheckWithoutOperations() {
        noopTracerRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(Tracer.class)).isSameAs(Tracer.NOOP);
            assertThat(context).hasSingleBean(ContextBinder.class);
        });
    }

    @Test
    void absentWithoutMicrometerTracing() {
        noopTracerRunner
                .withClassLoader(new FilteredClassLoader(Tracer.class))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(ContextBinder.class)
                        .doesNotHaveBean(CorrelationIdPolicy.class)
                        .doesNotHaveBean(TaskDecorator.class));
    }

    @Test
    void asyncTaskSeesRequestContext() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
                .withUserConfiguration(AsyncConfig.class)
                .run(context -> {
                    List<String> seen = callAsync(context.getBean(ContextBinder.class),
                            context.getBean(AsyncService.class));
                    assertThat(seen).containsExactly("corr-async", "false", "null");
                });
    }

    @Test
    void asyncTaskSeesRequestContextOnVirtualThreads() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
                .withUserConfiguration(AsyncConfig.class)
                .withPropertyValues("spring.threads.virtual.enabled=true")
                .run(context -> {
                    List<String> seen = callAsync(context.getBean(ContextBinder.class),
                            context.getBean(AsyncService.class));
                    assertThat(seen).containsExactly("corr-async", "true", "null");
                });
    }

    @Test
    void composesWithAnUnrelatedUserTaskDecorator() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
                .withUserConfiguration(AsyncConfig.class, MarkingDecoratorConfig.class)
                .run(context -> {
                    assertThat(context.getBeansOfType(TaskDecorator.class))
                            .hasSize(2)
                            .containsEntry("markingTaskDecorator", MarkingDecoratorConfig.DECORATOR)
                            .hasValueSatisfying(new Condition<>(ContextPropagatingTaskDecorator.class::isInstance,
                                    "a context-propagating decorator"));
                    List<String> seen = callAsync(context.getBean(ContextBinder.class),
                            context.getBean(AsyncService.class));
                    assertThat(seen).containsExactly("corr-async", "false", "user-decorated");
                });
    }

    @Test
    void backsOffToAUserContextPropagatingTaskDecorator() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
                .withUserConfiguration(AsyncConfig.class, UserContextDecoratorConfig.class)
                .run(context -> {
                    assertThat(context.getBeansOfType(TaskDecorator.class))
                            .containsOnly(Map.entry("userContextDecorator", UserContextDecoratorConfig.DECORATOR));
                    List<String> seen = callAsync(context.getBean(ContextBinder.class),
                            context.getBean(AsyncService.class));
                    assertThat(seen).containsExactly("corr-async", "false", "null");
                });
    }

    private static List<String> callAsync(ContextBinder binder, AsyncService service) throws Exception {
        CompletableFuture<List<String>> result;
        Thread caller = Thread.currentThread();
        try (ContextScope scope = binder.open(new InboundContext("corr-async", Map.of(), null, null, null))) {
            result = service.correlationId(caller);
        }
        return result.get(10, TimeUnit.SECONDS);
    }

    private static String correlationIdOf(ContextBinder binder, String candidate, Map<String, String> attributes) {
        try (ContextScope scope = binder.open(new InboundContext(candidate, attributes, null, null, null))) {
            return scope.context().correlationId();
        }
    }

    private static Map<String, String> attributesOf(ContextBinder binder, Map<String, String> attributes) {
        try (ContextScope scope = binder.open(new InboundContext(null, attributes, null, null, null))) {
            return scope.context().attributes();
        }
    }

    private static Throwable rootCause(@Nullable Throwable failure) {
        return NestedExceptionUtils.getMostSpecificCause(Objects.requireNonNull(failure));
    }

    @Configuration(proxyBeanMethods = false)
    static class UserBeans {

        static final Clock CLOCK = FIXED;
        static final CorrelationIdPolicy POLICY = new CorrelationIdPolicy(10);
        static final ContextBinder BINDER = new ContextBinder(Tracer.NOOP, POLICY, true, 1, 10);

        @Bean
        Clock userClock() {
            return CLOCK;
        }

        @Bean
        CorrelationIdPolicy userPolicy() {
            return POLICY;
        }

        @Bean
        ContextBinder userBinder() {
            return BINDER;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OperationConfig {

        @Bean
        TestQuery testQuery() {
            return new TestQuery();
        }
    }

    static class TestQuery implements QueryOperation<String, String> {

        @Override
        public String execute(String input) {
            return input;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    static class AsyncConfig {

        @Bean
        AsyncService asyncService() {
            return new AsyncService();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MarkingDecoratorConfig {

        static final ThreadLocal<String> MARKER = new ThreadLocal<>();

        static final TaskDecorator DECORATOR = runnable -> () -> {
            MARKER.set("user-decorated");
            try {
                runnable.run();
            } finally {
                MARKER.remove();
            }
        };

        @Bean
        TaskDecorator markingTaskDecorator() {
            return DECORATOR;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserContextDecoratorConfig {

        static final ContextPropagatingTaskDecorator DECORATOR = new ContextPropagatingTaskDecorator();

        @Bean
        ContextPropagatingTaskDecorator userContextDecorator() {
            return DECORATOR;
        }
    }

    static class AsyncService {

        @Async
        public CompletableFuture<List<String>> correlationId(Thread caller) {
            if (Thread.currentThread() == caller) {
                throw new IllegalStateException("Expected to run on another thread");
            }
            return CompletableFuture
                    .completedFuture(List
                            .of(RequestContext.current().correlationId(),
                                    String.valueOf(Thread.currentThread().isVirtual()),
                                    String.valueOf(MarkingDecoratorConfig.MARKER.get())));
        }
    }
}
