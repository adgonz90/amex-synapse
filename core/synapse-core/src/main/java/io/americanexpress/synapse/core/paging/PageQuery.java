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
package io.americanexpress.synapse.core.paging;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.core.error.Violation;

/**
 * A request for one page of results, by cursor or by offset. The edge fills in the default size; this type has no
 * default.
 *
 * @param cursor the opaque cursor to continue from, or {@code null}; not combinable with {@code offset}
 * @param offset the number of items to skip, or {@code null}
 * @param size the page size
 * @param sort the sort criteria; {@code null} becomes empty, and the list is an immutable copy
 */
public record PageQuery(@Nullable String cursor, @Nullable Long offset, int size, List<SortOrder> sort) {

    /**
     * Creates a page query.
     */
    public PageQuery {
        sort = sort == null ? List.of() : List.copyOf(sort);
    }

    /**
     * Returns a query for the first page.
     *
     * @param size the page size
     * @return the query
     */
    public static PageQuery first(int size) {
        return new PageQuery(null, null, size, List.of());
    }

    /**
     * Returns an offset-based query.
     *
     * @param offset the number of items to skip
     * @param size the page size
     * @param sort the sort criteria
     * @return the query
     */
    public static PageQuery ofOffset(long offset, int size, List<SortOrder> sort) {
        return new PageQuery(null, offset, size, sort);
    }

    /**
     * Returns a cursor-based query.
     *
     * @param cursor the opaque cursor to continue from
     * @param size the page size
     * @param sort the sort criteria
     * @return the query
     */
    public static PageQuery ofCursor(String cursor, int size, List<SortOrder> sort) {
        return new PageQuery(cursor, null, size, sort);
    }

    /**
     * Checks the paging rules.
     *
     * @param maxSize the largest page size allowed
     * @return the violations in field order (cursor, offset, size); empty when the query is valid
     */
    public List<Violation> violations(int maxSize) {
        List<Violation> violations = new ArrayList<>();
        if (cursor != null && offset != null) {
            violations.add(new Violation("cursor", "must not be combined with offset"));
        }
        if (offset != null && offset < 0) {
            violations.add(new Violation("offset", "must be greater than or equal to 0"));
        }
        if (size < 1) {
            violations.add(new Violation("size", "must be greater than or equal to 1"));
        } else if (size > maxSize) {
            violations.add(new Violation("size", "must be less than or equal to " + maxSize));
        }
        return List.copyOf(violations);
    }
}
