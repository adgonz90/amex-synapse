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
import org.springframework.util.unit.DataSize;

/**
 * Request context settings.
 *
 * @param inbound Trust and limits applied to the context attributes callers send.
 */
@ConfigurationProperties("synapse.context")
public record SynapseContextProperties(@DefaultValue Inbound inbound) {

    /**
     * Trust and limits applied to the context attributes callers send.
     *
     * @param trusted Whether context attributes and baggage sent by callers are accepted. Public-facing services set
     *     this to false so inbound context is dropped.
     * @param maxEntries Maximum number of context attributes kept from one request; the rest are dropped and logged.
     *     Must not be negative.
     * @param maxSize Maximum size of the context attributes (UTF-8 names plus values) kept from one request; the rest
     *     are dropped and logged. Must not be negative.
     */
    public record Inbound(@DefaultValue("true") boolean trusted, @DefaultValue("16") int maxEntries,
            @DefaultValue("4KB") DataSize maxSize) {

        /**
         * Validates the properties.
         *
         * @throws IllegalArgumentException when {@code maxEntries} or {@code maxSize} is negative
         */
        public Inbound {
            if (maxEntries < 0) {
                throw new IllegalArgumentException(
                        "synapse.context.inbound.max-entries must not be negative but was " + maxEntries);
            }
            if (maxSize.isNegative()) {
                throw new IllegalArgumentException(
                        "synapse.context.inbound.max-size must not be negative but was " + maxSize);
            }
        }
    }
}
