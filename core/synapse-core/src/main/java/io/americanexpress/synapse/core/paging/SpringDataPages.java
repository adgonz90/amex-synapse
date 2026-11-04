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

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;

/**
 * Converts between Synapse paging types and Spring Data's. This is the only class in core that references Spring
 * Data, which is an optional dependency.
 */
public final class SpringDataPages {

    private SpringDataPages() {
    }

    /**
     * Converts a Spring Data page, keeping its total.
     *
     * @param <T> the item type
     * @param page the page
     * @return the result
     */
    public static <T> PageResult<T> from(Page<T> page) {
        return PageResult.of(page.getContent(), page.hasNext()).withTotal(page.getTotalElements());
    }

    /**
     * Converts a Spring Data slice, which has no total.
     *
     * @param <T> the item type
     * @param slice the slice
     * @return the result
     */
    public static <T> PageResult<T> from(Slice<T> slice) {
        return PageResult.of(slice.getContent(), slice.hasNext());
    }

    /**
     * Returns an offset-based {@link Pageable} for the query. The offset is exact, not rounded to a page boundary.
     *
     * @param query the query
     * @return the pageable
     * @throws IllegalArgumentException if the query uses a cursor
     */
    public static Pageable pageable(PageQuery query) {
        if (query.cursor() != null) {
            throw new IllegalArgumentException("A cursor query cannot be converted to an offset-based Pageable");
        }
        Long offset = query.offset();
        List<Sort.Order> orders = query
                .sort()
                .stream()
                .map(order -> order.direction() == SortOrder.Direction.DESC
                        ? Sort.Order.desc(order.property())
                        : Sort.Order.asc(order.property()))
                .toList();
        return new OffsetPageable(offset == null ? 0 : offset, query.size(), Sort.by(orders));
    }
}
