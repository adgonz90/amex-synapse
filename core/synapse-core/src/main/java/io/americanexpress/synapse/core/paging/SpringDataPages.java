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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
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
     * entity's properties, or map the keys to them with {@link #pageable(PageQuery, Function, SortOrder...)}, which
     * also appends tie-breakers.
     *
     * @param query the query
     * @return the pageable
     * @throws IllegalArgumentException if the query uses a cursor
     */
    public static Pageable pageable(PageQuery query) {
        return pageable(query, Function.identity());
    }

    /**
     * As {@link #pageable(PageQuery)}, with each key mapped to an entity property by {@code property}, then each
     * {@code then} order appended unless its property is already sorted on. The tie-breakers give every item one
     * place, so pages neither skip nor repeat an item while the data doesn't change:
     *
     * <pre>{@code
     * SpringDataPages.pageable(query, key -> property(SortKey.valueOf(key)),
     *         new SortOrder("title", SortOrder.Direction.ASC), new SortOrder("id", SortOrder.Direction.ASC));
     * }</pre>
     *
     * A {@code then} property is compared with the mapped properties, so a caller who sorts on the title keeps their
     * direction, and the title tie-breaker is skipped. The {@code then} properties are entity properties already, and
     * are not mapped.
     *
     * @param query the query
     * @param property maps a sort key, such as an enum constant's name, to an entity property; null or blank for a key it
     *        can't map
     * @param then the tie-breakers, in order
     * @return the pageable
     * @throws IllegalArgumentException if the query uses a cursor, or {@code property} returns null or blank for a key
     *         (the message names the key)
     */
    public static Pageable pageable(PageQuery query, Function<String, ? extends @Nullable String> property,
            SortOrder... then) {
        if (query.cursor() != null) {
            throw new IllegalArgumentException("A cursor query cannot be converted to an offset-based Pageable");
        }
        Long offset = query.offset();
        List<Sort.Order> orders = new ArrayList<>();
        Set<String> sorted = new HashSet<>();
        for (SortOrder key : query.sort()) {
            @Nullable
            String mapped = property.apply(key.property());
            if (mapped == null || mapped.isBlank()) {
                throw new IllegalArgumentException("The sort key '" + key.property() + "' maps to no entity property");
            }
            orders.add(order(mapped, key.direction()));
            sorted.add(mapped);
        }
        for (SortOrder tieBreaker : then) {
            if (sorted.add(tieBreaker.property())) {
                orders.add(order(tieBreaker.property(), tieBreaker.direction()));
            }
        }
        return new OffsetPageable(offset == null ? 0 : offset, query.size(), Sort.by(orders));
    }

    private static Sort.Order order(String property, SortOrder.Direction direction) {
        return direction == SortOrder.Direction.DESC ? Sort.Order.desc(property) : Sort.Order.asc(property);
    }
}
