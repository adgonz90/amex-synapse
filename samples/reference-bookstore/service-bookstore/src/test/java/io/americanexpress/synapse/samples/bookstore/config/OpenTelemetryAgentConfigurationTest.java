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
package io.americanexpress.synapse.samples.bookstore.config;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassWriter;
import org.springframework.asm.Opcodes;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.americanexpress.synapse.core.SynapseConfigurationException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OpenTelemetryAgentConfiguration} with Boot's OpenTelemetry SDK auto-configuration: without the agent, Boot's
 * SDK is the {@link OpenTelemetry} bean; with the agent's class on the class path, the bean is the agent's
 * {@link GlobalOpenTelemetry} and Boot's SDK backs off. The agent itself is simulated by a class loader that defines
 * an empty class with the name of the agent's {@code AgentInitializer}; {@code BookstoreAgentIT} runs the real one.
 * {@link OpenTelemetryAgentAotConfiguration} fails an AOT run under the agent whose generated code lacks the bean;
 * {@code BookstoreAotIT} runs that case with the real agent.
 */
class OpenTelemetryAgentConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OpenTelemetryAgentConfiguration.class)
            .withConfiguration(AutoConfigurations.of(OpenTelemetrySdkAutoConfiguration.class));

    @BeforeEach
    @AfterEach
    void resetGlobalOpenTelemetry() {
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    void absentWithoutTheAgent() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean("openTelemetryAgent");
            assertThat(context).getBean(OpenTelemetry.class).isInstanceOf(OpenTelemetrySdk.class);
        });
    }

    @Test
    void activeWhenTheAgentClassIsPresent() {
        // What the agent does at startup: it installs its SDK as the global instance.
        ContextPropagators agentPropagators = ContextPropagators.create(W3CTraceContextPropagator.getInstance());
        GlobalOpenTelemetry.set(OpenTelemetry.propagating(agentPropagators));

        runner.withClassLoader(new AgentClassLoader(getClass().getClassLoader())).run(context -> {
            assertThat(context).hasSingleBean(OpenTelemetry.class).hasBean("openTelemetryAgent");
            OpenTelemetry bean = context.getBean(OpenTelemetry.class);
            assertThat(bean).isSameAs(GlobalOpenTelemetry.get()).isNotInstanceOf(OpenTelemetrySdk.class);
            assertThat(bean.getPropagators()).isSameAs(agentPropagators);
        });
    }

    /**
     * An AOT-processed jar started under the agent: AOT processing ran without the agent, so the generated code has
     * no {@code openTelemetryAgent} bean, which only {@link OpenTelemetryAgentAotConfiguration} is here to stand for.
     * Startup fails and says what to do, instead of running Boot's SDK next to the agent's.
     */
    @Test
    void aotRunUnderTheAgentWithoutItsBeanFails() {
        new ApplicationContextRunner()
                .withUserConfiguration(OpenTelemetryAgentAotConfiguration.class)
                .withClassLoader(new AgentClassLoader(getClass().getClassLoader()))
                .withSystemProperties("spring.aot.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOfSatisfying(SynapseConfigurationException.class, failure -> {
                                assertThat(failure.description())
                                        .isEqualTo(OpenTelemetryAgentAotConfiguration.DESCRIPTION);
                                assertThat(failure.action()).isEqualTo(OpenTelemetryAgentAotConfiguration.ACTION);
                            });
                });
    }

    /** An AOT-processed jar whose build ran with the agent's class, so the generated code has the bean. */
    @Test
    void aotRunUnderTheAgentWithItsBeanStarts() {
        GlobalOpenTelemetry.set(OpenTelemetry.noop());

        new ApplicationContextRunner()
                .withUserConfiguration(OpenTelemetryAgentConfiguration.class, OpenTelemetryAgentAotConfiguration.class)
                .withClassLoader(new AgentClassLoader(getClass().getClassLoader()))
                .withSystemProperties("spring.aot.enabled=true")
                .run(context -> assertThat(context).hasNotFailed().hasBean("openTelemetryAgent"));
    }

    /** On the JVM the condition is evaluated at run time, so the check has nothing to do, agent or not. */
    @Test
    void jvmRunUnderTheAgentIsUnaffected() {
        new ApplicationContextRunner()
                .withUserConfiguration(OpenTelemetryAgentAotConfiguration.class)
                .withClassLoader(new AgentClassLoader(getClass().getClassLoader()))
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** An AOT run without the agent: Boot's SDK is the one the generated code expects. */
    @Test
    void aotRunWithoutTheAgentStarts() {
        new ApplicationContextRunner()
                .withUserConfiguration(OpenTelemetryAgentAotConfiguration.class)
                .withSystemProperties("spring.aot.enabled=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** Defines an empty public class named as the agent's {@code AgentInitializer}, and delegates every other name. */
    private static final class AgentClassLoader extends ClassLoader {

        private static final String AGENT_CLASS = "io.opentelemetry.javaagent.bootstrap.AgentInitializer";

        AgentClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!AGENT_CLASS.equals(name)) {
                throw new ClassNotFoundException(name);
            }
            ClassWriter writer = new ClassWriter(0);
            writer
                    .visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, name.replace('.', '/'), null,
                            "java/lang/Object", null);
            writer.visitEnd();
            byte[] bytes = writer.toByteArray();
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
