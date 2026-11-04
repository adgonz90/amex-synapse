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
package io.americanexpress.synapse.api.rest.internal.route;

import java.util.List;

import org.springframework.core.Ordered;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.servlet.function.support.RouterFunctionMapping;

/**
 * The DispatcherServlet handler mapping of the {@link MethodNotAllowedHandler}, the 405 and {@code OPTIONS} fallback
 * of the Synapse routes. It is ordered after every other handler mapping that Spring MVC and Boot set up (the
 * functional routes at -1, annotated controllers at 0, view controllers, bean-name URLs and the welcome page), and
 * before the static resources ({@code Ordered.LOWEST_PRECEDENCE - 1}) and the default servlet. The DispatcherServlet
 * asks the mappings in order, so the fallback answers only when no other mapping has a handler for the request: an
 * escape-hatch controller method on a Synapse path is reached, and an unknown path still reaches the static resources
 * ({@code ROUTE_NOT_FOUND}).
 *
 * <p>
 * The fallback's templates are read from the routes it is given, so it covers exactly the Synapse routes served. It is
 * not a {@code RouterFunction} bean, so Spring MVC's own functional-route mapping never picks it up.
 */
public final class MethodNotAllowedMapping extends RouterFunctionMapping {

    /** The order of the mapping: after every other mapping, before the static resources. */
    public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 2;

    /**
     * Creates the mapping.
     *
     * @param routes the Synapse routes the fallback covers
     */
    public MethodNotAllowedMapping(RouterFunction<?> routes) {
        super(fallback(routes));
        setOrder(ORDER);
    }

    private static RouterFunction<ServerResponse> fallback(RouterFunction<?> routes) {
        MethodNotAllowedHandler handler = new MethodNotAllowedHandler(OperationRoutes.of(List.of(routes)));
        return RouterFunctions.route(handler, handler);
    }
}
