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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
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
 * @param paging Paging settings.
 * @param operations Settings per operation, keyed by operation name.
 * @param cors Cross-origin resource sharing (CORS) settings of the REST routes.
 */
@ConfigurationProperties("synapse.api.rest")
public record SynapseRestProperties(@DefaultValue Context context, @DefaultValue Deadline deadline,
        @DefaultValue("200") int maxConcurrentRequests, @DefaultValue("1MB") DataSize maxRequestSize,
        @DefaultValue Errors errors, @DefaultValue Paging paging, Map<String, OperationSettings> operations,
        @DefaultValue Cors cors) {

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
     * Paging settings.
     *
     * @param parameters Names of the paging query parameters.
     * @param maxOffset Largest offset a caller may request; a larger one is rejected before the operation runs. Must
     *     not be negative.
     * @param cursor Settings of the opaque cursors the plugin issues.
     */
    public record Paging(@DefaultValue Parameters parameters, @DefaultValue("1000") long maxOffset,
            @DefaultValue Cursor cursor) {

        /**
         * Validates the properties.
         *
         * @throws IllegalArgumentException when the offset limit is negative
         */
        public Paging {
            if (maxOffset < 0) {
                throw new IllegalArgumentException(
                        "synapse.api.rest.paging.max-offset must not be negative but was " + maxOffset);
            }
        }

        /**
         * Names of the paging query parameters, as callers send them.
         *
         * @param cursor Parameter with the cursor token of the page to fetch.
         * @param offset Parameter with the number of items to skip.
         * @param size Parameter with the page size.
         * @param sort Parameter with the sort keys, for example sort=title,-published.
         */
        public record Parameters(@DefaultValue("cursor") String cursor, @DefaultValue("offset") String offset,
                @DefaultValue("size") String size, @DefaultValue("sort") String sort) {

            private static final Pattern UNRESERVED = Pattern.compile("[A-Za-z0-9._~-]+");

            /**
             * Validates the properties.
             *
             * @throws IllegalArgumentException when a name is blank, uses a character that is not an RFC 3986
             *     unreserved character, or is used twice
             */
            public Parameters {
                Set<String> seen = new HashSet<>();
                for (String name : List.of(cursor, offset, size, sort)) {
                    if (!UNRESERVED.matcher(name).matches()) {
                        throw new IllegalArgumentException("synapse.api.rest.paging.parameters names must be"
                                + " non-blank and use only letters, digits, '-', '.', '_' and '~' but one was '" + name
                                + "'");
                    }
                    if (!seen.add(name)) {
                        throw new IllegalArgumentException(
                                "synapse.api.rest.paging.parameters names must be distinct but '" + name
                                        + "' is used twice");
                    }
                }
            }
        }

        /**
         * Cursor settings.
         *
         * @param key Base64 key that encrypts cursors; whitespace around it is ignored. When unset or blank, a key is
         *     generated at startup.
         * @param previousKeys Earlier keys, used only to read cursors issued before a key rotation. Blank entries are
         *     ignored.
         * @param maxAge How long a cursor stays valid. When unset, cursors do not expire. Must be positive when set.
         */
        public record Cursor(@Nullable String key, List<String> previousKeys, @Nullable Duration maxAge) {

            /**
             * Normalizes and validates the properties.
             *
             * @throws IllegalArgumentException when the maximum age is not positive
             */
            public Cursor {
                previousKeys = previousKeys == null ? List.of() : List.copyOf(previousKeys);
                if (maxAge != null && (maxAge.isZero() || maxAge.isNegative())) {
                    throw new IllegalArgumentException(
                            "synapse.api.rest.paging.cursor.max-age must be positive but was " + maxAge);
                }
            }

            /**
             * Describes the settings without key material.
             *
             * @return whether the key is set (a blank key is not), how many non-blank previous keys there are, and the
             *     maximum age
             */
            @Override
            public String toString() {
                long previous = previousKeys.stream().filter(previousKey -> !previousKey.isBlank()).count();
                return "Cursor[key=" + (key == null || key.isBlank() ? "unset" : "set") + ", previousKeys=" + previous
                        + ", maxAge=" + maxAge + "]";
            }
        }
    }
    /**
     * Settings of one operation.
     *
     * @param errors Error status overrides for this operation; they win over the plugin-wide ones.
     */
    public record OperationSettings(@DefaultValue Errors errors) {
    }

    /**
     * Cross-origin resource sharing (CORS) settings of the REST routes. Actuator's paths are left to Spring Boot's
     * management.endpoints.web.cors settings.
     *
     * @param allowedOrigins Origins whose browser pages may call the API, each * or http(s)://host[:port] with a port
     *     from 1 to 65535, for example https://app.example.com. Without any, CORS is off and browsers refuse
     *     cross-origin calls. * allows any website and is logged as a warning at startup. Origins are read back in
     *     lower case.
     * @param allowedMethods HTTP methods allowed on cross-origin requests: standard method names, or *. Must not be
     *     empty.
     * @param allowedHeaders Request headers allowed on cross-origin requests, or * for any.
     * @param exposedHeaders Response headers, besides the ones Synapse always exposes (the correlation header, ETag,
     *     Link, Location, Retry-After, Deprecation and Sunset), that browser pages may read, or * for any while
     *     credentials are not allowed.
     * @param allowCredentials Whether browsers send cookies and credentials on cross-origin requests. Can't be true
     *     while allowed-origins or exposed-headers contains *.
     * @param maxAge How long browsers may cache a pre-flight response. Must not be negative.
     */
    public record Cors(List<String> allowedOrigins,
            @DefaultValue({
                    "GET", "HEAD", "POST", "PUT", "DELETE"}) List<String> allowedMethods,
            @DefaultValue("*") List<String> allowedHeaders, List<String> exposedHeaders,
            @DefaultValue("false") boolean allowCredentials, @DefaultValue("30m") Duration maxAge) {

        /** Any origin, method or header. */
        private static final String ANY = "*";

        /** {@code http(s)://host[:port]}: a host name or bracketed IPv6 address, no user info, path or query. */
        private static final Pattern ORIGIN = Pattern
                .compile("(?i)https?://(\\[[0-9a-f:.]+\\]|[^/?#*\\s:@\\[\\]]+)(:\\d{1,5})?");

        private static final Set<String> STANDARD_METHODS = Set
                .of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE");

        /**
         * Normalizes and validates the properties: origins are lower-cased.
         *
         * @throws IllegalArgumentException when an origin, its port or a method is invalid, there is no method, the
         *     maximum age is negative, or credentials are allowed for any origin or with any exposed header
         */
        public Cors {
            allowedMethods = allowedMethods == null ? List.of() : List.copyOf(allowedMethods);
            allowedHeaders = allowedHeaders == null ? List.of() : List.copyOf(allowedHeaders);
            exposedHeaders = exposedHeaders == null ? List.of() : List.copyOf(exposedHeaders);
            List<String> origins = new ArrayList<>();
            for (String origin : allowedOrigins == null ? List.<String>of() : allowedOrigins) {
                Matcher matcher = ORIGIN.matcher(origin);
                if (!ANY.equals(origin) && !matcher.matches()) {
                    throw new IllegalArgumentException("synapse.api.rest.cors.allowed-origins must each be * or"
                            + " http(s)://host[:port], without a path, query, trailing slash or other *, but one was '"
                            + origin + "'");
                }
                String port = ANY.equals(origin) ? null : matcher.group(2);
                if (port != null && !isPort(Integer.parseInt(port.substring(1)))) {
                    throw new IllegalArgumentException("synapse.api.rest.cors.allowed-origins must each have a port"
                            + " from 1 to 65535, but one was '" + origin + "'");
                }
                // Spring compares origins ignoring case; lower-casing only normalizes what the settings read back.
                origins.add(origin.toLowerCase(Locale.ROOT));
            }
            allowedOrigins = List.copyOf(origins);
            if (allowedMethods.isEmpty()) {
                throw new IllegalArgumentException(
                        "synapse.api.rest.cors.allowed-methods must list at least one method (or *) but was empty");
            }
            for (String method : allowedMethods) {
                if (!ANY.equals(method) && !STANDARD_METHODS.contains(method)) {
                    throw new IllegalArgumentException("synapse.api.rest.cors.allowed-methods must each be * or one of"
                            + " GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS and TRACE, but one was '" + method + "'");
                }
            }
            if (maxAge.isNegative()) {
                throw new IllegalArgumentException(
                        "synapse.api.rest.cors.max-age must not be negative but was " + maxAge);
            }
            if (allowCredentials && allowedOrigins.contains(ANY)) {
                throw new IllegalArgumentException("synapse.api.rest.cors.allow-credentials can't be true while"
                        + " synapse.api.rest.cors.allowed-origins contains *: browsers refuse credentials for any"
                        + " origin. List the exact origins.");
            }
            if (allowCredentials && exposedHeaders.contains(ANY)) {
                throw new IllegalArgumentException("synapse.api.rest.cors.allow-credentials can't be true while"
                        + " synapse.api.rest.cors.exposed-headers contains *: browsers read it as a header named * on"
                        + " a request with credentials, not as any header. List the exact headers.");
            }
        }

        private static boolean isPort(int port) {
            return port >= 1 && port <= 65_535;
        }
    }
}
