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

import java.util.Map;

import io.micrometer.tracing.Baggage;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

import io.americanexpress.synapse.core.environment.SynapseEnvironment;
import io.americanexpress.synapse.core.internal.context.ContextBinder;
import io.americanexpress.synapse.core.internal.context.ContextScope;
import io.americanexpress.synapse.core.internal.context.InboundContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots a real application to prove the auto-configurations and {@code spring.factories} entries are registered
 * rather than only being loadable when referenced directly.
 */
@Tag("smoke")
@SpringBootTest(classes = SynapseCoreAutoConfigurationIT.TestApplication.class, properties = {
        "synapse.api.rest.context.headers.tenant=X-Tenant",
        "management.logging.export.otlp.enabled=false", "management.tracing.export.otlp.enabled=false",
        "management.otlp.metrics.export.enabled=false"})
class SynapseCoreAutoConfigurationIT {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @Test
    void coreAutoConfigurationIsAppliedThroughImportsFile() {
        assertThat(context.getBeansOfType(SynapseCoreAutoConfiguration.class)).hasSize(1);
        assertThat(context.getBeansOfType(SynapseContextAutoConfiguration.class)).hasSize(1);
        assertThat(context.getBean(SynapseErrorProperties.class).includeDetail()).isNull();
        assertThat(context
                .getBean(SynapseErrorProperties.class)
                .includeDetail(context.getBean(SynapseEnvironment.class))).isFalse();
        assertThat(context.getBean(SynapseEnvironment.class).isProduction()).isTrue();
        assertThat(context.getBean(TaskDecorator.class)).isInstanceOf(ContextPropagatingTaskDecorator.class);
    }

    @Test
    void baggageFieldsArePostProcessedThroughSpringFactories() {
        assertThat(environment.getProperty("management.tracing.baggage.remote-fields"))
                .isEqualTo("correlationId,tenant");
        assertThat(environment.getProperty("management.tracing.baggage.correlation.fields"))
                .isEqualTo("correlationId,tenant");
    }

    @Test
    void contextBinderOpensRemoteBaggageOnTheOpenTelemetryTracer() {
        Tracer tracer = context.getBean(Tracer.class);
        assertThat(tracer).isInstanceOf(OtelTracer.class);
        ContextBinder binder = context.getBean(ContextBinder.class);
        try (ContextScope scope = binder
                .open(new InboundContext("corr-it", Map.of("tenant", "t1"), null, null, null))) {
            Baggage correlation = tracer.getBaggage("correlationId");
            Baggage tenant = tracer.getBaggage("tenant");
            assertThat(correlation).isNotNull();
            assertThat(correlation.get()).isEqualTo("corr-it");
            assertThat(tenant).isNotNull();
            assertThat(tenant.get()).isEqualTo("t1");
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }
}
