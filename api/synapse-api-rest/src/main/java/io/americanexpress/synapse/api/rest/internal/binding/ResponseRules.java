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
import java.util.function.Function;
import java.util.function.ToIntFunction;

import org.jspecify.annotations.Nullable;
import org.springframework.util.unit.DataSize;

import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.internal.binding.ResolvedComponent;

/**
 * How a route writes its response. The functions take the operation's output exactly as {@code execute} returned it
 * (an {@code Optional} or {@code List} is not unwrapped). In a route returned by {@code RestBindings.routes()},
 * {@link #outputHeaders()} is empty and {@link #bodyComponent()} is {@code null}: {@link BindingValidator} fills them
 * from the declarations and the conventions.
 *
 * @param status the fixed status ({@code .status(int)}), or {@code null}
 * @param statusFunction the status computed from the output ({@code .status(fn)}), or {@code null}
 * @param created the {@code Location} of a created resource ({@code .created(fn)}: 201), or {@code null}
 * @param accepted the {@code Location} of the status resource ({@code .accepted(fn)}: 202), or {@code null}
 * @param notFound the error for an empty {@code Optional} ({@code .notFound(code)}), or {@code null}
 * @param emptyAs the status for an empty {@code Optional} with no body ({@code .emptyAs(status)}), or {@code null}
 * @param outputHeaders output components written to response headers, declared ones first, then conventions;
 *        always empty for a {@code List} output
 * @param bodyComponent the output component written as the whole body ({@code .body(component)}), or {@code null}
 * @param maxRequestSize the route's request body limit ({@code .maxRequestSize(size)}), or {@code null} for the
 *        default
 */
public record ResponseRules(@Nullable Integer status, @Nullable ToIntFunction<Object> statusFunction,
        @Nullable Function<Object, String> created, @Nullable Function<Object, String> accepted,
        @Nullable ErrorDetails notFound, @Nullable Integer emptyAs, List<OutputHeader> outputHeaders,
        @Nullable ResolvedComponent bodyComponent, @Nullable DataSize maxRequestSize) {

    /**
     * Creates the rules.
     *
     * @param status the fixed status
     * @param statusFunction the computed status
     * @param created the created location
     * @param accepted the status-resource location
     * @param notFound the empty-Optional error
     * @param emptyAs the empty-Optional status
     * @param outputHeaders the output headers
     * @param bodyComponent the body component
     * @param maxRequestSize the request body limit
     */
    public ResponseRules {
        outputHeaders = List.copyOf(outputHeaders);
    }

    /**
     * Returns these rules with the resolved output headers and body component.
     *
     * @param headers the output headers
     * @param body the body component, or {@code null}
     * @return the resolved rules
     */
    ResponseRules withOutput(List<OutputHeader> headers, @Nullable ResolvedComponent body) {
        return new ResponseRules(status, statusFunction, created, accepted, notFound, emptyAs, headers, body,
                maxRequestSize);
    }

    /**
     * An output component written to a response header.
     *
     * @param name the header name
     * @param component the output component
     * @param alsoInBody true if the component also stays in the JSON body ({@code .alsoInBody()}); otherwise it is
     *        removed from the body
     */
    public record OutputHeader(String name, ResolvedComponent component, boolean alsoInBody) {
    }
}
