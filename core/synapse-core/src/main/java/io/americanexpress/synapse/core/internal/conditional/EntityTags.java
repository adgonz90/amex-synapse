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
package io.americanexpress.synapse.core.internal.conditional;

import java.util.ArrayList;
import java.util.List;

/**
 * The entity tag grammar every protocol shares: a strict parser of entity tag lists, as {@code If-Match} and
 * {@code If-None-Match} carry them over REST and the {@code etag} field carries them over gRPC (RFC 9110 §8.8.3 and
 * §13.1), and the strong tag a response carries. A list is {@code "*"} alone, or a comma-separated list of
 * {@code entity-tag = [ "W/" ] DQUOTE *etagc DQUOTE}, with {@code etagc = %x21 / %x23-7E / %x80-FF} and optional
 * whitespace (space or tab) around each member.
 *
 * <p>
 * Anything outside the grammar is an error: an unquoted or unterminated tag, text after a tag, an empty list member, a
 * lower-case {@code w/}, or {@code *} next to a tag. (Spring's lenient {@code ETag.parse} reads {@code "5"x} as
 * {@code "5"} and drops {@code 5} without a word, so no protocol uses it.) One parser serves every protocol, so a
 * token taken from a REST {@code ETag} header reads the same over gRPC, and the reverse.
 */
public final class EntityTags {

    private static final String WEAK_PREFIX = "W/";

    private EntityTags() {
    }

    /**
     * A parsed entity tag list: {@code *}, or the tags.
     */
    public sealed interface Parsed permits Parsed.Any, Parsed.Tags {

        /** {@code *}, which matches any current representation. */
        record Any() implements Parsed {
        }

        /**
         * The entity tags of a list.
         *
         * @param tags the tags in list order; never empty
         */
        record Tags(List<Tag> tags) implements Parsed {

            /**
             * Creates the tags.
             *
             * @param tags the tags; copied
             * @throws IllegalArgumentException when there is none
             */
            public Tags {
                if (tags.isEmpty()) {
                    throw new IllegalArgumentException("An entity tag list has at least one tag");
                }
                tags = List.copyOf(tags);
            }
        }
    }

    /**
     * One entity tag.
     *
     * @param opaque the value between the double quotes, possibly empty
     * @param weak whether the tag has the {@code W/} prefix
     */
    public record Tag(String opaque, boolean weak) {
    }

    /**
     * Parses one field value: {@code *} alone, or an entity tag list.
     *
     * @param value the field value
     * @return the wildcard or the tags
     * @throws IllegalArgumentException when the value is empty or doesn't follow the grammar; the message never repeats
     *         the value
     */
    public static Parsed parse(String value) {
        if ("*".equals(trim(value))) {
            return new Parsed.Any();
        }
        List<Tag> tags = new ArrayList<>();
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
            tags.add(new Tag(value.substring(start, end), weak));
            position = skipWhitespace(value, end + 1);
            if (position == value.length()) {
                return new Parsed.Tags(tags);
            }
            if (value.charAt(position) != ',') {
                throw malformed();
            }
            position++;
        }
    }

    /**
     * Parses the field lines of one header. Several lines are one list, as if joined by commas (RFC 9110 §5.3).
     *
     * @param fieldValues the field values, one per header line
     * @return the wildcard or the tags
     * @throws IllegalArgumentException when there is no line, or the joined value is empty or doesn't follow the
     *         grammar; the message never repeats the value
     */
    public static Parsed parse(List<String> fieldValues) {
        return parse(String.join(",", fieldValues));
    }

    /**
     * Returns the strong entity tag of an opaque value as it is sent: the value in double quotes.
     *
     * @param opaque the value
     * @return {@code "\"" + opaque + "\""}
     * @throws IllegalArgumentException when the value is empty or has a character outside {@code etagc} (a double
     *         quote, a space, a control character, DEL or a character above {@code U+00FF}); the message never
     *         repeats the value
     */
    public static String strong(String opaque) {
        if (opaque.isEmpty()) {
            throw new IllegalArgumentException("A strong entity tag needs a value");
        }
        for (int i = 0; i < opaque.length(); i++) {
            if (!isEtagc(opaque.charAt(i))) {
                throw new IllegalArgumentException("The value has a character an entity tag can't carry (a double "
                        + "quote, a space, a control character, DEL or a character above U+00FF)");
            }
        }
        return "\"" + opaque + "\"";
    }

    /**
     * Tells whether the character may appear inside the quotes of an entity tag: RFC 9110 {@code etagc},
     * {@code %x21 / %x23-7E / %x80-FF}.
     *
     * @param c the character
     * @return whether it is an {@code etagc}
     */
    public static boolean isEtagc(char c) {
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
        return new IllegalArgumentException("The value is not an entity tag list (RFC 9110 §8.8.3)");
    }
}
