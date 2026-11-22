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
package io.americanexpress.synapse.api.grpc.internal.mapping;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;
import com.google.protobuf.Message;
import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.api.grpc.internal.binding.SortKeys;
import io.americanexpress.synapse.core.internal.paging.PagingViolations;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.SortOrder;

/**
 * Reads a {@code PageQuery} from a request's AIP-158 page fields, and applies core's caps as the REST edge applies them
 * to its paging parameters:
 * <ul>
 * <li>{@code int32 page_size}: unset is {@code synapse.paging.default-size}; 1 to {@code synapse.paging.max-size}
 * otherwise;</li>
 * <li>{@code string page_token}: unset is the first page; otherwise the caller's token, which the edge unwraps;</li>
 * <li>{@code int32 skip}, optional: unset is no offset; 0 to {@code synapse.paging.max-offset} otherwise;</li>
 * <li>{@code string order_by}, needed with {@code .sortable}: AIP-132, keys separated by commas, each followed by
 * {@code desc} to sort descending (or {@code asc}); U+0020 spaces around keys and words are insignificant.</li>
 * </ul>
 * A field with presence (proto3 {@code optional}) is unset when the request doesn't send it, and a sent value follows
 * REST's rules for its parameter: {@code page_size: 0} is {@code must be greater than or equal to 1}, {@code skip: 0}
 * is offset 0 (and can't be combined with a token), and an empty {@code page_token} or {@code order_by} is a
 * violation. A plain field can't tell its default from an unset field, so its default (0 or empty) is unset.
 * Violations name the field, never repeat the caller's value, and come in the order {@code page_token},
 * {@code skip}, {@code page_size}, {@code order_by}, with REST's messages.
 */
final class PageFields {

    static final String PAGE_SIZE = "page_size";

    static final String PAGE_TOKEN = "page_token";

    static final String SKIP = "skip";

    static final String ORDER_BY = "order_by";

    /** The request fields only a {@code PageQuery} binds from. */
    static final Set<String> NAMES = Set.of(PAGE_SIZE, PAGE_TOKEN, SKIP, ORDER_BY);

    private static final String DESC = "desc";

    private static final String ASC = "asc";

    /** The violation of a sort on a method without {@code .sortable}, the same text as REST's. */
    static final String NOT_SUPPORTED = PagingViolations.NOT_SUPPORTED;

    private final FieldDescriptor pageSize;

    private final FieldDescriptor pageToken;

    private final @Nullable FieldDescriptor skip;

    private final @Nullable FieldDescriptor orderBy;

    private final PagingRules rules;

    private PageFields(FieldDescriptor pageSize, FieldDescriptor pageToken, @Nullable FieldDescriptor skip,
            @Nullable FieldDescriptor orderBy, PagingRules rules) {
        this.pageSize = pageSize;
        this.pageToken = pageToken;
        this.skip = skip;
        this.orderBy = orderBy;
        this.rules = rules;
    }

    /**
     * Finds the page fields of a request message and checks their shape.
     *
     * @param message the request message
     * @param rules the paging rules, with the method's sort keys
     * @param subject the start of a problem, up to the message's description ({@code Books.page is a PageQuery, but })
     * @return the fields
     * @throws MappingException when {@code page_size} or {@code page_token} is missing, {@code order_by} is missing
     *         on a method with {@code .sortable}, or a page field is not a singular field of its type outside a
     *         {@code oneof}
     */
    static PageFields of(Descriptor message, PagingRules rules, String subject) {
        String tail = ": AIP-158's page fields carry the PageQuery.";
        FieldDescriptor pageSize = field(message, PAGE_SIZE, FieldDescriptor.Type.INT32, subject, tail);
        FieldDescriptor pageToken = field(message, PAGE_TOKEN, FieldDescriptor.Type.STRING, subject, tail);
        FieldDescriptor skip = message.findFieldByName(SKIP) == null
                ? null
                : field(message, SKIP, FieldDescriptor.Type.INT32, subject, tail);
        FieldDescriptor orderBy;
        if (rules.sortKeys() != null) {
            orderBy = field(message, ORDER_BY, FieldDescriptor.Type.STRING,
                    ".sortable(...) sorts by the request's order_by, but ", ", or remove .sortable(...).");
        } else {
            orderBy = message.findFieldByName(ORDER_BY) == null
                    ? null
                    : field(message, ORDER_BY, FieldDescriptor.Type.STRING, subject, tail);
        }
        return new PageFields(pageSize, pageToken, skip, orderBy, rules);
    }

    /**
     * Returns the singular field of a type, outside a real {@code oneof} (a proto3 {@code optional} is fine).
     *
     * @throws MappingException when the message has no such field, or the field has another shape
     */
    static FieldDescriptor field(Descriptor message, String name, FieldDescriptor.Type type, String subject,
            String actionTail) {
        String messageName = message.getFullName();
        String typeName = type.name().toLowerCase(Locale.ROOT);
        FieldDescriptor field = message.findFieldByName(name);
        if (field == null) {
            throw new MappingException(subject + messageName + " has no " + typeName + " field " + name,
                    "Add " + typeName + " " + name + " to " + messageName + actionTail);
        }
        String fieldName = messageName + "." + name;
        if (field.isRepeated() || field.getType() != type) {
            throw new MappingException(
                    subject + fieldName + " is " + MessageMapping.typeName(field) + ", not " + typeName,
                    "Declare " + fieldName + " as " + typeName + actionTail);
        }
        OneofDescriptor oneof = field.getRealContainingOneof();
        if (oneof != null) {
            throw new MappingException(subject + fieldName + " is part of oneof " + oneof.getName(),
                    "Move " + fieldName + " out of oneof " + oneof.getName() + actionTail);
        }
        return field;
    }

