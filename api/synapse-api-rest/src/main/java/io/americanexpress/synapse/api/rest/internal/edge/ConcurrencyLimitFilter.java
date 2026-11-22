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

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import io.americanexpress.synapse.api.rest.internal.error.ProblemWriter;
import io.americanexpress.synapse.api.rest.internal.error.RestProtocolException;
import io.americanexpress.synapse.core.error.SynapseErrors;

/**
 * Caps the requests handled at the same time ({@code synapse.api.rest.max-concurrent-requests}). A request that finds
 * the limit reached is answered {@code UNAVAILABLE} (503) with {@code Retry-After: 1}, and nothing behind this filter
 * runs. It sits inside the context filter and the CORS filter, so the 503 carries the request's correlation id and,
 * for an allowed origin, the CORS headers.
 *
 * <p>
 * A request holds its permit until the chain returns or throws, or, when the handler started async processing, until
 * the async request completes, fails or times out. The permit is released once. Requests to the management paths
 * ({@link ManagementPaths}) are never counted, so health probes are answered under full load; a request counts as one
 * only when both the path the container mapped and the raw request URI are under the management path
 * ({@link ManagementPaths#isManagementRequest}). The semaphore is not fair: a request either gets a permit at once or
 * is rejected, and never waits.
 */
public final class ConcurrencyLimitFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger LOG = LoggerFactory.getLogger("io.americanexpress.synapse.api.rest");

    private static final String RETRY_AFTER_SECONDS = "1";

    private final Semaphore permits;

    private final List<String> excludedPrefixes;

    private final ProblemWriter writer;

    /**
     * Creates the filter.
     *
     * @param maxConcurrent the most requests handled at the same time
     * @param excludedPrefixes the path prefixes, within the application, of requests that are never counted
     * @param writer writes the 503 response
     */
    public ConcurrencyLimitFilter(int maxConcurrent, List<String> excludedPrefixes, ProblemWriter writer) {
        this.permits = new Semaphore(maxConcurrent);
        this.excludedPrefixes = List.copyOf(excludedPrefixes);
        this.writer = writer;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 4;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (ManagementPaths.isManagementRequest(request, excludedPrefixes)) {
            chain.doFilter(request, response);
            return;
        }
        if (!permits.tryAcquire()) {
            LOG
                    .debug("Rejected {} {}: the concurrent request limit is reached", request.getMethod(),
                            request.getRequestURI());
            writer
                    .write(request, response, new RestProtocolException(SynapseErrors.UNAVAILABLE,
                            Map.of("Retry-After", RETRY_AFTER_SECONDS)));
            return;
        }
        Permit permit = new Permit(permits);
        try {
            chain.doFilter(request, response);
        } finally {
            holdUntilTheRequestEnds(request, permit);
        }
    }

    private static void holdUntilTheRequestEnds(HttpServletRequest request, Permit permit) {
        if (!request.isAsyncStarted()) {
            permit.release();
            return;
        }
        try {
            request.getAsyncContext().addListener(permit);
        } catch (IllegalStateException e) {
            // The async request ended before the listener could be added; nothing will call it.
            LOG.debug("Released the permit of {} {}: {}", request.getMethod(), request.getRequestURI(), e.toString());
            permit.release();
        }
    }

    /** One request's permit, released exactly once, also from the container's async listener callbacks. */
    private static final class Permit implements AsyncListener {

        private final Semaphore permits;

        private final AtomicBoolean released = new AtomicBoolean();

        Permit(Semaphore permits) {
            this.permits = permits;
        }

        void release() {
            if (released.compareAndSet(false, true)) {
                permits.release();
            }
        }

        @Override
        public void onComplete(AsyncEvent event) {
            release();
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            release();
        }

        @Override
        public void onError(AsyncEvent event) {
            release();
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            // Starting async again clears the listeners; stay registered until the request really ends.
            event.getAsyncContext().addListener(this);
        }
    }
}
