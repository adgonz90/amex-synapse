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
package io.americanexpress.synapse.api.rest.autoconfigure;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.util.unit.DataSize;

/**
 * Settings of the REST API plugin.
 *
 * @param context Request context settings.
 * @param deadline Request deadline settings.
 * @param maxConcurrentRequests Most requests handled at the same time; further requests get 503 with
 *     Retry-After: 1. Requests to Actuator on the application's port are never counted. Must be at least 1.
 * @param maxRequestSize Largest request body accepted unless a route sets its own limit; a larger body gets 413
 *     without being read. Must be positive.
 * @param errors Error status overrides for every operation.
 * @param operations Settings per operation, keyed by operation name.
 */
@ConfigurationProperties("synapse.api.rest")
public record SynapseRestProperties(@DefaultValue Context context, @DefaultValue Deadline deadline,
        @DefaultValue("200") int maxConcurrentRequests, @DefaultValue("1MB") DataSize maxRequestSize,
        @DefaultValue Errors errors, Map<String, OperationSettings> operations) {

    /**
     * Validates the properties.
     *
     * @throws IllegalArgumentException when a limit is not positive
     */
    public SynapseRestProperties {
        if (maxConcurrentRequests < 1) {
            throw new IllegalArgumentException(
                    "synapse.api.rest.max-concurrent-requests must be at least 1 but was " + maxConcurrentRequests);
        }
        if (maxRequestSize.toBytes() < 1) {
            throw new IllegalArgumentException(
                    "synapse.api.rest.max-request-size must be positive but was " + maxRequestSize);
        }
        operations = operations == null ? Map.of() : Map.copyOf(operations);
    }

    /**
     * Request context settings.
     *
     * @param headers Request headers that carry context attributes, keyed by attribute name. Attributes are read in
     *     this order, so when there are more than the context limits allow, the last ones are dropped.
     */
    public record Context(Map<String, String> headers) {

        /**
         * Normalizes the properties, keeping the configured order.
         */
        public Context {
            headers = headers == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        }
    }

    /**
     * Request deadline settings.
     *
     * @param header Request header with the caller's timeout in milliseconds.
     * @param defaultTimeout Timeout used when the caller sends none. Must be positive and not above the maximum.
     * @param max Longest timeout honored; larger requested values are capped to it.
     */
    public record Deadline(@DefaultValue("Request-Timeout") String header,
            @Name("default") @DefaultValue("10s") Duration defaultTimeout, @DefaultValue("30s") Duration max) {

        /**
         * Validates the properties.
         *
         * @throws IllegalArgumentException when the default is not positive or is above the maximum
         */
        public Deadline {
            if (defaultTimeout.isZero() || defaultTimeout.isNegative()) {
                throw new IllegalArgumentException(
                        "synapse.api.rest.deadline.default must be positive but was " + defaultTimeout);
            }
            if (defaultTimeout.compareTo(max) > 0) {
                throw new IllegalArgumentException("synapse.api.rest.deadline.default (" + defaultTimeout
                        + ") must not be above synapse.api.rest.deadline.max (" + max + ")");
            }
        }
    }

    /**
     * Error status overrides.
     *
     * @param status HTTP status per error code, for example
     *     synapse.api.rest.errors.status.[BOOK_NOT_FOUND]=423.
     */
    public record Errors(Map<String, Integer> status) {

        /**
         * Normalizes the properties.
         */
        public Errors {
            status = status == null ? Map.of() : Map.copyOf(status);
        }
    }

    /**
     * Settings of one operation.
     *
     * @param errors Error status overrides for this operation; they win over the plugin-wide ones.
     */
    public record OperationSettings(@DefaultValue Errors errors) {
    }
}
