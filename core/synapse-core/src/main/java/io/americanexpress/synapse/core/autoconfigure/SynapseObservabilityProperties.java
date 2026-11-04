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

import io.americanexpress.synapse.core.observability.BusinessObservation;

/**
 * Observability settings.
 *
 * @param business How business operation calls are observed: as spans, as metrics only, or not at all.
 * @param requireTracing Whether startup fails when service operations exist but tracing cannot carry their context
 *     (no real tracer, or baggage disabled). When false, the check is skipped and one INFO line reports a missing
 *     tracer or disabled baggage; the correlation id and context attributes then reach neither the logs nor outbound
 *     calls.
 */
@ConfigurationProperties("synapse.observability")
public record SynapseObservabilityProperties(@DefaultValue("spans") BusinessObservation business,
        @DefaultValue("true") boolean requireTracing) {
}
