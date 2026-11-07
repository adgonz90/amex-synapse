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
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.springframework.web.filter.OncePerRequestFilter;

import io.americanexpress.synapse.core.internal.context.BaggageHeaders;
import io.americanexpress.synapse.core.internal.context.TraceHeaders;

/**
 * Hides or strips inbound propagation headers before tracing reads them. It runs first, before Boot's server
 * observation filter extracts the caller's trace and baggage from the request headers.
 * <ul>
 * <li>When inbound context is not trusted, it hides the trace headers ({@link TraceHeaders#NAMES}), the {@code baggage}
 * header and the header of every remote baggage field, so the request starts a new trace with no caller baggage.</li>
 * <li>When it is trusted, it removes the Synapse fields (the correlation id and the context attribute names) from the
 * {@code baggage} header and hides their remote-field headers. Their values become {@link InboundBaggage} candidates
 * for the context filter to validate; everything else passes through.</li>
 * </ul>
 * Header names match case-insensitively. Only the view passed down the chain changes; the request is not modified.
 */
public final class InboundPropagationFilter extends OncePerRequestFilter {

    private final boolean trusted;

    private final Set<String> synapseFields;

    private final Set<String> synapseFieldMatcher;

    private final Set<String> untrustedHidden;

    /**
     * Creates the filter.
     *
     * @param trusted whether inbound context is trusted ({@code synapse.context.inbound.trusted})
     * @param synapseFields the Synapse baggage fields: the correlation id field and the context attribute names
     * @param remoteFields the remote baggage fields ({@code management.tracing.baggage.remote-fields}), each read
     *     from the header of the same name
     */
    public InboundPropagationFilter(boolean trusted, Set<String> synapseFields, Set<String> remoteFields) {
        this.trusted = trusted;
        this.synapseFields = Collections.unmodifiableSet(new LinkedHashSet<>(synapseFields));
        this.synapseFieldMatcher = caseInsensitive(synapseFields);
        Set<String> hidden = caseInsensitive(TraceHeaders.NAMES);
        hidden.add(BaggageHeaders.HEADER);
        hidden.addAll(remoteFields);
        // Synapse fields are remote fields once core has merged them; hide their headers even if that did not run.
        hidden.addAll(synapseFields);
        this.untrustedHidden = Collections.unmodifiableSet(hidden);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain
                .doFilter(trusted ? trustedView(request) : new HeaderMaskingRequest(request, untrustedHidden, null),
                        response);
    }

    private HttpServletRequest trustedView(HttpServletRequest request) {
        String baggage = joined(request);
        List<BaggageHeaders.Entry> entries = BaggageHeaders.parse(baggage);
        Map<String, String> candidates = new LinkedHashMap<>();
        for (String field : synapseFields) {
            String value = request.getHeader(field);
            if (value == null) {
                value = baggageValue(entries, field);
            }
            if (value != null) {
                candidates.put(field, value);
            }
        }
        request.setAttribute(InboundBaggage.ATTRIBUTE, Collections.unmodifiableMap(candidates));
        Set<String> hidden = caseInsensitive(synapseFields);
        String replacement = BaggageHeaders.remove(baggage, synapseFieldMatcher);
        if (baggage != null && replacement == null) {
            hidden.add(BaggageHeaders.HEADER);
        }
        return new HeaderMaskingRequest(request, hidden, replacement);
    }

    /** Joins every {@code baggage} header line, as W3C Baggage allows, so no line slips past the stripping. */
    private static @Nullable String joined(HttpServletRequest request) {
        String joined = String.join(",", Collections.list(request.getHeaders(BaggageHeaders.HEADER)));
        return joined.isEmpty() ? null : joined;
    }

    /** The first member for the field, matched case-insensitively as Micrometer's baggage lookup does. */
    private static @Nullable String baggageValue(List<BaggageHeaders.Entry> entries, String field) {
        for (BaggageHeaders.Entry entry : entries) {
            if (entry.key().equalsIgnoreCase(field)) {
                return entry.value();
            }
        }
        return null;
    }

    private static Set<String> caseInsensitive(Collection<String> names) {
        Set<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        set.addAll(names);
        return set;
    }
}
