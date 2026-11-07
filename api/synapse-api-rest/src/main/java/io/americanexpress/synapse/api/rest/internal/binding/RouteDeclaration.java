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

import io.americanexpress.synapse.core.binding.ComponentRef;
import io.americanexpress.synapse.core.context.RequestContext;

/**
 * One component call on a route, recorded as declared and in declaration order. Nothing is resolved when it is
 * recorded: {@link BindingValidator} resolves the component references and turns the declarations into
 * {@link InputSource}s and {@link ResponseRules}.
 */
public sealed interface RouteDeclaration {

    /**
     * {@code .path(variable, component)}.
     *
     * @param variable the template variable
     * @param component the input component
     */
    record Path(String variable, ComponentRef<?, ?> component) implements RouteDeclaration {
    }

    /**
     * {@code .query(component)} or {@code .query(parameter, component)}.
     *
     * @param parameter the parameter name, or {@code null} for the component name
     * @param component the input component
     */
    record Query(@Nullable String parameter, ComponentRef<?, ?> component) implements RouteDeclaration {
    }

    /**
     * {@code .cookie(name, component)}.
     *
     * @param name the cookie name
     * @param component the input component
     */
    record Cookie(String name, ComponentRef<?, ?> component) implements RouteDeclaration {
    }

    /**
     * {@code .context(component, value)}.
     *
     * @param component the input component
     * @param value reads the value from the request context
     */
    record Context(ComponentRef<?, ?> component, Function<RequestContext, ? extends @Nullable Object> value)
            implements
                RouteDeclaration {
    }

    /**
     * {@code .from(component, value)}.
     *
     * @param component the input component
     * @param value reads the value from the request
     */
    record Custom(ComponentRef<?, ?> component, Function<ServerRequest, ? extends @Nullable Object> value)
            implements
                RouteDeclaration {
    }

    /**
     * {@code .header(name, component)}: an input component read from, or an output component written to, a header.
     *
     * @param name the header name
     * @param component the input or output component
     */
    record Header(String name, ComponentRef<?, ?> component) implements RouteDeclaration {
    }

    /**
     * {@code .body(component)}: the request body bound to one input component, or one output component written as
     * the response body.
     *
     * @param component the input or output component
     */
    record Body(ComponentRef<?, ?> component) implements RouteDeclaration {
    }

    /**
     * {@code .alsoInBody()}: the output header declared just before stays in the body.
     */
    record AlsoInBody() implements RouteDeclaration {
    }

    /**
     * {@code .sortable(keys)} or {@code .sortable(names...)}: the route's sort allow-list.
     *
     * @param keys the enum of sort keys, or {@code null} when the keys are given as names
     * @param names the key names, empty when the keys are an enum
     */
    record Sortable(@Nullable Class<? extends Enum<?>> keys, List<String> names) implements RouteDeclaration {

        /**
         * Creates the declaration.
         *
         * @param keys the enum of sort keys, or {@code null}
         * @param names the key names
         */
        public Sortable {
            names = List.copyOf(names);
        }
    }

    /**
     * {@code .etag(component)}: the output component sent as the strong {@code ETag}.
     *
     * @param component the output component
     */
    record ETag(ComponentRef<?, ?> component) implements RouteDeclaration {
    }

    /**
     * {@code .version(version)}: the route is served at {@code /v<version>} followed by its path.
     *
     * @param version the version as declared, such as {@code 2} or {@code 1.2}
     */
    record Version(String version) implements RouteDeclaration {
    }
}
