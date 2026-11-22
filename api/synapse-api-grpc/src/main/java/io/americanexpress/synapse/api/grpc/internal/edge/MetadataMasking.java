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
package io.americanexpress.synapse.api.grpc.internal.edge;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import io.grpc.Metadata;
import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.core.internal.context.BaggageHeaders;
import io.americanexpress.synapse.core.internal.context.TraceHeaders;

/**
 * Hides or strips inbound propagation metadata before tracing reads it, as the REST edge does with headers.
 * <ul>
 * <li>When inbound context is not trusted, it hides the trace keys ({@link #TRACE_KEYS}), the {@code baggage} key and
 * the key of every remote baggage field and Synapse field, so the call starts a new trace with no caller baggage. It
 * finds no candidates.</li>
 * <li>When it is trusted, it removes the Synapse fields (the correlation id and the context attribute names) from the
 * {@code baggage} values and hides their keys. Their values become candidates for the context interceptor to validate:
 * a field's own key (its first value, when it is repeated) wins over its {@code baggage} entry. Everything else passes
 * through.</li>
 * </ul>
 * Keys and baggage entries match the field names case-insensitively: gRPC metadata keys are lower case, while field
 * names such as {@code correlationId} are mixed case. The inbound metadata is never changed; the result is a copy.
 */
public final class MetadataMasking {

    /** The trace keys: W3C Trace Context and B3, core's trace headers in lower case. */
    public static final Set<String> TRACE_KEYS = TraceHeaders.NAMES
            .stream()
            .map(name -> name.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());

    private static final Metadata.Key<String> BAGGAGE = Metadata.Key
            .of(BaggageHeaders.HEADER, Metadata.ASCII_STRING_MARSHALLER);

    private final boolean trusted;

    private final Set<String> synapseFields;

    private final Set<String> synapseFieldMatcher;

    private final Set<String> untrustedHidden;

    /**
     * Creates the masking.
     *
     * @param trusted whether inbound context is trusted ({@code synapse.context.inbound.trusted})
     * @param synapseFields the Synapse baggage fields: the correlation id field and the context attribute names; their
     *     candidates are read in this order
     * @param remoteFields the remote baggage fields ({@code management.tracing.baggage.remote-fields}), each read from
     *     the metadata key of the same name
     */
    public MetadataMasking(boolean trusted, Set<String> synapseFields, Set<String> remoteFields) {
        this.trusted = trusted;
        this.synapseFields = Collections.unmodifiableSet(new LinkedHashSet<>(synapseFields));
        this.synapseFieldMatcher = Collections.unmodifiableSet(caseInsensitive(synapseFields));
        Set<String> hidden = caseInsensitive(TRACE_KEYS);
        hidden.add(BaggageHeaders.HEADER);
        hidden.addAll(remoteFields);
        // Synapse fields are remote fields once core has merged them; hide their keys even if that did not run.
        hidden.addAll(synapseFields);
        this.untrustedHidden = Collections.unmodifiableSet(hidden);
    }

    /**
     * The metadata to pass on, and the Synapse field values the caller sent.
     *
     * @param metadata a masked copy of the inbound metadata
     * @param candidates the Synapse field values by field name, in field order, not validated; empty when inbound
     *     context is not trusted
     */
    public record Result(Metadata metadata, Map<String, String> candidates) {

        /**
         * Creates the result.
         *
         * @param metadata a masked copy of the inbound metadata
         * @param candidates the Synapse field values by field name
         */
        public Result {
            candidates = Collections.unmodifiableMap(new LinkedHashMap<>(candidates));
        }
    }

    /**
     * Masks the inbound metadata of a call.
     *
     * @param inbound the metadata the caller sent; not changed
     * @return the masked copy and the candidates
     */
    public Result apply(Metadata inbound) {
        Metadata copy = new Metadata();
        copy.merge(inbound);
        if (!trusted) {
            hide(copy, inbound, untrustedHidden);
            return new Result(copy, Map.of());
        }
        String baggage = joinedBaggage(inbound);
        List<BaggageHeaders.Entry> entries = BaggageHeaders.parse(baggage);
        Map<String, String> candidates = new LinkedHashMap<>();
        for (String field : synapseFields) {
            String value = keyValue(inbound, field);
            if (value == null) {
                value = baggageValue(entries, field);
            }
            if (value != null) {
                candidates.put(field, value);
            }
        }
        hide(copy, inbound, synapseFieldMatcher);
        if (baggage != null) {
            String replacement = BaggageHeaders.remove(baggage, synapseFieldMatcher);
            copy.discardAll(BAGGAGE);
            if (replacement != null) {
                copy.put(BAGGAGE, replacement);
            }
        }
        return new Result(copy, candidates);
    }

    /**
     * Discards from the copy every inbound key in the hidden set. A key that is not a valid metadata key name (HTTP/2
     * allows more characters) is left as it is: no {@link Metadata.Key} can name it, so nothing downstream can read it
     * either.
     */
    private static void hide(Metadata copy, Metadata inbound, Set<String> hidden) {
        for (String name : inbound.keys()) {
            if (hidden.contains(name)) {
                Metadata.Key<?> key = keyOrNull(name);
                if (key != null) {
                    copy.discardAll(key);
                }
            }
        }
    }

    /**
     * Returns the first value of an ASCII key, as the REST edge reads the first header line. grpc-java's
     * {@link Metadata#get} returns the last one.
     *
     * @param metadata the metadata
     * @param key the key
     * @return the first value, or {@code null} when the key is absent
     */
    public static @Nullable String firstValue(Metadata metadata, Metadata.Key<String> key) {
        Iterable<String> values = metadata.getAll(key);
        if (values == null) {
            return null;
        }
        Iterator<String> iterator = values.iterator();
        return iterator.hasNext() ? iterator.next() : null;
    }

    /** Joins every {@code baggage} value, as W3C Baggage allows, so no value slips past the stripping. */
    private static @Nullable String joinedBaggage(Metadata inbound) {
        Iterable<String> values = inbound.getAll(BAGGAGE);
        if (values == null) {
            return null;
        }
        String joined = String.join(",", values);
        return joined.isEmpty() ? null : joined;
    }

    /** The first value of the key named like the field, ignoring case; keys no Metadata.Key can name are skipped. */
    private static @Nullable String keyValue(Metadata inbound, String field) {
        for (String name : inbound.keys()) {
            if (name.equalsIgnoreCase(field) && !name.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
                try {
                    return firstValue(inbound, Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER));
                } catch (IllegalArgumentException invalidName) {
                    // Not a valid metadata key name: nothing downstream can read it either.
                }
            }
        }
        return null;
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

    /**
     * A key for a name read from metadata, with the marshaller its suffix requires; {@code null} when the name is not
     * a valid metadata key name.
     */
    private static Metadata.@Nullable Key<?> keyOrNull(String name) {
        try {
            return name.endsWith(Metadata.BINARY_HEADER_SUFFIX)
                    ? Metadata.Key.of(name, Metadata.BINARY_BYTE_MARSHALLER)
                    : Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER);
        } catch (IllegalArgumentException invalidName) {
            return null;
        }
    }

    private static Set<String> caseInsensitive(Collection<String> names) {
        Set<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        set.addAll(names);
        return set;
    }
}
