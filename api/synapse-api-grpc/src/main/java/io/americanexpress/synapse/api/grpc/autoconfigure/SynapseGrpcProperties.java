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
package io.americanexpress.synapse.api.grpc.autoconfigure;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import io.grpc.Metadata;
import io.grpc.Status;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;

/**
 * Settings of the gRPC API plugin.
 *
 * @param context Call context settings.
 * @param deadline Call deadline settings.
 * @param maxConcurrentCalls Most calls handled at the same time; further calls are closed with UNAVAILABLE and a
 *     retry delay of 1 s. Calls to the gRPC health and reflection services are never counted. Must be at least 1.
 * @param errors Error status overrides for every operation.
 * @param operations Settings per operation, keyed by operation name.
 */
@ConfigurationProperties("synapse.api.grpc")
public record SynapseGrpcProperties(@DefaultValue Context context, @DefaultValue Deadline deadline,
        @DefaultValue("200") int maxConcurrentCalls, @DefaultValue Errors errors,
        Map<String, OperationSettings> operations) {

    /**
     * Validates the properties.
     *
     * @throws IllegalArgumentException when the concurrency limit is below 1
     */
    public SynapseGrpcProperties {
        if (maxConcurrentCalls < 1) {
            throw new IllegalArgumentException(
                    "synapse.api.grpc.max-concurrent-calls must be at least 1 but was " + maxConcurrentCalls);
        }
        operations = operations == null ? Map.of() : Map.copyOf(operations);
    }

    /**
     * Call context settings.
     *
     * @param metadata Request metadata keys that carry context attributes, keyed by attribute name, for example
     *     synapse.api.grpc.context.metadata.tenant=x-tenant. Keys are lower case, ASCII and don't end with -bin.
     *     Attributes are read in this order, so when there are more than the context limits allow, the last ones are
     *     dropped.
     */
    public record Context(Map<String, String> metadata) {

        /**
         * Normalizes and validates the properties, keeping the configured order.
         *
         * @throws IllegalArgumentException when a metadata key is not a valid lower-case ASCII metadata key
         */
        public Context {
            metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
            metadata.forEach(Context::checkKey);
        }

        private static void checkKey(String attribute, String key) {
            String property = "synapse.api.grpc.context.metadata." + attribute;
            if (!key.equals(key.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(property + " must be a lower-case metadata key but was '" + key
                        + "'");
            }
            if (key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
                throw new IllegalArgumentException(property + " must be an ASCII metadata key, not a binary one"
                        + " ending with " + Metadata.BINARY_HEADER_SUFFIX + ", but was '" + key + "'");
            }
            try {
                Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(property + " must be a valid metadata key (letters, digits, '-',"
                        + " '_' and '.') but was '" + key + "': " + ex.getMessage());
            }
        }
    }

    /**
     * Call deadline settings.
     *
     * @param defaultTimeout Timeout used when the caller sets no gRPC deadline. Must be positive and not above the
     *     maximum.
     * @param max Longest timeout honored; a longer gRPC deadline is capped to it.
     */
    public record Deadline(@Name("default") @DefaultValue("10s") Duration defaultTimeout,
            @DefaultValue("30s") Duration max) {

        /**
         * Validates the properties.
         *
         * @throws IllegalArgumentException when the default is not positive or is above the maximum
         */
        public Deadline {
            if (defaultTimeout.isZero() || defaultTimeout.isNegative()) {
                throw new IllegalArgumentException(
                        "synapse.api.grpc.deadline.default must be positive but was " + defaultTimeout);
            }
            if (defaultTimeout.compareTo(max) > 0) {
                throw new IllegalArgumentException("synapse.api.grpc.deadline.default (" + defaultTimeout
                        + ") must not be above synapse.api.grpc.deadline.max (" + max + ")");
            }
        }
    }

    /**
     * Error status overrides.
     *
     * @param status gRPC status per error code, for example
     *     synapse.api.grpc.errors.status.[BOOK_NOT_FOUND]=FAILED_PRECONDITION.
     */
    public record Errors(Map<String, Status.Code> status) {

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
