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

import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.core.paging.SortOrder.Direction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageQueryTest {

    @Test
    void sortAndItemsAreImmutableCopies() {
        List<SortOrder> sort = new ArrayList<>(List.of(new SortOrder("a", Direction.ASC)));
        PageQuery query = new PageQuery(null, null, 20, sort);
        sort.add(new SortOrder("b", Direction.DESC));

        assertThat(query.sort()).containsExactly(new SortOrder("a", Direction.ASC));
        assertThatThrownBy(() -> query.sort().add(new SortOrder("c", Direction.ASC)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @SuppressWarnings("NullAway")
    void nullSortBecomesEmpty() {
        assertThat(new PageQuery(null, null, 20, null).sort()).isEmpty();
    }

    @Test
    void factoriesSetTheirFields() {
        PageQuery first = PageQuery.first(20);
        assertThat(first.cursor()).isNull();
        assertThat(first.offset()).isNull();
        assertThat(first.size()).isEqualTo(20);
        assertThat(first.sort()).isEmpty();

        List<SortOrder> sort = List.of(new SortOrder("t", Direction.DESC));
        PageQuery offset = PageQuery.ofOffset(40, 10, sort);
        assertThat(offset.cursor()).isNull();
        assertThat(offset.offset()).isEqualTo(40L);
        assertThat(offset.size()).isEqualTo(10);
        assertThat(offset.sort()).isEqualTo(sort);

        PageQuery cursor = PageQuery.ofCursor("c", 15, sort);
        assertThat(cursor.cursor()).isEqualTo("c");
        assertThat(cursor.offset()).isNull();
        assertThat(cursor.size()).isEqualTo(15);
        assertThat(cursor.sort()).isEqualTo(sort);
    }

    @Test
    void validQueriesHaveNoViolations() {
        assertThat(PageQuery.first(20).violations(100)).isEmpty();
        assertThat(PageQuery.ofOffset(40, 20, List.of()).violations(100)).isEmpty();
        assertThat(PageQuery.ofOffset(0, 20, List.of()).violations(100)).isEmpty();
        assertThat(PageQuery.ofCursor("c", 20, List.of()).violations(100)).isEmpty();
    }

    @Test
    void sizeBoundariesAreValid() {
        assertThat(PageQuery.first(1).violations(100)).isEmpty();
        assertThat(PageQuery.first(100).violations(100)).isEmpty();
    }

    @Test
    void cursorAndOffsetTogetherIsViolation() {
        assertThat(new PageQuery("c", 0L, 20, List.of()).violations(100))
                .containsExactly(new Violation("cursor", "must not be combined with offset"));
    }

    @Test
    void negativeOffsetIsViolation() {
        assertThat(PageQuery.ofOffset(-1, 20, List.of()).violations(100))
                .containsExactly(new Violation("offset", "must be greater than or equal to 0"));
    }

    @Test
    void sizeBoundsAreViolations() {
        assertThat(PageQuery.first(0).violations(100))
                .containsExactly(new Violation("size", "must be greater than or equal to 1"));
        assertThat(PageQuery.first(101).violations(100))
                .containsExactly(new Violation("size", "must be less than or equal to 100"));
        assertThat(PageQuery.first(11).violations(10))
                .containsExactly(new Violation("size", "must be less than or equal to 10"));
    }

    @Test
    void violationsAreInFieldOrder() {
        assertThat(new PageQuery("c", -1L, 0, List.of()).violations(100))
                .containsExactly(
                        new Violation("cursor", "must not be combined with offset"),
                        new Violation("offset", "must be greater than or equal to 0"),
                        new Violation("size", "must be greater than or equal to 1"));
    }
}
