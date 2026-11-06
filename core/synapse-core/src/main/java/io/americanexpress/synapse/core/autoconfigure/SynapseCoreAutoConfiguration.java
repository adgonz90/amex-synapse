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

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.core.masking.LogMasking;

/**
 * Auto-configuration for the Synapse core: its properties, the application {@link Clock} and the
 * {@link LogMasking}. Registered in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 */
@AutoConfiguration(afterName = "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration")
@EnableConfigurationProperties({SynapseErrorProperties.class, SynapsePagingProperties.class,
        SynapseObservabilityProperties.class, SynapseOperationsProperties.class})
public class SynapseCoreAutoConfiguration {

    /**
     * The clock Synapse reads the time from, in UTC. Tests replace it with a fixed or mutable clock.
     *
     * @return the system clock in UTC
     */
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Writes payloads for logs with {@code @Masked} members masked. It follows Boot's {@link JsonMapper} (naming
     * strategy and other {@code spring.jackson.*} settings) when there is a single or primary one, without changing
     * that mapper; otherwise it uses its own standard mapper.
     *
     * @param jsonMapper Boot's mapper, if there is a single or primary one
     * @return the log masker
     */
    @Bean
    @ConditionalOnMissingBean
    LogMasking logMasking(ObjectProvider<JsonMapper> jsonMapper) {
        JsonMapper base = jsonMapper.getIfUnique();
        return base != null ? new LogMasking(base) : LogMasking.standard();
    }
}
