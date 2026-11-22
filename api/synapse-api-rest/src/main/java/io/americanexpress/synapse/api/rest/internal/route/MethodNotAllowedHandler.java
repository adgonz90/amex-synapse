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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import jakarta.servlet.DispatcherType;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import io.americanexpress.synapse.api.rest.RestErrors;
import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.api.rest.internal.error.RestProtocolException;

/**
 * The 405 and {@code OPTIONS} fallback of the Synapse routes, served by the {@link MethodNotAllowedMapping} after
 * every other handler mapping. It matches only a path that matches a Synapse path template, and never the servlet
 * container's error dispatch, which belongs to the error controller. A request that reaches it has a method that no
 * Synapse route and no other handler serves for that path: it answers {@code OPTIONS} with 200 and {@code Allow}, and
 * any other method with {@link RestErrors#METHOD_NOT_ALLOWED} and {@code Allow}. A CORS pre-flight for a method a
 * Synapse route serves is answered with that route's mapping. Any other path is left to Spring MVC: unknown paths
 * still reach the static resource handler ({@code ROUTE_NOT_FOUND}). Functional routes never raise 405 themselves.
 */
public final class MethodNotAllowedHandler implements HandlerFunction<ServerResponse>, RequestPredicate {

    /** The order of the methods in {@code Allow}. */
    private static final List<HttpMethod> ORDER = List
            .of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE,
                    HttpMethod.OPTIONS);

    private final List<Template> templates;

    /**
     * Creates the handler.
     *
     * @param routes the Synapse routes
     */
    public MethodNotAllowedHandler(List<RouteDefinition> routes) {
        Map<String, Set<HttpMethod>> byPath = new LinkedHashMap<>();
        for (RouteDefinition route : routes) {
            byPath.computeIfAbsent(route.path(), path -> new HashSet<>()).add(route.method());
        }
        List<Template> parsed = new ArrayList<>(byPath.size());
        PathPatternParser parser = PathPatternParser.defaultInstance;
        // Parsed as RequestPredicates.path parses the route's template, so both match the same paths.
        byPath
                .forEach((path, methods) -> parsed
                        .add(new Template(parser.parse(parser.initFullPathPattern(path)), Set.copyOf(methods))));
        this.templates = List.copyOf(parsed);
    }

    /**
     * Tells whether the request path matches a Synapse path template, the way the routes match it, outside the
     * container's error dispatch.
     *
     * @param request the request
     * @return true if a template matches the path and the request is not an error dispatch
     */
    @Override
    public boolean test(ServerRequest request) {
        if (request.servletRequest().getDispatcherType() == DispatcherType.ERROR) {
            return false;
        }
        PathContainer path = request.requestPath().pathWithinApplication();
        return templates.stream().anyMatch(template -> template.pattern().matches(path));
    }

    /**
     * Answers {@code OPTIONS} with 200 and {@code Allow}, and rejects any other method with
     * {@link RestErrors#METHOD_NOT_ALLOWED} and {@code Allow}. {@code Allow} lists the methods declared for the
     * templates that match the path, plus {@code HEAD} when {@code GET} is declared, plus {@code OPTIONS}, in
     * {@code GET, HEAD, POST, PUT, DELETE, OPTIONS} order.
     *
     * @param request the request
     * @return the {@code OPTIONS} response
     * @throws RestProtocolException for any method other than {@code OPTIONS}
     */
    @Override
    public ServerResponse handle(ServerRequest request) {
        PathContainer path = request.requestPath().pathWithinApplication();
        Set<HttpMethod> allowed = new HashSet<>();
        for (Template template : templates) {
            if (template.pattern().matches(path)) {
                allowed.addAll(template.methods());
            }
        }
        if (allowed.contains(HttpMethod.GET)) {
            allowed.add(HttpMethod.HEAD);
        }
        allowed.add(HttpMethod.OPTIONS);
        String allow = ORDER
                .stream()
                .filter(allowed::contains)
                .map(HttpMethod::name)
                .collect(Collectors.joining(", "));
        if (HttpMethod.OPTIONS.equals(request.method())) {
            return ServerResponse.ok().header(HttpHeaders.ALLOW, allow).build();
        }
        throw new RestProtocolException(RestErrors.METHOD_NOT_ALLOWED, Map.of(HttpHeaders.ALLOW, allow));
    }

    @Override
    public String toString() {
        return "405 fallback for " + templates.stream().map(template -> template.pattern().getPatternString()).toList();
    }

    /** A path template and the methods declared for it. */
    private record Template(PathPattern pattern, Set<HttpMethod> methods) {
    }
}
