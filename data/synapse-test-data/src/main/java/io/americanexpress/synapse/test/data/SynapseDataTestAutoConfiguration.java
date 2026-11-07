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
package io.americanexpress.synapse.test.data;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration of {@link SynapseDataTest}: the bean {@value #SLICE} that marks a context as the data slice. The
 * slice leaves out the stores it doesn't test, so with the marker present the data stores' checks expect the settings
 * of stores that no {@code @SynapseDataStore} in the context declares. It is imported by the slice only, never by an
 * application.
 */
@AutoConfiguration
public final class SynapseDataTestAutoConfiguration {

    /** The marker's bean name, which the data stores' checks look for. */
    static final String SLICE = "synapseDataTestSlice";

    SynapseDataTestAutoConfiguration() {
    }

    /**
     * The marker. It is not a default candidate, so nothing that injects by type receives it. Define a bean with the
     * same name to replace it.
     *
     * @return the marker
     */
    @Bean(name = SLICE, defaultCandidate = false)
    @ConditionalOnMissingBean(name = SLICE)
    static SliceMarker synapseDataTestSlice() {
        return new SliceMarker();
    }

    /** The type of the marker bean; it has no behaviour. */
    static final class SliceMarker {
    }
}
