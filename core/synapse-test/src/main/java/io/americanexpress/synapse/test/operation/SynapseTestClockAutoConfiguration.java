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
package io.americanexpress.synapse.test.operation;

import java.time.Clock;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.test.time.MutableClock;

/**
 * Registers a {@link MutableClock} fixed at {@code 2026-01-01T00:00:00Z} as the application {@link Clock} in
 * {@link SynapseOperationTest} unless the test defines a {@link Clock}. It is ordered before Synapse core's
 * auto-configuration, which would otherwise register the system clock.
 */
@AutoConfiguration(before = SynapseCoreAutoConfiguration.class)
public class SynapseTestClockAutoConfiguration {

    /** The instant the test clock starts at. */
    static final String START = "2026-01-01T00:00:00Z";

    /**
     * Creates the test clock.
     *
     * @return a mutable clock at {@code 2026-01-01T00:00:00Z}
     */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    MutableClock synapseTestClock() {
        return MutableClock.at(START);
    }
}
