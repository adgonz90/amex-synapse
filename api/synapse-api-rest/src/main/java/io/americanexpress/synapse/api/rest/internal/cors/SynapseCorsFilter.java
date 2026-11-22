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

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.filter.OncePerRequestFilter;

import io.americanexpress.synapse.api.rest.RestErrors;
import io.americanexpress.synapse.api.rest.internal.error.ProblemWriter;
import io.americanexpress.synapse.core.error.SynapseException;

/**
 * Applies CORS at the edge, before the limits and Spring MVC, so that every response Synapse writes carries the CORS
 * headers a browser page needs to read it: the 503 of the concurrency limit, the 413 of the size limit, the 405 of the
 * fallback and the problem responses of the error controller included. Spring's {@link DefaultCorsProcessor} decides:
 * <ul>
 * <li>no configuration for the request (CORS is off, or the path, or on an error dispatch the original URI, is a
 * management path): the chain runs;</li>
 * <li>no cross-origin request (no {@code Origin}, or the request's own): the chain runs, except for an
 * {@code OPTIONS} with {@code Origin} and {@code Access-Control-Request-Method}, which, as in Spring's
 * {@code CorsFilter}, is answered 200 without CORS headers;</li>
 * <li>a rejected request (origin, method or headers not allowed, or a malformed {@code Origin}): a
 * {@link RestErrors#CORS_REJECTED} problem response, and the chain doesn't run;</li>
 * <li>a valid pre-flight: 200 with the CORS headers and no body, and the chain doesn't run;</li>
 * <li>an actual request: the CORS headers are set, then the chain runs.</li>
 * </ul>
 * Only the processor's rejection is changed: instead of writing Spring's plain-text 403, it lets this filter write
 * the problem response.
 *
 * <p>
 * It handles the container's error dispatch too, and an error dispatch nested in the request dispatch, so an error
 * response whose headers were reset gets them again; the processor leaves a response that already has
 * {@code Access-Control-Allow-Origin} alone. It runs inside the context filter, so a rejection carries the request's
 * correlation id.
 */
public final class SynapseCorsFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger LOG = LoggerFactory.getLogger("io.americanexpress.synapse.api.rest");

    private final CorsConfigurationSource source;

    private final ProblemWriter writer;

    private final DefaultCorsProcessor processor = new ProblemRejectingCorsProcessor();

    /**
     * Creates the filter.
     *
     * @param source the CORS configuration of each request
     * @param writer writes the rejections
     */
    public SynapseCorsFilter(CorsConfigurationSource source, ProblemWriter writer) {
        this.source = source;
        this.writer = writer;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 3;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        apply(request, response, chain);
    }

    @Override
    protected void doFilterNestedErrorDispatch(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        apply(request, response, chain);
    }

    private void apply(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CorsConfiguration configuration = source.getCorsConfiguration(request);
        if (configuration == null) {
            chain.doFilter(request, response);
            return;
        }
        if (!processor.processRequest(configuration, request, response)) {
            LOG
                    .debug("Rejected {} {}: the cross-origin request is not allowed", request.getMethod(),
                            request.getRequestURI());
            writer.write(request, response, new SynapseException(RestErrors.CORS_REJECTED));
            return;
        }
        if (CorsUtils.isPreFlightRequest(request)) {
            return;
        }
        chain.doFilter(request, response);
    }

    /** Spring's processor, except that a rejection writes nothing: the filter answers it with a problem. */
    private static final class ProblemRejectingCorsProcessor extends DefaultCorsProcessor {

        @Override
        protected void rejectRequest(ServerHttpResponse response) {
            // processRequest returns false, and the filter writes the CORS_REJECTED problem.
        }
    }
}
