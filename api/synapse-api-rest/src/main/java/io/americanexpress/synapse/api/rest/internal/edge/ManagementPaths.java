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
package io.americanexpress.synapse.api.rest.internal.edge;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import jakarta.servlet.http.HttpServletRequest;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import org.springframework.web.util.UriUtils;

/**
 * The paths at which the application's own server serves Actuator, so that the edge limits leave them alone. Without
 * Actuator on the classpath (no {@value #ACTUATOR_CLASS}) there are none. Otherwise it mirrors Spring Boot's rules,
 * read from Boot's properties (Synapse has none of its own here):
 * <ul>
 * <li>{@code management.endpoints.web.base-path} (default {@code /actuator}, a trailing {@code /} removed), when the
 * endpoints are served on the server's port: {@code management.server.port} is unset, or equals {@code server.port}
 * (8080 when unset), and is not 0 (a random port of its own) or negative (endpoints disabled over HTTP). A base path
 * of {@code /} puts every endpoint at the root, so there is no prefix that holds only them, and none is returned;</li>
 * <li>each {@code management.endpoint.health.group.<name>.additional-path} in the {@code server:} namespace, with the
 * prefix removed. Boot serves these on the server's port wherever the other endpoints are;</li>
 * <li>each path is prefixed with {@code spring.mvc.servlet.path}, because Actuator's handler mappings live in the
 * {@code DispatcherServlet}.</li>
 * </ul>
 */
public final class ManagementPaths {

    /** A class of {@code spring-boot-actuator} (checked in 4.1.2) that tells whether Actuator is present. */
    static final String ACTUATOR_CLASS = "org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier";

    private static final String DEFAULT_BASE_PATH = "/actuator";

    private static final String SERVER_NAMESPACE = "server:";

    private static final int DEFAULT_SERVER_PORT = 8080;

    /** A {@code ;} path parameter of a segment, up to the next {@code /}. */
    private static final Pattern PATH_PARAMETERS = Pattern.compile(";[^/]*");

    private static final Pattern REPEATED_SLASHES = Pattern.compile("/{2,}");

    private ManagementPaths() {
    }

    /**
     * Returns the path prefixes of the management endpoints the application's server serves.
     *
     * @param environment the environment
     * @param classLoader the application's class loader, to look for Actuator ({@code null} for the default)
     * @return the prefixes, each starting with {@code /} and without a trailing {@code /}; empty without Actuator
     */
    public static List<String> excludedPrefixes(Environment environment, @Nullable ClassLoader classLoader) {
        return excludedPrefixes(environment, ClassUtils.isPresent(ACTUATOR_CLASS, classLoader));
    }

    /**
     * Returns the path prefixes of the management endpoints the application's server serves.
     *
     * @param environment the environment
     * @param actuatorPresent whether Actuator is on the classpath
     * @return the prefixes; empty without Actuator
     */
    static List<String> excludedPrefixes(Environment environment, boolean actuatorPresent) {
        if (!actuatorPresent) {
            return List.of();
        }
        String servletPath = clean(environment.getProperty("spring.mvc.servlet.path", ""));
        List<String> prefixes = new ArrayList<>();
        if (servedByTheServer(environment)) {
            String basePath = clean(environment.getProperty("management.endpoints.web.base-path", DEFAULT_BASE_PATH));
            if (!basePath.isEmpty()) {
                prefixes.add(servletPath + basePath);
            }
        }
        Map<String, HealthGroup> groups = Binder
                .get(environment)
                .bind("management.endpoint.health.group", Bindable.mapOf(String.class, HealthGroup.class))
                .orElse(Map.of());
        for (HealthGroup group : groups.values()) {
            String path = group.additionalPath();
            if (path != null && path.startsWith(SERVER_NAMESPACE)) {
                String cleaned = clean(path.substring(SERVER_NAMESPACE.length()));
                if (!cleaned.isEmpty()) {
                    prefixes.add(servletPath + cleaned);
                }
            }
        }
        return List.copyOf(prefixes);
    }

