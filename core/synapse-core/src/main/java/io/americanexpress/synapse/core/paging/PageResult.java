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

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.core.masking.MaskType;
import io.americanexpress.synapse.core.masking.Masked;

/**
 * One page of results.
 *
 * @param <T> the item type
 * @param items the items on this page; an immutable copy
 * @param nextCursor the cursor of the next page, or {@code null}. Masked in payload logs ({@link Masked})
 * @param hasNext whether another page follows
 * @param total the total number of items across all pages, or {@code null} when not counted
 */
public record PageResult<T>(List<T> items, @Masked(MaskType.FIXED_LENGTH) @Nullable String nextCursor, boolean hasNext,
        @Nullable Long total) {

    /**
     * Creates a page result.
     */
    public PageResult {
        items = List.copyOf(items);
    }

    /**
     * Returns a result without a cursor or total.
     *
     * @param <T> the item type
     * @param items the items
     * @param hasNext whether another page follows
     * @return the result
     */
    public static <T> PageResult<T> of(List<T> items, boolean hasNext) {
        return new PageResult<>(items, null, hasNext, null);
    }

    /**
     * Returns a cursor-based result; it has a next page exactly when the cursor is present.
     *
     * @param <T> the item type
     * @param items the items
     * @param nextCursor the cursor of the next page, or {@code null} on the last page
     * @return the result
     */
    public static <T> PageResult<T> ofCursor(List<T> items, @Nullable String nextCursor) {
        return new PageResult<>(items, nextCursor, nextCursor != null, null);
    }

    /**
     * Returns a copy with the total set.
     *
     * @param total the total number of items
     * @return the copy
     */
    public PageResult<T> withTotal(long total) {
        return new PageResult<>(items, nextCursor, hasNext, total);
    }
}
