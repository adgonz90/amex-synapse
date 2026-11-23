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

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.api.rest.internal.binding.WireNames;

/**
 * Names of the request attributes the REST edge sets, and typed readers for them.
 */
public final class RestRequestAttributes {

    /** Request attribute holding the correlation id of the request. */
    public static final String CORRELATION_ID = RestRequestAttributes.class.getName() + ".correlationId";

    /** Request attribute holding the name of the operation that handles the request. */
    public static final String OPERATION_NAME = RestRequestAttributes.class.getName() + ".operationName";

    /**
     * Request attribute holding the {@link WireNames} of the route that handles the request, set once its input is
     * bound and checked, just before the operation runs: the problem renames the violations the operation raises.
     */
    public static final String WIRE_NAMES = RestRequestAttributes.class.getName() + ".wireNames";

    private RestRequestAttributes() {
    }

    /**
     * Returns the correlation id the edge set for the request.
     *
     * @param request the request
     * @return the correlation id, or {@code null} when the edge has not set one
     */
    public static @Nullable String correlationId(HttpServletRequest request) {
        return string(request.getAttribute(CORRELATION_ID));
    }

    /**
     * Returns the name of the operation that handles the request.
     *
     * @param request the request
     * @return the operation name, or {@code null} when the request is not handled by an operation
     */
    public static @Nullable String operationName(HttpServletRequest request) {
        return string(request.getAttribute(OPERATION_NAME));
    }

    /**
     * Returns the wire names of the route whose operation handles the request.
     *
     * @param request the request
     * @return the names, or {@code null} before the operation runs, or when no Synapse route handles the request
     */
    public static @Nullable WireNames wireNames(HttpServletRequest request) {
        return request.getAttribute(WIRE_NAMES) instanceof WireNames names ? names : null;
    }

    private static @Nullable String string(@Nullable Object value) {
        return value instanceof String s ? s : null;
    }
}
