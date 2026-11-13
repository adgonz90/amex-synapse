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

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;

import io.americanexpress.synapse.core.paging.SortOrder.Direction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SpringDataPagesTest {

    @Test
    void convertsPageWithTotal() {
        Page<String> page = new PageImpl<>(List.of("a", "b", "c"), PageRequest.of(0, 3), 10);
        PageResult<String> result = SpringDataPages.from(page);
        assertThat(result.items()).containsExactly("a", "b", "c");
        assertThat(result.hasNext()).isTrue();
        assertThat(result.total()).isEqualTo(10L);
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    void lastPageHasNoNext() {
        Page<String> page = new PageImpl<>(List.of("a"), PageRequest.of(1, 3), 4);
        PageResult<String> result = SpringDataPages.from(page);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.total()).isEqualTo(4L);
    }

    /**
     * An offset that isn't a multiple of the size: Spring Data's {@code PageImpl.hasNext()} compares page numbers
     * ({@code offset / size + 1 < totalPages}, here 1 + 1 &lt; 3), which says true at the end. The page at offset 3 of
     * size 2 holds the last two of 5 elements, so no page follows.
     */
    @Test
    void anUnalignedLastPageHasNoNext() {
        Page<String> page = new PageImpl<>(List.of("d", "e"), offset(3, 2), 5);

        PageResult<String> result = SpringDataPages.from(page);

        assertThat(result.hasNext()).isFalse();
        assertThat(result.total()).isEqualTo(5L);
    }

    @Test
    void anUnalignedPageBeforeTheEndHasANext() {
        assertThat(SpringDataPages.from(new PageImpl<>(List.of("b", "c"), offset(1, 2), 5)).hasNext()).isTrue();
        // One element is left after this page.
        assertThat(SpringDataPages.from(new PageImpl<>(List.of("c", "d", "e"), offset(2, 3), 6)).hasNext()).isTrue();
    }

    @Test
    void alignedOffsetsAnswerAsSpringData() {
        // Spring Data's page-number rule is right for an aligned offset, so both answer alike, next page or not.
        for (Page<String> page : List
                .<Page<String>>of(new PageImpl<>(List.of("a", "b"), offset(0, 2), 5),
                        new PageImpl<>(List.of("c", "d"), offset(2, 2), 6),
                        new PageImpl<>(List.of("c", "d"), offset(2, 2), 4),
                        new PageImpl<>(List.of("e"), offset(4, 2), 5))) {
            assertThat(SpringDataPages.from(page).hasNext()).as("%s", page).isEqualTo(page.hasNext());
        }
    }

    @Test
    void anEmptyPageHasNoNext() {
        // Past the end: the offset is beyond every element.
        PageResult<String> past = SpringDataPages.from(new PageImpl<>(List.of(), offset(7, 2), 5));
        assertThat(past.items()).isEmpty();
        assertThat(past.hasNext()).isFalse();
        assertThat(past.total()).isEqualTo(5L);
        // Nothing matches.
        PageResult<String> none = SpringDataPages.from(new PageImpl<>(List.of(), offset(0, 2), 0));
        assertThat(none.hasNext()).isFalse();
        assertThat(none.total()).isZero();
    }

    @Test
    void anUnpagedPageHasNoNext() {
        assertThat(SpringDataPages.from(new PageImpl<>(List.of("a", "b"))).hasNext()).isFalse();
        // Unpaged with more elements than it holds: Spring Data says a next page follows (its size is the content's),
        // but there is no offset to continue from.
        Page<String> part = new PageImpl<>(List.of("a"), Pageable.unpaged(), 5);
        assertThat(part.hasNext()).isTrue();
        assertThat(SpringDataPages.from(part).hasNext()).isFalse();
        assertThat(SpringDataPages.from(part).total()).isEqualTo(5L);
    }

    @Test
    void convertsSliceWithoutTotal() {
        Slice<String> slice = new SliceImpl<>(List.of("a"), PageRequest.of(0, 3), false);
        PageResult<String> result = SpringDataPages.from(slice);
        assertThat(result.items()).containsExactly("a");
        assertThat(result.hasNext()).isFalse();
        assertThat(result.total()).isNull();
        assertThat(result.nextCursor()).isNull();

        // At an unaligned offset too: the flag is the query's.
        assertThat(SpringDataPages.from(new SliceImpl<>(List.of("d", "e"), offset(3, 2), false)).hasNext()).isFalse();

        Slice<String> more = new SliceImpl<>(List.of("a"), PageRequest.of(0, 3), true);
        assertThat(SpringDataPages.from(more).hasNext()).isTrue();
    }

    @Test
    void pageableUsesExactOffset() {
        Pageable pageable = SpringDataPages
                .pageable(PageQuery.ofOffset(5, 20, List.of(new SortOrder("title", Direction.DESC))));
        assertThat(pageable.getOffset()).isEqualTo(5L);
        assertThat(pageable.getPageSize()).isEqualTo(20);
        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Order.desc("title")));
    }

    @Test
    void sortDirectionsMap() {
        Pageable pageable = SpringDataPages
                .pageable(PageQuery
                        .ofOffset(0, 5,
                                List.of(new SortOrder("a", Direction.ASC), new SortOrder("b", Direction.DESC))));
        assertThat(pageable.getSort()).containsExactly(Sort.Order.asc("a"), Sort.Order.desc("b"));
    }

    @Test
    void pageableFromFirstPage() {
        Pageable pageable = SpringDataPages.pageable(PageQuery.first(20));
        assertThat(pageable.getOffset()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(20);
        assertThat(pageable.getSort().isUnsorted()).isTrue();
        assertThat(pageable.isPaged()).isTrue();
    }

    @Test
    void pageableRejectsCursor() {
        PageQuery query = PageQuery.ofCursor("c", 20, List.of());
        assertThatIllegalArgumentException().isThrownBy(() -> SpringDataPages.pageable(query));
    }

    @Test
    void pageNumberIsOffsetDividedBySize() {
        Pageable pageable = SpringDataPages.pageable(PageQuery.ofOffset(45, 20, List.of()));
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getOffset()).isEqualTo(45L);
    }

    @Test
    void nextMovesBySize() {
        Pageable next = SpringDataPages.pageable(PageQuery.ofOffset(5, 20, sorted())).next();
        assertThat(next.getOffset()).isEqualTo(25L);
        assertThat(next.getPageSize()).isEqualTo(20);
        assertThat(next.getSort()).isEqualTo(titleDesc());
    }

    @Test
    void previousOrFirstMovesBackBySizeOrStopsAtZero() {
        Pageable previous = SpringDataPages.pageable(PageQuery.ofOffset(50, 20, sorted())).previousOrFirst();
        assertThat(previous.getOffset()).isEqualTo(30L);
        assertThat(previous.getPageSize()).isEqualTo(20);
        assertThat(previous.getSort()).isEqualTo(titleDesc());

        assertThat(SpringDataPages.pageable(PageQuery.ofOffset(20, 20, sorted())).previousOrFirst().getOffset())
                .isZero();
        Pageable shortOfOne = SpringDataPages.pageable(PageQuery.ofOffset(5, 20, sorted())).previousOrFirst();
        assertThat(shortOfOne.getOffset()).isZero();
        assertThat(shortOfOne.getSort()).isEqualTo(titleDesc());
        assertThat(SpringDataPages.pageable(PageQuery.ofOffset(0, 20, sorted())).previousOrFirst().getOffset())
                .isZero();
    }

    @Test
    void hasPreviousIsTrueOnlyAfterOffsetZero() {
        assertThat(SpringDataPages.pageable(PageQuery.ofOffset(0, 20, sorted())).hasPrevious()).isFalse();
        assertThat(SpringDataPages.pageable(PageQuery.ofOffset(1, 20, sorted())).hasPrevious()).isTrue();
    }

    @Test
    void firstIsOffsetZeroKeepingSizeAndSort() {
        Pageable first = SpringDataPages.pageable(PageQuery.ofOffset(50, 20, sorted())).first();
        assertThat(first.getOffset()).isZero();
        assertThat(first.getPageSize()).isEqualTo(20);
        assertThat(first.getSort()).isEqualTo(titleDesc());
    }

    @Test
    void withPageMovesToThePageNumber() {
        Pageable page3 = SpringDataPages.pageable(PageQuery.ofOffset(5, 20, sorted())).withPage(3);
        assertThat(page3.getOffset()).isEqualTo(60L);
        assertThat(page3.getPageNumber()).isEqualTo(3);
        assertThat(page3.getPageSize()).isEqualTo(20);
        assertThat(page3.getSort()).isEqualTo(titleDesc());
    }

    @Test
    void equalPageablesAreEqual() {
        Pageable one = SpringDataPages.pageable(PageQuery.ofOffset(5, 20, sorted()));
        Pageable same = SpringDataPages.pageable(PageQuery.ofOffset(5, 20, sorted()));
        assertThat(one).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(one).isNotEqualTo(SpringDataPages.pageable(PageQuery.ofOffset(6, 20, sorted())));
        assertThat(one).isNotEqualTo(SpringDataPages.pageable(PageQuery.ofOffset(5, 21, sorted())));
        assertThat(one).isNotEqualTo(SpringDataPages.pageable(PageQuery.ofOffset(5, 20, List.of())));
        assertThat(one).isNotEqualTo("x");
        assertThat(one.toString()).contains("offset=5").contains("size=20");
    }

    /** The pageable SpringDataPages.pageable gives for an offset query. */
    private static Pageable offset(long offset, int size) {
        return SpringDataPages.pageable(PageQuery.ofOffset(offset, size, List.of()));
    }

    private static List<SortOrder> sorted() {
        return List.of(new SortOrder("title", Direction.DESC));
    }

    private static Sort titleDesc() {
        return Sort.by(Sort.Order.desc("title"));
    }
}
