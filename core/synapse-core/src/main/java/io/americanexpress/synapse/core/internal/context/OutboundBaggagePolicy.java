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
package io.americanexpress.synapse.core.internal.context;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Decides which baggage fields may leave the service on an outbound call: the correlation id and the declared
 * context attributes. Protocol-neutral; a client wraps it in its own interceptor, which strips the {@code baggage}
 * entries and the per-field headers that are not allowed.
 */
public final class OutboundBaggagePolicy {

    private final Set<String> keptFields;

    private OutboundBaggagePolicy(Set<String> keptFields) {
        this.keptFields = keptFields;
    }

    /**
     * Creates a policy that keeps the correlation id and the named attributes.
     *
     * @param attributeNames the context attribute names that may be sent
     * @return the policy
     */
    public static OutboundBaggagePolicy allowing(Collection<String> attributeNames) {
        Set<String> kept = new LinkedHashSet<>();
        kept.add(ContextBinder.CORRELATION_BAGGAGE_FIELD);
        kept.addAll(attributeNames);
        return new OutboundBaggagePolicy(Set.copyOf(kept));
    }

    /**
     * Returns the fields that may be sent.
     *
     * @return the kept field names
     */
    public Set<String> keptFields() {
        return keptFields;
    }

    /**
     * Removes the entries of fields that may not be sent from a baggage header.
     * <p>
     * Callers join repeated {@code baggage} header lines with a comma before filtering, and replace the original
     * header line(s) with the result, removing the header when the result is {@code null}.
     *
     * @param value the {@code baggage} header value, if any
     * @return the filtered value, or {@code null} when the header should be dropped
     */
    public @Nullable String filterBaggageHeader(@Nullable String value) {
        return BaggageHeaders.retain(value, keptFields);
    }

    /**
     * Returns the remote fields that may not be sent, to be removed as headers.
     *
     * @param remoteFields the configured remote baggage fields
     * @return the fields not kept, compared case-insensitively
     */
    public Set<String> headersToRemove(Collection<String> remoteFields) {
        Set<String> keptLower = new LinkedHashSet<>();
        for (String kept : keptFields) {
            keptLower.add(kept.toLowerCase(Locale.ROOT));
        }
        Set<String> remove = new LinkedHashSet<>();
        for (String field : remoteFields) {
            if (!keptLower.contains(field.toLowerCase(Locale.ROOT))) {
                remove.add(field);
            }
        }
        return remove;
    }
}
