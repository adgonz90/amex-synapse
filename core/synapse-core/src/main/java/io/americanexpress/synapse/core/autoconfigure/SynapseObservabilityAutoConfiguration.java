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

import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import io.americanexpress.synapse.core.observability.SynapseObservations;

/**
 * Observability settings of Synapse (BUILD.md §5.7). In {@code synapse.observability.business=spans} mode (the
 * default), business calls are observed for tracing only, so their meters are denied.
 *
 * <p>
 * {@code @Scheduled} methods need nothing from Synapse: Boot 4.1.2 already registers the {@code ObservationRegistry}
 * with the scheduled task registrar ({@code ScheduledTasksObservationAutoConfiguration} in
 * {@code spring-boot-micrometer-observation}), so they are observed as {@code tasks.scheduled.execution}.
 */
@AutoConfiguration(after = SynapseCoreAutoConfiguration.class)
@ConditionalOnClass(MeterFilter.class)
public class SynapseObservabilityAutoConfiguration {

    /**
     * Denies the meters of {@value SynapseObservations#BUSINESS} observations in {@code spans} mode.
     *
     * @return the meter filter
     */
    @Bean
    @ConditionalOnMissingBean(name = "synapseBusinessMeterFilter")
    @ConditionalOnProperty(name = "synapse.observability.business", havingValue = "spans", matchIfMissing = true)
    MeterFilter synapseBusinessMeterFilter() {
        return MeterFilter.denyNameStartsWith(SynapseObservations.BUSINESS);
    }
}
