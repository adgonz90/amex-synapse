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

import org.springframework.aot.AotDetector;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;

import io.americanexpress.synapse.core.SynapseConfigurationException;

/**
 * The OpenTelemetry Java agent recipe's guard for AOT mode. Spring's AOT processing evaluates the conditions at build
 * time, without the agent, so in the generated code {@link OpenTelemetryAgentConfiguration}'s
 * {@code @ConditionalOnClass} is fixed as false: an AOT-processed jar started under the agent has no
 * {@code openTelemetryAgent} bean, and Boot would start its own OpenTelemetry SDK next to the agent's. This check fails
 * that start instead, with what to do. It has no condition, so the generated code always has it; on the JVM, without
 * AOT mode, the condition is evaluated at run time and the check does nothing.
 */
@Configuration(proxyBeanMethods = false)
public class OpenTelemetryAgentAotConfiguration {

    /** The bean of {@link OpenTelemetryAgentConfiguration}. */
    static final String AGENT_BEAN = "openTelemetryAgent";

    static final String DESCRIPTION = "The OpenTelemetry Java agent is running, but this AOT-processed application was "
            + "built without it: AOT processing fixed OpenTelemetryAgentConfiguration's @ConditionalOnClass at build "
            + "time, so Spring Boot would start a second OpenTelemetry SDK instead of using the agent's.";

    static final String ACTION = "To run under the agent, start the jar without -Dspring.aot.enabled=true, or don't "
            + "build with -Paot.";

    /**
     * Fails startup in AOT mode when the agent's class is present but the agent's {@code OpenTelemetry} bean is not.
     *
     * @param beanFactory the bean factory, for the bean and its class loader
     * @return the check, run once all singletons exist
     */
    @Bean
    static SmartInitializingSingleton openTelemetryAgentAotCheck(ConfigurableListableBeanFactory beanFactory) {
        return () -> {
            if (AotDetector.useGeneratedArtifacts()
                    && ClassUtils
                            .isPresent(OpenTelemetryAgentConfiguration.AGENT_CLASS, beanFactory.getBeanClassLoader())
                    && !beanFactory.containsBean(AGENT_BEAN)) {
                throw new SynapseConfigurationException(DESCRIPTION, ACTION);
            }
        };
    }
}
