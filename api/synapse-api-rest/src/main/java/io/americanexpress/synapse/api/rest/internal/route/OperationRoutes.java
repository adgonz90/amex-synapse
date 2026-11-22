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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.core.io.Resource;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;

import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;

/**
 * Reads the Synapse routes out of router functions: the routes served by an {@link OperationHandler}, in order, each
 * once (a {@code GET} route's handler also serves its implicit {@code HEAD} route).
 */
public final class OperationRoutes implements RouterFunctions.Visitor {

    private final List<RouteDefinition> routes = new ArrayList<>();

    private OperationRoutes() {
    }

    /**
     * Returns the Synapse routes of the router functions.
     *
     * @param routerFunctions the router functions, in order
     * @return the routes
     */
    public static List<RouteDefinition> of(Iterable<? extends RouterFunction<?>> routerFunctions) {
        OperationRoutes collector = new OperationRoutes();
        routerFunctions.forEach(function -> function.accept(collector));
        return List.copyOf(collector.routes);
    }

    @Override
    public void startNested(RequestPredicate predicate) {
        // Nested routes are visited through route(...).
    }

    @Override
    public void endNested(RequestPredicate predicate) {
        // Nothing to close.
    }

    @Override
    public void route(RequestPredicate predicate, HandlerFunction<?> handlerFunction) {
        if (handlerFunction instanceof OperationHandler handler && !routes.contains(handler.route())) {
            routes.add(handler.route());
        }
    }

    @Override
    public void resources(Function<ServerRequest, Optional<Resource>> lookupFunction) {
        // Not an operation.
    }

    @Override
    public void attributes(Map<String, Object> attributes) {
        // Not needed.
    }

    @Override
    public void unknown(RouterFunction<?> routerFunction) {
        // Not built by Synapse.
    }
}
