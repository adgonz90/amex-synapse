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
     * Converts a Spring Data page, keeping its total. A next page follows when the elements before and on this page
     * are fewer than the total: {@code offset + elements < total}. Spring Data's {@code Page.hasNext()} compares page
     * numbers instead, which is right only for an offset that is a multiple of the size: at offset 3 of size 2 it says
     * a page follows the last two of 5 elements. For an aligned offset both rules agree, so this one is used for every
     * paged page; an unpaged page has no next.
     *
     * @param <T> the item type
     * @param page the page
     * @return the result
     */
    public static <T> PageResult<T> from(Page<T> page) {
        Pageable pageable = page.getPageable();
        boolean hasNext = pageable.isPaged()
                && pageable.getOffset() + page.getNumberOfElements() < page.getTotalElements();
        return PageResult.of(page.getContent(), hasNext).withTotal(page.getTotalElements());
    }

    /**
     * Converts a Spring Data slice, which has no total. Its {@code hasNext()} is whatever the query found, not a page
     * number: Spring Data JPA fetches one element more than the size from the exact offset, so it holds at any offset.
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
     * Each {@link SortOrder#property()} is passed to Spring Data unchanged, as an entity property. For keys declared
     * with {@code .sortable(SortKey.class)} that is the enum constant's name, so name the constants after the
     * entity's properties, or map the keys to them before calling this method.
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
