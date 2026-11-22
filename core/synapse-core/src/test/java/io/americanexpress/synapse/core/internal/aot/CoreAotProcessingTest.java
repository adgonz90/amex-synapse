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
package io.americanexpress.synapse.core.internal.aot;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.aot.test.generate.TestGenerationContext;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.NoopTracerAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.aot.ApplicationContextAotGenerator;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.test.tools.CompileWithForkedClassLoader;
import org.springframework.core.test.tools.TestCompiler;
import org.springframework.javapoet.ClassName;

import io.americanexpress.synapse.core.autoconfigure.SynapseContextAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseOperationAutoConfiguration;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.core.internal.aot.AotFixtures.Book;
import io.americanexpress.synapse.core.internal.aot.AotFixtures.GetBookOperation;
import io.americanexpress.synapse.core.internal.aot.AotFixtures.GetBookRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs Spring's AOT processing on an application with the core auto-configurations and one operation, compiles the
 * generated code and starts it, as a native image or a JVM with {@code -Dspring.aot.enabled=true} would.
 */
// Generated code calls package-private members, so it must be defined in the same class loader as the test classes.
@CompileWithForkedClassLoader
class CoreAotProcessingTest {

    private static final String SERVICES = "META-INF/services/io.micrometer.context.ThreadLocalAccessor";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations
                    .of(AopAutoConfiguration.class, ValidationAutoConfiguration.class,
                            MessageSourceAutoConfiguration.class, JacksonAutoConfiguration.class,
                            ObservationAutoConfiguration.class, OpenTelemetrySdkAutoConfiguration.class,
                            OpenTelemetryTracingAutoConfiguration.class, NoopTracerAutoConfiguration.class,
                            MicrometerTracingAutoConfiguration.class, SynapseCoreAutoConfiguration.class,
                            SynapseContextAutoConfiguration.class, SynapseOperationAutoConfiguration.class))
            .withUserConfiguration(Operations.class);

    @Test
    void operationRunsThePipelineInAotMode() {
        runner.prepare(context -> {
            TestGenerationContext generationContext = new TestGenerationContext();
            ClassName initializerName = new ApplicationContextAotGenerator()
                    .processAheadOfTime((GenericApplicationContext) context.getSourceApplicationContext(),
                            generationContext);
            generationContext.writeGeneratedContent();

            assertThat(RuntimeHintsPredicates.resource().forResource(SERVICES))
                    .accepts(generationContext.getRuntimeHints());
            assertThat(RuntimeHintsPredicates.reflection().onType(GetBookRequest.class))
                    .accepts(generationContext.getRuntimeHints());
            assertThat(RuntimeHintsPredicates.reflection().onType(Book.Author.class))
                    .accepts(generationContext.getRuntimeHints());

            TestCompiler.forSystem().with(generationContext).compile(compiled -> {
                try (GenericApplicationContext aotContext = new GenericApplicationContext()) {
                    @SuppressWarnings("unchecked")
                    ApplicationContextInitializer<GenericApplicationContext> initializer = compiled
                            .getInstance(ApplicationContextInitializer.class, initializerName.toString());
                    initializer.initialize(aotContext);
                    aotContext.refresh();

                    GetBookOperation operation = aotContext.getBean(GetBookOperation.class);
                    assertThat(AopUtils.isCglibProxy(operation)).isTrue();
                    assertThat(operation.execute(new GetBookRequest("1")))
                            .hasValue(new Book("1", new Book.Author("Ursula")));
                    assertThatThrownBy(() -> operation.execute(new GetBookRequest(" ")))
                            .isInstanceOfSatisfying(SynapseException.class, failure -> {
                                assertThat(failure.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED);
                                assertThat(failure.violations()).extracting(Violation::field).containsExactly("id");
                            });
                }
            });
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class Operations {

        @Bean
        GetBookOperation getBookOperation() {
            return new GetBookOperation();
        }
    }
}
