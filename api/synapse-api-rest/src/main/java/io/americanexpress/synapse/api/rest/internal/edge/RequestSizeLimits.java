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

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.unit.DataSize;
import org.springframework.web.util.WebUtils;

/**
 * Applies a route's own request body limit ({@code .maxRequestSize(...)}) in place of the default that
 * {@link RequestSizeLimitFilter} set.
 */
public final class RequestSizeLimits {

    private static final Logger LOG = LoggerFactory.getLogger("io.americanexpress.synapse.api.rest");

    private RequestSizeLimits() {
    }

    /**
     * Replaces the body limit of the request. It must be called before the body is read: once the body stream is open,
     * the limit in force stays, and that is logged at DEBUG. A request that did not pass through {@link RequestSizeLimitFilter} is left as it is.
     *
     * @param request the request, or any wrapper around it
     * @param limit the route's limit
     */
    public static void apply(HttpServletRequest request, DataSize limit) {
        SizeLimitedRequest limited = WebUtils.getNativeRequest(request, SizeLimitedRequest.class);
        if (limited != null && !limited.limit(limit.toBytes())) {
            LOG
                    .debug("Kept the request body limit of {} bytes: the body stream was already open when the route "
                            + "limit of {} bytes was applied to {} {}", limited.limit(), limit.toBytes(),
                            request.getMethod(), request.getRequestURI());
        }
    }
}
