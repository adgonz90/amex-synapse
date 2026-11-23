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
package io.americanexpress.synapse.api.rest.internal.paging;

import java.util.List;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.util.MultiValueMap;

import io.americanexpress.synapse.api.rest.internal.binding.RequestBinder;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.core.internal.paging.PagingViolations;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.SortOrder;

/**
 * Reads a {@link PageQuery} from the paging parameters of a parsed query string, under the configured parameter names,
 * and applies the edge's caps.
 *
 * <ul>
 * <li>{@code cursor}, {@code offset} and {@code size} take a single value; a repeated or empty one is a violation.
 * {@code sort} may repeat, and its values are joined in order.</li>
 * <li>Integers are ASCII decimal digits with an optional sign ({@code [+-]?[0-9]+}); other digits, and a number that
 * doesn't fit a {@code long}, are not a valid value. A sign is range-checked like any value. The offset cap is
 * inclusive.</li>
 * <li>{@code sort} is a comma-separated list of wire names from the route's {@link SortKeys}, each optionally led by
 * {@code -} for descending. Nothing is trimmed, and keys are case-sensitive.</li>
 * </ul>
 *
 * Violations name the parameter as the caller sent it (the configured name), never repeat the caller's value, and come
 * in the order cursor, offset, size, sort. The cursor is returned as the caller's token.
 */
public final class PageQueryReader {

    private static final String INVALID = PagingViolations.INVALID;

    private static final String SINGLE = RequestBinder.SINGLE_VALUE;

    /** An integer as callers may send it: ASCII digits with an optional sign. */
    private static final Pattern ASCII_INTEGER = Pattern.compile("[+-]?[0-9]+");

    private final PagingSettings settings;

    /**
     * Creates the reader.
     *
     * @param settings the parameter names, the offset cap and the page sizes
     */
    public PageQueryReader(PagingSettings settings) {
        this.settings = settings;
    }

    /**
     * Reads the paging parameters.
     *
     * @param query the parsed query string
     * @param sortKeys the route's sort allow-list, or {@code null} when the route takes no sort parameter
     * @param violations where the problems found are appended
     * @return the query, with the default size when the caller sent none; its values are meaningless when a violation
     *         was appended
     */
    public PageQuery read(MultiValueMap<String, String> query, @Nullable SortKeys sortKeys,
            List<Violation> violations) {
        String cursor = single(query, settings.cursor(), violations);
        if (cursor != null && query.containsKey(settings.offset())) {
            violations.add(new Violation(settings.cursor(), PagingViolations.notCombinedWith(settings.offset())));
        }
        Long offset = number(query, settings.offset(), 0, settings.maxOffset(), violations);
        Long size = number(query, settings.size(), 1, settings.maxSize(), violations);
        List<SortOrder> sort = sort(query.get(settings.sort()), sortKeys, violations);
        // A size outside 1..maxSize is a violation, so the narrowing never wraps.
        return new PageQuery(cursor, offset, size == null ? settings.defaultSize() : size.intValue(), sort);
    }

    /**
     * Returns the parameter's one non-empty value, or {@code null} when it is absent or has a violation.
     */
    private static @Nullable String single(MultiValueMap<String, String> query, String name,
            List<Violation> violations) {
        List<String> values = query.get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() > 1) {
            violations.add(new Violation(name, SINGLE));
            return null;
        }
        String value = values.getFirst();
        if (value.isEmpty()) {
            violations.add(new Violation(name, INVALID));
            return null;
        }
        return value;
    }

    /**
     * Returns the parameter as a number within {@code min..max}, or {@code null} when it is absent or has a violation.
     */
    private static @Nullable Long number(MultiValueMap<String, String> query, String name, long min, long max,
            List<Violation> violations) {
        String text = single(query, name, violations);
        if (text == null) {
            return null;
        }
        if (!ASCII_INTEGER.matcher(text).matches()) {
            // Long.parseLong would also accept non-ASCII digits.
            violations.add(new Violation(name, INVALID));
            return null;
        }
        long value;
        try {
            value = Long.parseLong(text);
        } catch (NumberFormatException e) {
            // Beyond a long: not a value the caller may send.
            violations.add(new Violation(name, INVALID));
            return null;
        }
        if (value < min) {
            violations.add(new Violation(name, PagingViolations.atLeast(min)));
            return null;
        }
        if (value > max) {
            violations.add(new Violation(name, PagingViolations.atMost(max)));
            return null;
        }
        return value;
    }

    /**
     * Parses the sort parameter. Each distinct problem is reported once, in the order first found.
     */
    private List<SortOrder> sort(@Nullable List<String> values, @Nullable SortKeys sortKeys,
            List<Violation> violations) {
        String name = settings.sort();
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (sortKeys == null) {
            violations.add(new Violation(name, PagingViolations.NOT_SUPPORTED));
            return List.of();
        }
        PagingViolations.SortReading reading = new PagingViolations.SortReading(sortKeys.wireToProperty());
        for (String element : String.join(",", values).split(",", -1)) {
            boolean descending = element.startsWith("-");
            String key = descending ? element.substring(1) : element;
            if (key.isEmpty()) {
                reading.invalid();
            } else {
                reading.key(key, descending);
            }
        }
        reading.problems().forEach(problem -> violations.add(new Violation(name, problem)));
        return reading.sort();
    }
}
