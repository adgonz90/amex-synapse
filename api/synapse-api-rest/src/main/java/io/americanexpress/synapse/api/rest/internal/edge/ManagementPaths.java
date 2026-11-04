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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

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
