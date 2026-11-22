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

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The Synapse baggage fields a trusted caller sent, which {@link InboundPropagationFilter} removed from the headers
 * before tracing could read them. They are only candidates: the context filter validates them before they reach the
 * request context, tracing, logs or downstream calls.
 */
public final class InboundBaggage {

    /** Request attribute holding the candidates, an unmodifiable {@code Map<String, String>}. */
    public static final String ATTRIBUTE = InboundBaggage.class.getName() + ".candidates";

    private InboundBaggage() {
    }

    /**
     * Returns the Synapse baggage fields the caller sent, keyed by field name.
     *
     * @param request the request
     * @return the values removed from the headers when inbound context is trusted; empty when it is not, or when
     *     {@link InboundPropagationFilter} has not run
     */
    public static Map<String, String> candidates(HttpServletRequest request) {
        Object candidates = request.getAttribute(ATTRIBUTE);
        if (!(candidates instanceof Map<?, ?> map)) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, String> typed = (Map<String, String>) map;
        return typed;
    }
}
