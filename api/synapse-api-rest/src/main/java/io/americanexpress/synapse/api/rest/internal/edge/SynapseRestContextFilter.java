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
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.micrometer.common.KeyValue;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.filter.ServerHttpObservationFilter;

import io.americanexpress.synapse.core.internal.context.ContextBinder;
import io.americanexpress.synapse.core.internal.context.ContextScope;
import io.americanexpress.synapse.core.internal.context.InboundContext;
import io.americanexpress.synapse.core.observability.SynapseObservations;

/**
 * Opens the Synapse request context for each request. It runs inside the span of Boot's server observation and after
 * {@link InboundPropagationFilter}, and reads:
 * <ul>
 * <li>the correlation id from the correlation header, else from the trusted baggage candidate;</li>
 * <li>each context attribute, in configured order, from its mapped header, else from the trusted baggage
 * candidate;</li>
 * <li>the locale from {@code Accept-Language}, only when that header is present;</li>
 * <li>the deadline from the request timeout header, through the {@link DeadlinePolicy}.</li>
 * </ul>
 * {@link ContextBinder} validates and limits these values. While the context is open, the filter exposes the correlation
 * id as the request attribute {@link RestRequestAttributes#CORRELATION_ID}, echoes it in the response correlation header
 * and tags the server observation with it ({@value SynapseObservations#CORRELATION_ID_KEY}). The context is closed on
 * this thread when the chain returns or throws.
 */
public final class SynapseRestContextFilter extends OncePerRequestFilter {

    private final ContextBinder binder;

    private final String correlationHeader;

    private final Map<String, String> attributeHeaders;

    private final String deadlineHeader;

    private final DeadlinePolicy deadlines;

    /**
     * Creates the filter.
     *
     * @param binder the binder that opens the context
     * @param correlationHeader the header that carries the correlation id ({@code synapse.correlation.header})
     * @param attributeHeaders the header of each context attribute, keyed by attribute name, in the order they are read
     *     ({@code synapse.api.rest.context.headers})
     * @param deadlineHeader the header with the caller's timeout in milliseconds
     *     ({@code synapse.api.rest.deadline.header})
     * @param deadlines the policy that turns that timeout into a deadline
     */
    public SynapseRestContextFilter(ContextBinder binder, String correlationHeader,
            Map<String, String> attributeHeaders,
            String deadlineHeader, DeadlinePolicy deadlines) {
        this.binder = binder;
        this.correlationHeader = correlationHeader;
        this.attributeHeaders = Collections.unmodifiableMap(new LinkedHashMap<>(attributeHeaders));
        this.deadlineHeader = deadlineHeader;
        this.deadlines = deadlines;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try (ContextScope scope = binder.open(inbound(request))) {
            String correlationId = scope.context().correlationId();
            request.setAttribute(RestRequestAttributes.CORRELATION_ID, correlationId);
            response.setHeader(correlationHeader, correlationId);
            ServerHttpObservationFilter
                    .findObservationContext(request)
                    .ifPresent(observation -> observation
                            .addHighCardinalityKeyValue(
                                    KeyValue.of(SynapseObservations.CORRELATION_ID_KEY, correlationId)));
            chain.doFilter(request, response);
        }
    }

    private InboundContext inbound(HttpServletRequest request) {
        Map<String, String> candidates = InboundBaggage.candidates(request);
        String correlationId = headerOrCandidate(request, correlationHeader, candidates,
                ContextBinder.CORRELATION_BAGGAGE_FIELD);
        Map<String, String> attributes = new LinkedHashMap<>();
        for (Map.Entry<String, String> attribute : attributeHeaders.entrySet()) {
            String value = headerOrCandidate(request, attribute.getValue(), candidates, attribute.getKey());
            if (value != null) {
                attributes.put(attribute.getKey(), value);
            }
        }
        Locale locale = request.getHeader(HttpHeaders.ACCEPT_LANGUAGE) == null ? null : request.getLocale();
        Instant deadline = deadlines.deadline(request.getHeader(deadlineHeader));
        // The principal comes from the verified credential, which this edge does not read yet.
        return new InboundContext(correlationId, attributes, null, locale, deadline);
    }

    /** The header value, or the trusted baggage candidate when the header is absent or empty. */
    private static @Nullable String headerOrCandidate(HttpServletRequest request, String header,
            Map<String, String> candidates, String field) {
        String value = request.getHeader(header);
        return value == null || value.isEmpty() ? candidates.get(field) : value;
    }
}
