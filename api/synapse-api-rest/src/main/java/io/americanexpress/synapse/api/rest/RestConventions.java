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
package io.americanexpress.synapse.api.rest;

import java.util.ArrayList;
import java.util.List;

import io.americanexpress.synapse.api.rest.internal.binding.ConventionRules;
import io.americanexpress.synapse.api.rest.internal.binding.RestBindingsAccess;
import io.americanexpress.synapse.core.binding.ComponentRef;

/**
 * App-wide REST binding rules, declared once as a bean and applied <em>by type</em> to every route. A declaration on
 * a route beats a convention, which beats the defaults.
 *
 * <pre class="code">
 * &#64;Bean
 * RestConventions conventions() {
 *     return RestConventions.builder()
 *         .query(WriteOptions.class)
 *         .header("X-Tenant", TenantId.class)
 *         .responseHeader("X-RateLimit-Remaining", BookResult.class, BookResult::remaining)
 *         .build();
 * }
 * </pre>
 */
public final class RestConventions {

    static {
        // Synapse reads the rules through an internal class, so that they are not public API.
        RestBindingsAccess.registerRules(conventions -> conventions.rules);
    }

    private final ConventionRules rules;

    private RestConventions(ConventionRules rules) {
        this.rules = rules;
    }

    /**
     * Starts declaring conventions.
     *
     * @return a builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Declares conventions.
     */
    public static final class Builder {

        private final List<Class<?>> queryRecords = new ArrayList<>();

        private final List<ConventionRules.HeaderRule> headers = new ArrayList<>();

        private final List<ConventionRules.ResponseHeaderRule> responseHeaders = new ArrayList<>();

        private Builder() {
        }

        /**
         * Binds every input component of this record type from the query string, on any method: each of the
         * record's components from the parameter of the same name.
         *
         * @param recordType the record type
         * @return this builder
         */
        public Builder query(Class<?> recordType) {
            queryRecords.add(recordType);
            return this;
        }

        /**
         * Binds every input component of this type from a request header.
         *
         * @param name the header name
         * @param componentType the component type
         * @return this builder
         */
        public Builder header(String name, Class<?> componentType) {
            headers.add(new ConventionRules.HeaderRule(name, componentType));
            return this;
        }

        /**
         * Writes an output component to a response header on every route whose output (unwrapped from
         * {@code Optional}) is {@code outputType}. The component is removed from the body. The rule does not apply to
         * a route whose output is a {@code List}. The component needs a simple type, as for
         * {@code RouteSpec.header}.
         *
         * @param name the header name
         * @param outputType the output record type
         * @param component the output component
         * @param <T> the output record type
         * @return this builder
         */
        public <T> Builder responseHeader(String name, Class<T> outputType, ComponentRef<T, ?> component) {
            responseHeaders.add(new ConventionRules.ResponseHeaderRule(name, outputType, component));
            return this;
        }

        /**
         * Builds the conventions.
         *
         * @return the conventions
         */
        public RestConventions build() {
            return new RestConventions(new ConventionRules(queryRecords, headers, responseHeaders));
        }
    }
}
