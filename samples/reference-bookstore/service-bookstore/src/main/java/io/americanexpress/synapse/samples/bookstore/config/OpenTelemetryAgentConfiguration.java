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
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenTelemetry Java agent recipe: when the service runs under the agent ({@code java -javaagent:...}), Boot's
 * OpenTelemetry bridge gets the agent's SDK, {@link GlobalOpenTelemetry#get()}, so Synapse's baggage, Boot's
 * observations and the agent's own instrumentation share one SDK, one exporter configuration and one trace. Boot's
 * own SDK backs off, because it is created only when no {@link OpenTelemetry} bean exists. Without the agent this
 * configuration does nothing, and Boot's SDK is the one in use.
 *
 * <p>
 * The condition is a class of the agent itself, which {@code -javaagent:} puts on the class path, not a Spring
 * profile: whether the agent runs is decided by how the JVM starts, never by the environment. On the JVM it is
 * evaluated at every start, so the same jar runs with or without the agent. Spring's AOT processing ({@code -Paot})
 * evaluates it once, at build time, without the agent: an AOT-processed jar run in AOT mode never has this bean, and
 * {@link OpenTelemetryAgentAotConfiguration} fails such a start under the agent. See
 * {@code docs/modules/ROOT/pages/opentelemetry-agent.adoc}.
 *
 * <p>
 * This is application configuration, so it has no {@code @ConditionalOnMissingBean} back-off. Moved into a library
 * or an auto-configuration, it would need one, and an {@code ApplicationContextRunner} test that it backs off.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = OpenTelemetryAgentConfiguration.AGENT_CLASS)
public class OpenTelemetryAgentConfiguration {

    /** A class of the OpenTelemetry Java agent's jar, present only when the JVM runs the agent. */
    static final String AGENT_CLASS = "io.opentelemetry.javaagent.bootstrap.AgentInitializer";

    /**
     * Returns the agent's OpenTelemetry, which the agent installs as the global instance before the application
     * starts.
     *
     * @return the agent's OpenTelemetry
     */
    @Bean
    OpenTelemetry openTelemetryAgent() {
        return GlobalOpenTelemetry.get();
    }
}