    /**
     * Tells whether a request is to one of the management paths. Both the path the container mapped and the raw request
     * URI must be under the same prefix: a raw URI whose dot segments only normalize to a management path, such as
     * {@code /files/../actuator/health}, is not a management request.
     *
     * @param request the request
     * @param prefixes the management path prefixes within the application ({@link #excludedPrefixes})
     * @return whether the request is under one of the prefixes; {@code false} when there are none
     */
    public static boolean isManagementRequest(HttpServletRequest request, List<String> prefixes) {
        if (prefixes.isEmpty()) {
            return false;
        }
        String raw = rawPathWithinApplication(request);
        String mapped = mappedPath(request, raw);
        for (String prefix : prefixes) {
            if (isUnder(mapped, prefix) && isUnder(raw, prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether a request may reach a management path, failing closed: it does when any of three views of its path
     * is under a prefix.
     * <ul>
     * <li>The path the container mapped: servlet path plus path info, with dot segments and repeated {@code /}
     * resolved.</li>
     * <li>The raw request URI as Spring MVC matches it: without the context path, with its {@code ;} path parameters
     * removed, percent-decoded (left as sent when an escape is invalid) and repeated {@code /} collapsed, but with its
     * dot segments kept. Spring matches these segments as they are, so {@code /actuator/health/../..} reaches
     * {@code /actuator/health/{*path}} although the container maps it to {@code /}.</li>
     * <li>That raw path with its dot segments resolved, so {@code /files/../actuator/env} is one too.</li>
     * </ul>
     * For settings that must not reach a management response, such as CORS; {@link #isManagementRequest} is the strict
     * match for exemptions.
     *
     * @param request the request
     * @param prefixes the management path prefixes within the application ({@link #excludedPrefixes})
     * @return whether any view of the path is under one of the prefixes; {@code false} when there are none
     */
    public static boolean isUnderEitherPath(HttpServletRequest request, List<String> prefixes) {
        if (prefixes.isEmpty()) {
            return false;
        }
        String servletPath = request.getServletPath();
        String pathInfo = request.getPathInfo();
        String mapped = normalize(pathInfo == null ? servletPath : servletPath + pathInfo);
        return isUnderAny(mapped, prefixes) || isUnderEitherPath(rawPathWithinApplication(request), prefixes);
    }

    /**
     * Tells whether a raw path within the application may reach a management path, failing closed, when there is no
     * request for it, such as the original URI the container reports on an error dispatch. It applies the views of
     * {@link #isUnderEitherPath(HttpServletRequest, List)}: the path as Spring MVC matches it ({@code ;} path
     * parameters removed, percent-decoded, repeated {@code /} collapsed, dot segments kept), and that path with its
     * dot segments resolved, which also stands in for the path the container would map.
     *
     * @param rawPath the path as the caller sent it, without the context path
     * @param prefixes the management path prefixes within the application ({@link #excludedPrefixes})
     * @return whether any view of the path is under one of the prefixes; {@code false} when there are none
     */
    public static boolean isUnderEitherPath(String rawPath, List<String> prefixes) {
        if (prefixes.isEmpty()) {
            return false;
        }
        String springRaw = springView(rawPath);
        return isUnderAny(springRaw, prefixes) || isUnderAny(normalize(springRaw), prefixes);
    }

    /**
     * Tells whether the original URI the container reports on an error dispatch
     * ({@code jakarta.servlet.error.request_uri}) may reach a management path, failing closed. On the error dispatch
     * Tomcat gives the context path canonically ({@code /ctx}), while the original URI is as the caller sent it, and
     * Tomcat routes spellings such as {@code /%63tx/actuator/env}, {@code //ctx/actuator/env},
     * {@code /foo/../ctx/actuator/env} and {@code /ctx;x=1/actuator/env} to the context. So both are canonicalized into
     * the views of {@link #isUnderEitherPath(String, List)} (the path as Spring MVC matches it, and that path with its
     * dot segments resolved), and the context path is removed from a view only on a segment boundary. The URI may reach
     * a management path when any view, with or without the context path removed, is under a prefix, or under the
     * context path followed by a prefix.
     *
     * @param originalUri the original request URI, with the context path, as the caller sent it
     * @param contextPath the context path the container gives on the error dispatch ({@code ""} for the root)
     * @param prefixes the management path prefixes within the application ({@link #excludedPrefixes})
     * @return whether any view of the URI is under one of the prefixes; {@code false} when there are none
     */
    public static boolean isOriginalUnderEitherPath(String originalUri, String contextPath, List<String> prefixes) {
        if (prefixes.isEmpty()) {
            return false;
        }
        String spring = springView(originalUri);
        List<String> contexts = contextViews(contextPath);
        for (String view : List.of(spring, normalize(spring))) {
            if (isUnderAny(view, prefixes)) {
                return true;
            }
            for (String context : contexts) {
                String withinApplication = withoutContext(view, context);
                if (withinApplication != null && isUnderAny(withinApplication, prefixes)) {
                    return true;
                }
                for (String prefix : prefixes) {
                    if (isUnder(view, context + prefix)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** The path as Spring MVC matches it: {@code ;} parameters removed, percent-decoded, repeated {@code /} collapsed. */
    private static String springView(String rawPath) {
        return REPEATED_SLASHES.matcher(decode(PATH_PARAMETERS.matcher(rawPath).replaceAll(""))).replaceAll("/");
    }

    /** The canonical views of a context path, without the root, which has nothing to remove. */
    private static List<String> contextViews(String contextPath) {
        String spring = springView(contextPath);
        List<String> views = new ArrayList<>();
        for (String view : List.of(spring, normalize(spring))) {
            if (!view.isEmpty() && !"/".equals(view) && !views.contains(view)) {
                views.add(view);
            }
        }
        return views;
    }

    /** The view without the context path when it starts with it on a segment boundary; otherwise {@code null}. */
    private static @Nullable String withoutContext(String view, String context) {
        if (view.equals(context)) {
            return "/";
        }
        return view.startsWith(context + "/") ? view.substring(context.length()) : null;
    }

    private static boolean isUnderAny(String path, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (isUnder(path, prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String decode(String path) {
        try {
            return UriUtils.decode(path, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // An invalid escape: the container rejects or keeps it, so match the path as sent.
            return path;
        }
    }

    /** The path with {@code .} and {@code ..} segments resolved and empty segments dropped; {@code /} for the root. */
    private static String normalize(String path) {
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                segments.pollLast();
            } else {
                segments.addLast(segment);
            }
        }
        return "/" + String.join("/", segments);
    }

    private static boolean isUnder(String path, String prefix) {
        return path.startsWith(prefix) && (path.length() == prefix.length() || path.charAt(prefix.length()) == '/');
    }

    /**
     * The decoded, normalized path the container mapped: servlet path plus path info. When the container gives neither
     * (no servlet mapping yet), the raw path.
     */
    private static String mappedPath(HttpServletRequest request, String raw) {
        String servletPath = request.getServletPath();
        String pathInfo = request.getPathInfo();
        String mapped = pathInfo == null ? servletPath : servletPath + pathInfo;
        return mapped.isEmpty() ? raw : mapped;
    }

    /** The request URI as the caller sent it, neither decoded nor normalized, without the context path. */
    private static String rawPathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return uri.startsWith(contextPath) ? uri.substring(contextPath.length()) : uri;
    }

    /** Boot's {@code ManagementPortType}: the endpoints share the server's port. */
    private static boolean servedByTheServer(Environment environment) {
        Integer managementPort = environment.getProperty("management.server.port", Integer.class);
        if (managementPort == null) {
            return true;
        }
        if (managementPort <= 0) {
            // Negative: disabled over HTTP. Zero: a random port of its own.
            return false;
        }
        Integer serverPort = environment.getProperty("server.port", Integer.class);
        return managementPort.equals(serverPort == null ? DEFAULT_SERVER_PORT : serverPort);
    }

    /** A path with a leading {@code /} and no trailing {@code /}; empty for the root. */
    private static String clean(String path) {
        String trimmed = path.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            return "";
        }
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    /**
     * The part of a health group this class reads. Spring Boot's {@code Binder} constructs it reflectively, so it is
     * public for the REST plugin's runtime hints to register it.
     *
     * @param additionalPath the group's additional path, such as {@code server:/health}
     */
    public record HealthGroup(@Nullable String additionalPath) {
    }
}
