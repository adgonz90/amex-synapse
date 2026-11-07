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
package io.americanexpress.synapse.api.rest.internal.cors;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import io.americanexpress.synapse.api.rest.autoconfigure.SynapseRestProperties;
import io.americanexpress.synapse.api.rest.internal.edge.ManagementPaths;

/**
 * The CORS configuration of the REST routes, built from {@code synapse.api.rest.cors}. Without allowed origins there
 * is none, so CORS is off. Otherwise one configuration covers every path except the management paths, which are left
 * to Spring Boot's {@code management.endpoints.web.cors} settings. That match fails closed
 * ({@link ManagementPaths#isUnderEitherPath}): a request gets no configuration when its mapped path, its raw URI as
 * Spring MVC matches it (decoded, without path parameters, dot segments kept) or that raw URI with its dot segments
 * resolved is under a management path. So spellings such as {@code /files/../actuator/env}, {@code /%61ctuator/env},
 * {@code /actuator;x/env} and {@code /actuator/health/../..} don't get the application's policy.
 *
 * <p>
 * The container's error dispatch is to the error page's path, so the error response of a management request gets no
 * configuration either: the source records, in a request attribute, that an earlier dispatch of the request may reach
 * a management path, and the error dispatch keeps that decision. For a request that failed before reaching the source,
 * the original URI the container reports ({@link RequestDispatcher#ERROR_REQUEST_URI}) decides, canonicalized with the
 * context path ({@link ManagementPaths#isOriginalUnderEitherPath}).
 *
 * <p>
 * The response headers browser pages may read are, in this order and without duplicates (ignoring case): the
 * correlation header, {@code ETag}, {@code Link}, {@code Location}, {@code Retry-After}, {@code Deprecation},
 * {@code Sunset}, then {@code synapse.api.rest.cors.exposed-headers}.
 */
public final class SynapseCorsConfigurationSource extends UrlBasedCorsConfigurationSource {

    private static final Logger LOG = LoggerFactory.getLogger("io.americanexpress.synapse.api.rest");

    private static final String ANY = "*";

    /**
     * The request attribute that records, from a dispatch other than the error dispatch, that the request may reach
     * a management path, so that its error dispatch keeps that decision.
     */
    static final String MANAGEMENT_ATTRIBUTE = SynapseCorsConfigurationSource.class.getName() + ".MANAGEMENT";

    /** The headers Synapse writes that browser pages need, after the correlation header. */
    private static final List<String> SYNAPSE_HEADERS = List
            .of(HttpHeaders.ETAG, HttpHeaders.LINK, HttpHeaders.LOCATION, HttpHeaders.RETRY_AFTER, "Deprecation",
                    "Sunset");

    private final List<String> managementPrefixes;

    private SynapseCorsConfigurationSource(List<String> managementPrefixes) {
        this.managementPrefixes = List.copyOf(managementPrefixes);
    }

    /**
     * Creates the source. When the origins contain {@code *}, it logs a warning.
     *
     * @param cors the CORS settings
     * @param correlationHeader the correlation header ({@code synapse.correlation.header}), always exposed
     * @param managementPrefixes the management path prefixes ({@link ManagementPaths#excludedPrefixes}), which get no
     *     configuration
     * @return the source
     */
    public static SynapseCorsConfigurationSource of(SynapseRestProperties.Cors cors, String correlationHeader,
            List<String> managementPrefixes) {
        SynapseCorsConfigurationSource source = new SynapseCorsConfigurationSource(managementPrefixes);
        if (cors.allowedOrigins().isEmpty()) {
            return source;
        }
        if (cors.allowedOrigins().contains(ANY)) {
            LOG
                    .warn("synapse.api.rest.cors.allowed-origins contains *: any website can call this API from a"
                            + " visitor's browser. List the exact origins instead (for example"
                            + " https://app.example.com).");
        }
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(cors.allowedOrigins());
        configuration.setAllowedMethods(cors.allowedMethods());
        configuration.setAllowedHeaders(cors.allowedHeaders());
        configuration.setExposedHeaders(exposedHeaders(correlationHeader, cors.exposedHeaders()));
        configuration.setAllowCredentials(cors.allowCredentials());
        configuration.setMaxAge(cors.maxAge());
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Override
    public @Nullable CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
        if (mayReachAManagementPath(request)) {
            return null;
        }
        return super.getCorsConfiguration(request);
    }

    /**
     * Whether the request may reach a management path. Outside the error dispatch, a positive answer is recorded in
     * {@link #MANAGEMENT_ATTRIBUTE}, never cleared. On the error dispatch, whose path is the error page's, the request
     * may reach one when its own path may, when an earlier dispatch recorded it, or when the original URI the container
     * reports may ({@link ManagementPaths#isOriginalUnderEitherPath}), for a request that failed before reaching this
     * source.
     */
    private boolean mayReachAManagementPath(HttpServletRequest request) {
        if (managementPrefixes.isEmpty()) {
            return false;
        }
        boolean current = ManagementPaths.isUnderEitherPath(request, managementPrefixes);
        if (request.getDispatcherType() != DispatcherType.ERROR) {
            if (current) {
                request.setAttribute(MANAGEMENT_ATTRIBUTE, Boolean.TRUE);
            }
            return current;
        }
        if (current || Boolean.TRUE.equals(request.getAttribute(MANAGEMENT_ATTRIBUTE))) {
            return true;
        }
        return request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String original
                && ManagementPaths.isOriginalUnderEitherPath(original, request.getContextPath(), managementPrefixes);
    }

    private static List<String> exposedHeaders(String correlationHeader, List<String> configured) {
        List<String> all = new ArrayList<>();
        all.add(correlationHeader);
        all.addAll(SYNAPSE_HEADERS);
        all.addAll(configured);
        Set<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        return all.stream().filter(seen::add).toList();
    }
}
