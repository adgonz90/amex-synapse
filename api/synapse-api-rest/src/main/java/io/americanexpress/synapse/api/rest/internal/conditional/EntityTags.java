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
package io.americanexpress.synapse.api.rest.internal.conditional;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.ETag;

/**
 * A strict parser of the entity tag lists in {@code If-Match} and {@code If-None-Match} (RFC 9110 §8.8.3 and §13.1):
 * {@code "*"} alone, or a comma-separated list of {@code entity-tag = [ "W/" ] DQUOTE *etagc DQUOTE}, with
 * {@code etagc = %x21 / %x23-7E / %x80-FF} and optional whitespace (space or tab) around each member.
 *
 * <p>
 * Spring's {@link ETag#parse} is not used: it is lenient, reading {@code "5"x} as {@code "5"} and dropping {@code 5}
 * without a word. Here anything outside the grammar is an error: an unquoted or unterminated tag, text after a tag, an
 * empty list member, a lower-case {@code w/}, or {@code *} next to a tag.
 */
public final class EntityTags {

    private static final String WEAK_PREFIX = "W/";

    private EntityTags() {
    }

    /**
     * A parsed header.
     *
     * @param wildcard whether the header is {@code *}, which matches any current representation
     * @param tags the entity tags in header order; empty for the wildcard
     */
    public record Parsed(boolean wildcard, List<ETag> tags) {

        /**
         * Creates a parsed header.
         *
         * @param wildcard whether the header is {@code *}
         * @param tags the entity tags; copied
         */
        public Parsed {
            tags = List.copyOf(tags);
        }
    }

    /**
     * Parses the field lines of one header. Several lines are one list, as if joined by commas (RFC 9110 §5.3).
     *
     * @param fieldValues the field values, one per header line
     * @return the wildcard or the tags
     * @throws IllegalArgumentException when the header is empty or doesn't follow the grammar; the message never
     *         repeats the header value
     */
    public static Parsed parse(List<String> fieldValues) {
        String value = String.join(",", fieldValues);
        if ("*".equals(trim(value))) {
            return new Parsed(true, List.of());
        }
        List<ETag> tags = new ArrayList<>();
        int position = 0;
        while (true) {
            position = skipWhitespace(value, position);
            boolean weak = value.startsWith(WEAK_PREFIX, position);
            if (weak) {
                position += WEAK_PREFIX.length();
            }
            if (position >= value.length() || value.charAt(position) != '"') {
                throw malformed();
            }
            int start = position + 1;
            int end = start;
            while (end < value.length() && isEtagc(value.charAt(end))) {
                end++;
            }
            if (end >= value.length() || value.charAt(end) != '"') {
                throw malformed();
            }
            tags.add(new ETag(value.substring(start, end), weak));
            position = skipWhitespace(value, end + 1);
            if (position == value.length()) {
                return new Parsed(false, tags);
            }
            if (value.charAt(position) != ',') {
                throw malformed();
            }
            position++;
        }
    }

    /** Whether the character may appear inside the quotes of an entity tag: {@code %x21 / %x23-7E / %x80-FF}. */
    static boolean isEtagc(char c) {
        return c == 0x21 || (c >= 0x23 && c <= 0x7e) || (c >= 0x80 && c <= 0xff);
    }

    private static String trim(String value) {
        int start = skipWhitespace(value, 0);
        int end = value.length();
        while (end > start && isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static int skipWhitespace(String value, int position) {
        int index = position;
        while (index < value.length() && isWhitespace(value.charAt(index))) {
            index++;
        }
        return index;
    }

    /** RFC 9110 {@code OWS}: space or horizontal tab. */
    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t';
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("The header is not an entity tag list (RFC 9110 §8.8.3)");
    }
}
