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

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ScheduledTasksObservationAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.SchedulingConfigurer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class SynapseObservabilityAutoConfigurationTest {

    private static final String FILTER = "synapseBusinessMeterFilter";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations
                    .of(ObservationAutoConfiguration.class, MetricsAutoConfiguration.class,
                            CompositeMeterRegistryAutoConfiguration.class,
                            SimpleMetricsExportAutoConfiguration.class, SynapseCoreAutoConfiguration.class,
                            SynapseObservabilityAutoConfiguration.class));

    @Test
    void spansModeDeniesBusinessMeters() {
        contextRunner.run(context -> {
            assertThat(context).hasBean(FILTER);
            observe(context.getBean(ObservationRegistry.class));
            MeterRegistry meters = context.getBean(MeterRegistry.class);
            assertThat(meters.find("synapse.business").meters()).isEmpty();
            assertThat(meters.find("synapse.operation").timer()).isNotNull();
        });
    }

    @Test
    void spansModeIsMatchedWhateverTheCase() {
        contextRunner
                .withPropertyValues("synapse.observability.business=SPANS")
                .run(context -> assertThat(context).hasBean(FILTER));
    }

    @Test
    void metricsModeHasNoFilter() {
        contextRunner.withPropertyValues("synapse.observability.business=metrics").run(context -> {
            assertThat(context).doesNotHaveBean(FILTER);
            observe(context.getBean(ObservationRegistry.class));
            assertThat(context.getBean(MeterRegistry.class).find("synapse.business").timer()).isNotNull();
        });
    }

    @Test
    void offModeHasNoFilter() {
        contextRunner
                .withPropertyValues("synapse.observability.business=off")
                .run(context -> assertThat(context).doesNotHaveBean(FILTER));
    }

    @Test
    void backsOffToUserFilter() {
        contextRunner.withUserConfiguration(UserFilter.class).run(context -> {
            assertThat(context.getBean(FILTER)).isSameAs(UserFilter.FILTER);
            observe(context.getBean(ObservationRegistry.class));
            assertThat(context.getBean(MeterRegistry.class).find("synapse.business").timer()).isNotNull();
        });
    }

    @Test
    void absentWithoutMicrometerMetrics() {
        contextRunner
                .withClassLoader(new FilteredClassLoader(MeterFilter.class))
                .run(context -> assertThat(context).doesNotHaveBean(FILTER));
    }

    @Test
    void scheduledMethodsAreObserved() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations
                        .of(ObservationAutoConfiguration.class, ScheduledTasksObservationAutoConfiguration.class,
                                TaskSchedulingAutoConfiguration.class, SynapseCoreAutoConfiguration.class,
                                SynapseObservabilityAutoConfiguration.class))
                .withUserConfiguration(ScheduledJob.class)
                .run(context -> {
                    assertThat(context.getBean(ScheduledJob.class).ran.await(10, TimeUnit.SECONDS)).isTrue();
                    TestObservationRegistry registry = context.getBean(TestObservationRegistry.class);
                    await()
                            .atMost(Duration.ofSeconds(10))
                            .untilAsserted(() -> TestObservationRegistryAssert
                                    .assertThat(registry)
                                    .hasObservationWithNameEqualTo("tasks.scheduled.execution")
                                    .that()
                                    .hasBeenStopped());
                    // Boot observes @Scheduled methods itself; Synapse adds no scheduling configurer of its own.
                    assertThat(context.getBeansOfType(SchedulingConfigurer.class).values())
                            .noneMatch(configurer -> configurer
                                    .getClass()
                                    .getName()
                                    .startsWith("io.americanexpress.synapse"));
                });
    }

    private static void observe(ObservationRegistry registry) {
        Observation.start("synapse.business", registry).stop();
        Observation.start("synapse.operation", registry).stop();
    }

    @Configuration(proxyBeanMethods = false)
    static class UserFilter {

        static final MeterFilter FILTER = MeterFilter.acceptNameStartsWith("synapse");

        @Bean
        MeterFilter synapseBusinessMeterFilter() {
            return FILTER;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class ScheduledJob {

        final CountDownLatch ran = new CountDownLatch(1);

        @Bean
        TestObservationRegistry observationRegistry() {
            return TestObservationRegistry.create();
        }

        @Scheduled(fixedDelay = 10)
        void run() {
            ran.countDown();
        }
    }
}
