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
package io.americanexpress.synapse.logging.log4j2;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the burst limit on operation failure logs, bound under {@code synapse.logging.failure-burst}. Both
 * unset means no limit; setting only one of them fails startup.
 *
 * @param rate events per second the failure logger sustains once the burst is used up; unset (together with the
 *        maximum burst) means no limit
 * @param maxBurst burst size: how many failure events are logged back to back before the rate applies; unset
 *        (together with the rate) means no limit
 */
@ConfigurationProperties(SynapseLoggingProperties.PREFIX)
public record SynapseLoggingProperties(@Nullable Float rate, @Nullable Integer maxBurst) {

    /** The property prefix. */
    static final String PREFIX = "synapse.logging.failure-burst";
}
