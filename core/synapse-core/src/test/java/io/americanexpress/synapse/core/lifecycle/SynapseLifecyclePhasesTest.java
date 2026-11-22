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
package io.americanexpress.synapse.core.lifecycle;

import org.junit.jupiter.api.Test;
import org.springframework.context.SmartLifecycle;

import static org.assertj.core.api.Assertions.assertThat;

class SynapseLifecyclePhasesTest {

    @Test
    void phasesAreOrderedForShutdown() {
        assertThat(SynapseLifecyclePhases.INBOUND)
                .isGreaterThan(SynapseLifecyclePhases.DRAIN)
                .isEqualTo(SmartLifecycle.DEFAULT_PHASE - 2048);
        assertThat(SynapseLifecyclePhases.DRAIN)
                .isGreaterThan(SynapseLifecyclePhases.PUBLISHERS)
                .isEqualTo(SmartLifecycle.DEFAULT_PHASE - 4096);
        assertThat(SynapseLifecyclePhases.PUBLISHERS)
                .isGreaterThan(SynapseLifecyclePhases.CLIENTS)
                .isEqualTo(SmartLifecycle.DEFAULT_PHASE - 8192);
        assertThat(SynapseLifecyclePhases.CLIENTS)
                .isGreaterThan(SynapseLifecyclePhases.DATA_STORES)
                .isEqualTo(SmartLifecycle.DEFAULT_PHASE - 9216);
        assertThat(SynapseLifecyclePhases.DATA_STORES).isEqualTo(SmartLifecycle.DEFAULT_PHASE - 10240);
    }
}
