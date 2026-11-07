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
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limits the request body to {@code synapse.api.rest.max-request-size}: it passes the request on as a
 * {@link SizeLimitedRequest}, and a route can replace the limit before the body is read ({@link RequestSizeLimits}).
 * A body over the limit fails the read with {@link RequestTooLargeException}, which the error handling answers with
 * {@code PAYLOAD_TOO_LARGE} (413) without reading the rest of the body. When a filter behind this one reads the body
 * and lets that failure out (Boot's {@code FormContentFilter} does for {@code PUT}, {@code PATCH} and {@code DELETE}
 * forms), the client error filter inside this one answers the 413
 * ({@link io.americanexpress.synapse.api.rest.internal.error.ClientErrorFilter}).
 *
 * <p>
 * After such a response, Tomcat reads and discards at most {@code server.tomcat.max-swallow-size} (2 MB by default) of
 * the unread body to keep the connection; a larger body closes the connection, and a client still sending it may see
 * the connection reset before it reads the 413.
 */
public final class RequestSizeLimitFilter extends OncePerRequestFilter implements Ordered {

    private final long defaultLimit;

    /**
     * Creates the filter.
     *
     * @param defaultLimit the limit of routes that don't set their own ({@code synapse.api.rest.max-request-size})
     */
    public RequestSizeLimitFilter(DataSize defaultLimit) {
        this.defaultLimit = defaultLimit.toBytes();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(new SizeLimitedRequest(request, defaultLimit), response);
    }
}
