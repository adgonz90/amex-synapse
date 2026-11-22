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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageResultTest {

    @Test
    void cursorResultHasNextWhenCursorPresent() {
        PageResult<String> with = PageResult.ofCursor(List.of("a"), "n");
        assertThat(with.hasNext()).isTrue();
        assertThat(with.nextCursor()).isEqualTo("n");
        assertThat(with.total()).isNull();

        PageResult<String> without = PageResult.ofCursor(List.of("a"), null);
        assertThat(without.hasNext()).isFalse();
        assertThat(without.nextCursor()).isNull();
    }

    @Test
    void ofSetsHasNextAndNoCursorOrTotal() {
        PageResult<String> result = PageResult.of(List.of("a", "b"), true);
        assertThat(result.items()).containsExactly("a", "b");
        assertThat(result.hasNext()).isTrue();
        assertThat(result.nextCursor()).isNull();
        assertThat(result.total()).isNull();
        assertThat(PageResult.of(List.of("a"), false).hasNext()).isFalse();
    }

    @Test
    void withTotalKeepsEverythingElse() {
        PageResult<String> result = PageResult.ofCursor(List.of("a"), "n").withTotal(7);
        assertThat(result.total()).isEqualTo(7L);
        assertThat(result.items()).containsExactly("a");
        assertThat(result.nextCursor()).isEqualTo("n");
        assertThat(result.hasNext()).isTrue();
    }

    @Test
    void itemsAreAnImmutableCopy() {
        List<String> items = new ArrayList<>(List.of("a"));
        PageResult<String> result = PageResult.of(items, false);
        items.add("b");
        assertThat(result.items()).containsExactly("a");
        assertThatThrownBy(() -> result.items().add("c")).isInstanceOf(UnsupportedOperationException.class);
    }
}
