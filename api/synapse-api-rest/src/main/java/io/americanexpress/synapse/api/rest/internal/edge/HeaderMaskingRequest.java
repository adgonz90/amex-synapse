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
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.core.internal.context.BaggageHeaders;

/**
 * A request whose listed headers are hidden and whose {@code baggage} header may be replaced, matching header names
 * case-insensitively. Tracing reads propagation headers through {@link #getHeader(String)} and
 * {@link #getHeaders(String)}, so it sees this masked view. A hidden header is also absent for
 * {@link #getIntHeader(String)} and {@link #getDateHeader(String)}, which return {@code -1}.
 */
public final class HeaderMaskingRequest extends HttpServletRequestWrapper {

    private final Set<String> hidden;

    private final @Nullable String baggageReplacement;

    /**
     * Creates the masked view.
     *
     * @param request the request to wrap
     * @param hidden the names of the headers to hide, in any case
     * @param baggageReplacement the value seen for the {@code baggage} header instead of the caller's, or
     *     {@code null} to leave it alone (it is still hidden when listed in {@code hidden})
     */
    public HeaderMaskingRequest(HttpServletRequest request, Set<String> hidden, @Nullable String baggageReplacement) {
        super(request);
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(hidden);
        this.hidden = Collections.unmodifiableSet(names);
        this.baggageReplacement = baggageReplacement;
    }

    @Override
    public @Nullable String getHeader(String name) {
        if (replaces(name)) {
            return baggageReplacement;
        }
        return hidden.contains(name) ? null : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        String replacement = baggageReplacement;
        if (replacement != null && replaces(name)) {
            return Collections.enumeration(List.of(replacement));
        }
        return hidden.contains(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
    }

    @Override
    public int getIntHeader(String name) {
        return getHeader(name) == null ? -1 : super.getIntHeader(name);
    }

    @Override
    public long getDateHeader(String name) {
        return getHeader(name) == null ? -1L : super.getDateHeader(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        List<String> names = new ArrayList<>();
        for (String name : Collections.list(super.getHeaderNames())) {
            if (replaces(name) || !hidden.contains(name)) {
                names.add(name);
            }
        }
        return Collections.enumeration(names);
    }

    private boolean replaces(String name) {
        return baggageReplacement != null && BaggageHeaders.HEADER.equalsIgnoreCase(name);
    }
}
