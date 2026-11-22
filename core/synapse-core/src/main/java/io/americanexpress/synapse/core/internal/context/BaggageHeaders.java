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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Reads and writes the W3C Baggage header: list members separated by {@code ,}, each {@code key=value} with optional
 * {@code ;properties}, surrounding whitespace trimmed and values percent-encoded as UTF-8. Malformed members are
 * skipped rather than failing the request. The parser is lenient: keys and properties are not validated against the
 * token grammar, only trimmed with {@link String#trim()}; duplicate keys are kept in order.
 */
public final class BaggageHeaders {

    /** The name of the baggage header. */
    public static final String HEADER = "baggage";

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private BaggageHeaders() {
    }

    /**
     * One baggage list member.
     *
     * @param key the member key
     * @param value the percent-decoded value
     * @param properties the raw {@code ;...} suffix, or an empty string
     */
    public record Entry(String key, String value, String properties) {
    }

    /**
     * Parses a baggage header, skipping malformed list members.
     *
     * @param header the header value, if any
     * @return the members in order
     */
    public static List<Entry> parse(@Nullable String header) {
        List<Entry> entries = new ArrayList<>();
        if (header == null) {
            return entries;
        }
        for (String member : header.split(",")) {
            Entry entry = parseMember(member);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /**
     * Formats members as a header value, percent-encoding the values.
     *
     * @param entries the members
     * @return the header value
     */
    public static String format(List<Entry> entries) {
        StringBuilder out = new StringBuilder();
        for (Entry entry : entries) {
            if (!out.isEmpty()) {
                out.append(',');
            }
            out.append(entry.key()).append('=');
            encode(entry.value(), out);
            out.append(entry.properties());
        }
        return out.toString();
    }

    /**
     * Keeps only the members whose key is listed.
     *
     * @param header the header value, if any
     * @param keys the keys to keep
     * @return the filtered header value, or {@code null} when nothing is left
     */
    public static @Nullable String retain(@Nullable String header, Set<String> keys) {
        return filter(header, keys, true);
    }

    /**
     * Drops the members whose key is listed.
     *
     * @param header the header value, if any
     * @param keys the keys to drop
     * @return the filtered header value, or {@code null} when nothing is left
     */
    public static @Nullable String remove(@Nullable String header, Set<String> keys) {
        return filter(header, keys, false);
    }

    private static @Nullable String filter(@Nullable String header, Set<String> keys, boolean keepListed) {
        List<Entry> kept = new ArrayList<>();
        for (Entry entry : parse(header)) {
            if (keys.contains(entry.key()) == keepListed) {
                kept.add(entry);
            }
        }
        return kept.isEmpty() ? null : format(kept);
    }

    private static @Nullable Entry parseMember(String member) {
        int semicolon = member.indexOf(';');
        String pair = (semicolon < 0 ? member : member.substring(0, semicolon)).trim();
        String properties = semicolon < 0 ? "" : member.substring(semicolon).trim();
        int equals = pair.indexOf('=');
        if (equals < 0) {
            return null;
        }
        String key = pair.substring(0, equals).trim();
        if (key.isEmpty()) {
            return null;
        }
        String raw = pair.substring(equals + 1).trim();
        try {
            return new Entry(key, URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8), properties);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void encode(String value, StringBuilder out) {
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '.'
                    || c == '_' || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append(HEX[c >> 4]).append(HEX[c & 0xF]);
            }
        }
    }
}
