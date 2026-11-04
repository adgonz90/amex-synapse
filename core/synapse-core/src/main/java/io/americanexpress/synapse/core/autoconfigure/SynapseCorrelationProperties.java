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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Correlation id settings.
 *
 * @param header Name of the header (and gRPC metadata key) that carries the correlation id.
 * @param maxLength Longest correlation id accepted from a caller, in characters; longer ids are replaced with a
 *     generated one. Must be at least 1.
 */
@ConfigurationProperties("synapse.correlation")
public record SynapseCorrelationProperties(@DefaultValue("X-Correlation-Id") String header,
        @DefaultValue("128") int maxLength) {

    /**
     * Validates the properties.
     *
     * @throws IllegalArgumentException when {@code maxLength} is less than 1
     */
    public SynapseCorrelationProperties {
        if (maxLength < 1) {
            throw new IllegalArgumentException(
                    "synapse.correlation.max-length must be at least 1 but was " + maxLength);
        }
    }
}
