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

import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * A {@link Pageable} positioned at an exact offset, which need not be a multiple of the page size.
 */
final class OffsetPageable implements Pageable {

    private final long offset;

    private final int size;

    private final Sort sort;

    OffsetPageable(long offset, int size, Sort sort) {
        this.offset = offset;
        this.size = size;
        this.sort = sort;
    }

    @Override
    public int getPageNumber() {
        return (int) (offset / size);
    }

    @Override
    public int getPageSize() {
        return size;
    }

    @Override
    public long getOffset() {
        return offset;
    }

    @Override
    public Sort getSort() {
        return sort;
    }

    @Override
    public Pageable next() {
        return new OffsetPageable(offset + size, size, sort);
    }

    @Override
    public Pageable previousOrFirst() {
        return hasPrevious() ? new OffsetPageable(Math.max(0, offset - size), size, sort) : this;
    }

    @Override
    public Pageable first() {
        return new OffsetPageable(0, size, sort);
    }

    @Override
    public Pageable withPage(int pageNumber) {
        return new OffsetPageable((long) pageNumber * size, size, sort);
    }

    @Override
    public boolean hasPrevious() {
        return offset > 0;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof OffsetPageable that && offset == that.offset && size == that.size
                && sort.equals(that.sort);
    }

    @Override
    public int hashCode() {
        return Objects.hash(offset, size, sort);
    }

    @Override
    public String toString() {
        return "OffsetPageable[offset=" + offset + ", size=" + size + ", sort=" + sort + "]";
    }
}
