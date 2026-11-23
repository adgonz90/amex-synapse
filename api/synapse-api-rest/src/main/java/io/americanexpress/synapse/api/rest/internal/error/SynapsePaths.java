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
package io.americanexpress.synapse.api.rest.internal.error;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;

import org.jspecify.annotations.Nullable;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import io.americanexpress.synapse.api.rest.internal.binding.BindingValidator;
import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.api.rest.internal.route.OperationRoutes;

/**
 * The paths the Synapse routes serve, which tell the Synapse-paths instance of {@link SynapseRestExceptionResolver}
 * whether an exception raised before a handler was found belongs to the Synapse API. A path matches when a route's
 * served path template (the {@code /v<N>} prefix of a versioned route included) matches it, whatever the request's
 * method. The templates are parsed as {@code RequestPredicates.path} parses a route's, and matched against the
 * request's path within the application, as Spring MVC's handler mappings read it, so the context path and a servlet
 * path prefix are left out.
 *
 * <p>
 * The routes also own the paths under their literal prefixes ({@link #owns}), so an unknown path there is Synapse's
 * 404. A route's literal prefix is its declared path's segments up to its first variable or pattern segment:
 * {@code /api/books} for {@code /api/books/{id}}, which owns {@code /api/books/nope} but not {@code /api/other}. A
 * versioned route owns that prefix under every version ({@code /v9/books/x} for {@code /v1/books/{id}}), and neither
 * another prefix under a version ({@code /v9/unknown}) nor its unversioned prefix ({@code /books/x}). A route without
 * a literal prefix, whose first segment (after the version, on a versioned route) is a variable ({@code /{slug}}), owns
 * only its template, under every version when it is versioned.
 */
public final class SynapsePaths {

    private static final SynapsePaths NONE = new SynapsePaths(List.of(), List.of());

    private final List<PathPattern> templates;

    /** What each route owns beyond its served path; see {@link Claim}. */
    private final List<Claim> claims;

    private SynapsePaths(List<PathPattern> templates, List<Claim> claims) {
        this.templates = templates;
        this.claims = claims;
    }

    /**
     * Returns the paths of an application without Synapse routes: no request matches.
     *
     * @return the empty paths
     */
    public static SynapsePaths none() {
        return NONE;
    }

    /**
     * Returns the served paths of the Synapse routes in the router function. Routes that no Synapse operation serves
     * are not Synapse paths.
     *
     * @param routes the router function, normally the {@code synapseRestRoutes} bean
     * @return the paths
     */
    public static SynapsePaths of(RouterFunction<?> routes) {
        PathPatternParser parser = PathPatternParser.defaultInstance;
        Set<String> paths = new LinkedHashSet<>();
        Set<Claim> claims = new LinkedHashSet<>();
        for (RouteDefinition route : OperationRoutes.of(List.of(routes))) {
            paths.add(route.path());
            claims.add(Claim.of(route, parser));
        }
        if (paths.isEmpty()) {
            return NONE;
        }
        List<PathPattern> templates = new ArrayList<>(paths.size());
        paths.forEach(path -> templates.add(parser.parse(parser.initFullPathPattern(path))));
        return new SynapsePaths(List.copyOf(templates), List.copyOf(claims));
    }

    /**
     * Tells whether the request's path, within the application, is one a Synapse route owns: a path under a route's
     * literal prefix (under any version, {@code v} and {@code major[.minor[.patch]]}, for a versioned route), or the
     * template of a route without one. The path is read as {@link #matches} reads it.
     *
     * @param request the request
     * @return true if the path is under a Synapse route's literal prefix, or matches the template of one without
     */
    public boolean owns(HttpServletRequest request) {
        if (claims.isEmpty()) {
            return false;
        }
        PathContainer within = within(request);
        List<String> segments = new ArrayList<>();
        for (PathContainer.Element element : within.elements()) {
            if (element instanceof PathContainer.PathSegment segment) {
                segments.add(segment.valueToMatch());
            }
        }
        return claims.stream().anyMatch(claim -> claim.owns(segments, within));
    }

    /**
     * Tells whether the request's path, within the application, matches a Synapse route's served path, whatever the
     * method. The path Spring MVC parsed for the request is read when there is one; otherwise the request is parsed
     * without caching the result on it.
     *
     * @param request the request
     * @return true if a Synapse route serves the path
     */
    public boolean matches(HttpServletRequest request) {
        if (templates.isEmpty()) {
            return false;
        }
        PathContainer within = within(request);
        return templates.stream().anyMatch(template -> template.matches(within));
    }

    private static PathContainer within(HttpServletRequest request) {
        RequestPath path = ServletRequestPathUtils.hasParsedRequestPath(request)
                ? ServletRequestPathUtils.getParsedRequestPath(request)
                : ServletRequestPathUtils.parse(request);
        return path.pathWithinApplication();
    }

    /**
     * What one route owns. With a literal prefix (its declared path's segments before the first variable or pattern
     * segment), every path that starts with those segments; otherwise only its declared template. A versioned route
     * owns them after any version segment.
     *
     * @param versioned whether the first segment of an owned path is {@code v} and any version
     * @param prefix the literal prefix's segments; empty when the route has none
     * @param template the declared template, after a {@code /*} that stands for the version segment on a versioned
     *        route; {@code null} when the route has a literal prefix
     */
    private record Claim(boolean versioned, List<String> prefix, @Nullable PathPattern template) {

        static Claim of(RouteDefinition route, PathPatternParser parser) {
            boolean versioned = route.version() != null;
            String declared = route.declaredPath();
            List<String> prefix = literalPrefix(declared);
            if (!prefix.isEmpty()) {
                return new Claim(versioned, prefix, null);
            }
            // A versioned route's declared path starts with "/"; "/" alone is served at the version segment itself.
            String template = versioned ? "/*" + ("/".equals(declared) ? "" : declared) : declared;
            return new Claim(versioned, List.of(), parser.parse(parser.initFullPathPattern(template)));
        }

        /** The segments of a path template before its first one with a variable, a wildcard or other pattern syntax. */
        private static List<String> literalPrefix(String template) {
            List<String> prefix = new ArrayList<>();
            // Empty segments (a leading, doubled or trailing "/") are skipped below.
            for (String segment : template.split("/", -1)) {
                if (segment.isEmpty()) {
                    continue;
                }
                if (segment.chars().anyMatch(c -> c == '{' || c == '}' || c == '*' || c == '?')) {
                    break;
                }
                prefix.add(segment);
            }
            return List.copyOf(prefix);
        }

        boolean owns(List<String> segments, PathContainer path) {
            int start = 0;
            if (versioned) {
                if (segments.isEmpty() || !isVersionSegment(segments.getFirst())) {
                    return false;
                }
                start = 1;
            }
            PathPattern exact = template;
            if (exact != null) {
                return exact.matches(path);
            }
            return segments.size() - start >= prefix.size()
                    && segments.subList(start, start + prefix.size()).equals(prefix);
        }

        private static boolean isVersionSegment(String segment) {
            return segment.startsWith("v") && BindingValidator.isVersion(segment.substring(1));
        }
    }
}
