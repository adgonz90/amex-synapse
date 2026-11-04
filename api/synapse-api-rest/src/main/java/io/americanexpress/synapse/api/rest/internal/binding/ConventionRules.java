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
package io.americanexpress.synapse.api.rest.internal.binding;

import java.util.List;

import io.americanexpress.synapse.core.binding.ComponentRef;

/**
 * The rules of one {@code RestConventions} bean, as declared. {@link BindingValidator} applies them by type.
 *
 * @param queryRecords record types whose input components bind from the query string
 * @param headers input component types that bind from a header
 * @param responseHeaders output components written to a header for every route returning their record
 */
public record ConventionRules(List<Class<?>> queryRecords, List<HeaderRule> headers,
        List<ResponseHeaderRule> responseHeaders) {

    /**
     * Creates the rules.
     *
     * @param queryRecords the query record types
     * @param headers the header rules
     * @param responseHeaders the response header rules
     */
    public ConventionRules {
        queryRecords = List.copyOf(queryRecords);
        headers = List.copyOf(headers);
        responseHeaders = List.copyOf(responseHeaders);
    }

    /**
     * Every input component of {@code componentType} binds from header {@code name}.
     *
     * @param name the header name
     * @param componentType the component type
     */
    public record HeaderRule(String name, Class<?> componentType) {
    }

    /**
     * For every route whose (unwrapped) output type is {@code outputType}, {@code component} is written to header
     * {@code name}.
     *
     * @param name the header name
     * @param outputType the output record type
     * @param component the output component
     */
    public record ResponseHeaderRule(String name, Class<?> outputType, ComponentRef<?, ?> component) {
    }
}
