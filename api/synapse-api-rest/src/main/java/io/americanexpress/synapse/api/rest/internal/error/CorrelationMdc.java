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

import jakarta.servlet.http.HttpServletRequest;

import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

import io.americanexpress.synapse.api.rest.internal.edge.RestRequestAttributes;
import io.americanexpress.synapse.core.context.RequestContext;
import io.americanexpress.synapse.core.internal.context.ContextBinder;

/**
 * Puts the edge's correlation id in the MDC for a failure logged outside the request context. The context filter
 * handles REQUEST dispatches only, so on an ERROR or ASYNC dispatch the context, and with it the MDC entry that tracing
 * maintains, is already closed.
 */
final class CorrelationMdc {

    private CorrelationMdc() {
    }

    /**
     * Puts the request's correlation id in the MDC when no request context is open.
     *
     * @param request the request
     * @return the entry to close once the failure is logged, or {@code null} when a context is open (tracing owns the
     *     MDC entry then) or the edge set no correlation id
     */
    static MDC.@Nullable MDCCloseable put(HttpServletRequest request) {
        if (RequestContext.find().isPresent()) {
            return null;
        }
        String correlationId = RestRequestAttributes.correlationId(request);
        return correlationId == null
                ? null
                : MDC.putCloseable(ContextBinder.CORRELATION_BAGGAGE_FIELD, correlationId);
    }
}
