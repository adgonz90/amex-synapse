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

import org.jspecify.annotations.Nullable;
import org.springframework.web.servlet.function.ServerRequest;

import io.americanexpress.synapse.core.context.RequestContext;
import io.americanexpress.synapse.core.internal.binding.ResolvedComponent;

/**
 * Where one input component's value comes from, after declarations, conventions and defaults have been applied. A
 * resolved route has at most one source per input component, listed in component order.
 */
public sealed interface InputSource {

    /**
     * Returns the input component this source fills.
     *
     * @return the component
     */
    ResolvedComponent component();

    /**
     * A path template variable.
     *
     * @param component the input component
     * @param variable the template variable name
     */
    record Path(ResolvedComponent component, String variable) implements InputSource {
    }

    /**
     * A query parameter; a repeated parameter gives a {@code List} component all its values.
     *
     * @param component the input component
     * @param parameter the parameter name
     */
    record Query(ResolvedComponent component, String parameter) implements InputSource {
    }

    /**
     * A record-typed component built from the query string: each of its components binds from the parameter of the
     * same name.
     *
     * @param component the input component, whose type is a record
     * @param components the nested record's components, in declaration order
     */
    record QueryRecord(ResolvedComponent component, List<ResolvedComponent> components) implements InputSource {

        /**
         * Creates the source.
         *
         * @param component the input component
         * @param components the nested record's components
         */
        public QueryRecord {
            components = List.copyOf(components);
        }
    }

    /**
     * The JSON body property for this component (the body fills every component not bound elsewhere).
     *
     * @param component the input component
     */
    record Body(ResolvedComponent component) implements InputSource {
    }

    /**
     * The whole JSON body, bound to this one component.
     *
     * @param component the input component
     */
    record BodyComponent(ResolvedComponent component) implements InputSource {
    }

    /**
     * A request header.
     *
     * @param component the input component
     * @param name the header name
     */
    record Header(ResolvedComponent component, String name) implements InputSource {
    }

    /**
     * A cookie.
     *
     * @param component the input component
     * @param name the cookie name
     */
    record Cookie(ResolvedComponent component, String name) implements InputSource {
    }

    /**
     * A value read from the current {@link RequestContext}.
     *
     * @param component the input component
     * @param value reads the value
     */
    record Context(ResolvedComponent component, Function<RequestContext, ? extends @Nullable Object> value)
            implements
                InputSource {
    }

    /**
     * A value read from the request by application code.
     *
     * @param component the input component
     * @param value reads the value
     */
    record Custom(ResolvedComponent component, Function<ServerRequest, ? extends @Nullable Object> value)
            implements
                InputSource {
    }
}