    /**
     * Reads the page query. Each problem is recorded as a violation of its field.
     *
     * @param message the request
     * @param reading the violations of the request
     * @return the query, with the caller's token as its cursor, or {@link FieldConverter#FAILED}
     * @throws FieldConverter.Full when the violation limit is reached
     */
    Object read(Message message, FieldConverter.Reading reading) {
        boolean failed = false;
        String token = (String) message.getField(pageToken);
        // A field with presence was sent or not, as a REST parameter; a plain one is sent when it isn't its default.
        boolean tokenSent = pageToken.hasPresence() ? message.hasField(pageToken) : !token.isEmpty();
        String cursor = tokenSent && !token.isEmpty() ? token : null;
        if (tokenSent && token.isEmpty()) {
            reading.violation(PAGE_TOKEN, FieldConverter.Reading.INVALID);
            failed = true;
        }
        FieldDescriptor skipField = skip;
        int skipped = skipField == null ? 0 : (Integer) message.getField(skipField);
        boolean skipSent = skipField != null && (skipField.hasPresence() ? message.hasField(skipField) : skipped != 0);
        if (cursor != null && skipSent) {
            reading.violation(PAGE_TOKEN, PagingViolations.notCombinedWith(SKIP));
            failed = true;
        }
        Long offset = null;
        if (skipSent) {
            if (skipped < 0) {
                reading.violation(SKIP, PagingViolations.atLeast(0));
                failed = true;
            } else if (skipped > rules.maxOffset()) {
                reading.violation(SKIP, PagingViolations.atMost(rules.maxOffset()));
                failed = true;
            } else {
                offset = (long) skipped;
            }
        }
        int size = (Integer) message.getField(pageSize);
        boolean sizeSent = pageSize.hasPresence() ? message.hasField(pageSize) : size != 0;
        // A plain page_size can't send 0, its default, so its least value is 0; a sent one's is 1, as REST's size.
        int minSize = pageSize.hasPresence() ? 1 : 0;
        if (!sizeSent) {
            size = rules.defaultSize();
        } else if (size < minSize) {
            reading.violation(PAGE_SIZE, PagingViolations.atLeast(minSize));
            failed = true;
        } else if (size > rules.maxSize()) {
            reading.violation(PAGE_SIZE, PagingViolations.atMost(rules.maxSize()));
            failed = true;
        }
        FieldDescriptor orderField = orderBy;
        List<SortOrder> sort = List.of();
        if (orderField != null) {
            String value = (String) message.getField(orderField);
            boolean orderSent = orderField.hasPresence()
                    ? message.hasField(orderField)
                    : !trimSpaces(value).isEmpty();
            sort = orderSent ? sort(value, reading) : List.of();
        }
        if (failed || sort == null) {
            return FieldConverter.FAILED;
        }
        return new PageQuery(cursor, offset, size, sort);
    }

    /**
     * Parses a sent {@code order_by} (AIP-132). Each distinct problem is reported once, in the order first found. Only
     * U+0020 separates words, and {@code asc}/{@code desc} are lower case: an element with another whitespace
     * character, or another word, is not a valid value.
     *
     * @return the sort, or {@code null} when a violation was recorded
     */
    private @Nullable List<SortOrder> sort(String value, FieldConverter.Reading reading) {
        SortKeys keys = rules.sortKeys();
        if (keys == null) {
            reading.violation(ORDER_BY, NOT_SUPPORTED);
            return null;
        }
        PagingViolations.SortReading sort = new PagingViolations.SortReading(keys.wireToProperty());
        for (String element : value.split(",", -1)) {
            String trimmed = trimSpaces(element);
            String[] words = trimmed.isEmpty() ? new String[0] : trimmed.split(" +");
            boolean valid = (words.length == 1
                    || (words.length == 2 && (words[1].equals(DESC) || words[1].equals(ASC))))
                    && trimmed.chars().noneMatch(c -> c != ' ' && Character.isWhitespace(c));
            if (valid) {
                sort.key(words[0], words.length == 2 && words[1].equals(DESC));
            } else {
                sort.invalid();
            }
        }
        if (sort.problems().isEmpty()) {
            return sort.sort();
        }
        for (String problem : sort.problems()) {
            reading.violation(ORDER_BY, problem);
        }
        return null;
    }

    /** The value without the space characters (U+0020) around it; AIP-132 makes them insignificant. */
    private static String trimSpaces(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == ' ') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(start, end);
    }

    /**
     * Returns the proto field of each {@code PageQuery} component, for the violations the operation pipeline names
     * by component ({@code page.size} → {@code page_size}).
     *
     * @param prefix the component path of the {@code PageQuery} with a trailing dot, or empty for a
     *        {@code PageQuery} input
     * @return the field paths by component path
     */
    Map<String, String> fieldPaths(String prefix) {
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put(prefix + "cursor", PAGE_TOKEN);
        if (skip != null) {
            paths.put(prefix + "offset", SKIP);
        }
        paths.put(prefix + "size", PAGE_SIZE);
        if (orderBy != null) {
            paths.put(prefix + "sort", ORDER_BY);
        }
        return paths;
    }
}
