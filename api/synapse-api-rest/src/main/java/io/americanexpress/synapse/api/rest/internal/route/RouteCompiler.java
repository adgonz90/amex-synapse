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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jakarta.servlet.DispatcherType;

import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.RestConventions;
import io.americanexpress.synapse.api.rest.internal.binding.BindingValidator;
import io.americanexpress.synapse.api.rest.internal.binding.RequestBinder;
import io.americanexpress.synapse.api.rest.internal.binding.RestBindingsAccess;
import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.api.rest.internal.paging.PagingSettings;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.internal.paging.PageCursors;

/**
 * Compiles the REST bindings into functional routes at startup. It resolves and checks every route (including the
 * duplicate-route check, in declaration order), checks that its input can be bound, and builds one route per
 * definition, ordered by {@link PathPattern#SPECIFICITY_COMPARATOR} so the most specific template wins whatever the
 * declaration order (equally specific templates keep their declaration order); then, after every declared route and in
 * the same order, a {@code HEAD} route for each {@code GET} route, served by the same handler, so a declared
 * {@code HEAD} route wins. A versioned route, and its implicit {@code HEAD} route, also carry Spring's
 * {@link RequestPredicates#version} predicate, from which Spring MVC detects the supported versions. No route matches the servlet container's error dispatch, which belongs to the error
 * controller. Any binding mistake fails startup. The 405 and {@code OPTIONS} fallback is not part of the routes: it
 * is the {@link MethodNotAllowedMapping}, after the other handler mappings. Once the routes are checked, it reports a
 * cursor key generated at startup when a route returns {@code PageResult} ({@link PageCursors#reportGeneratedKey}).
 */
public final class RouteCompiler {

    /** Keeps the container's error dispatch, whose path a template such as {@code /{slug}} also matches, away. */
    private static final RequestPredicate NOT_ERROR_DISPATCH = request -> request
            .servletRequest()
            .getDispatcherType() != DispatcherType.ERROR;

    private final BindingValidator validator;

    private final RequestBinder binder;

    private final ResponseWriter writer;

    private final PageCursors cursors;

    private final PagingSettings paging;

    /**
     * Creates the compiler.
     *
     * @param validator resolves and checks the routes
     * @param binder checks and binds the inputs
     * @param writer writes the responses
     * @param cursors unwraps the cursor tokens, and reports a generated cursor key
     * @param paging the paging settings, for the cursor parameter name a rejected token names
     */
    public RouteCompiler(BindingValidator validator, RequestBinder binder, ResponseWriter writer, PageCursors cursors,
            PagingSettings paging) {
        this.validator = validator;
        this.binder = binder;
        this.writer = writer;
        this.cursors = cursors;
        this.paging = paging;
    }

    /**
     * Compiles the routes.
     *
     * @param bindings every {@code RestBindings} bean
     * @param conventions every {@code RestConventions} bean
     * @return the routes, most specific template first, with the implicit {@code HEAD} routes last
     * @throws SynapseConfigurationException on the first binding mistake
     */
    public RouterFunction<ServerResponse> compile(List<RestBindings> bindings, List<RestConventions> conventions) {
        List<RouteDefinition> declared = bindings
                .stream()
                .flatMap(each -> RestBindingsAccess.routes(each).stream())
                .toList();
        List<RouteDefinition> routes = bySpecificity(validator.validate(declared, conventions));
        RouterFunctions.Builder builder = RouterFunctions.route();
        Map<RouteDefinition, OperationHandler> getRoutes = new LinkedHashMap<>();
        for (RouteDefinition route : routes) {
            binder.checkBindable(route);
            OperationHandler handler = new OperationHandler(route, binder, writer, cursors, paging);
            builder.route(predicate(route.method(), route), handler);
            if (HttpMethod.GET.equals(route.method())) {
                getRoutes.put(route, handler);
            }
        }
        // HEAD is served by each GET route's handler, after every declared route so a declared HEAD route wins.
        getRoutes.forEach((route, handler) -> builder.route(predicate(HttpMethod.HEAD, route), handler));
        // Every route is checked: a startup that fails on a binding mistake reports nothing.
        if (routes.stream().anyMatch(RouteDefinition::pagedOutput)) {
            cursors.reportGeneratedKey();
        }
        return builder.build();
    }

    /** Sorts the routes by template specificity; the sort is stable, so equally specific routes keep their order. */
    private static List<RouteDefinition> bySpecificity(List<RouteDefinition> routes) {
        PathPatternParser parser = PathPatternParser.defaultInstance;
        List<Ranked> ranked = new ArrayList<>(routes.size());
        for (RouteDefinition route : routes) {
            // Parsed as RequestPredicates.path parses the route's template.
            ranked.add(new Ranked(route, parser.parse(parser.initFullPathPattern(route.path()))));
        }
        ranked.sort(Comparator.comparing(Ranked::pattern, PathPattern.SPECIFICITY_COMPARATOR));
        return ranked.stream().map(Ranked::route).toList();
    }

    private static RequestPredicate predicate(HttpMethod method, RouteDefinition route) {
        RequestPredicate predicate = NOT_ERROR_DISPATCH
                .and(RequestPredicates.method(method))
                .and(RequestPredicates.path(route.path()));
        String version = route.version();
        // After the path: only a versioned route's path carries a version, which Spring's strategy has resolved.
        return version == null ? predicate : predicate.and(RequestPredicates.version(version));
    }

    /** A route and its parsed template. */
    private record Ranked(RouteDefinition route, PathPattern pattern) {
    }
}
