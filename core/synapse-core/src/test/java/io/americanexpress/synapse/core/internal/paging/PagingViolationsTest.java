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
package io.americanexpress.synapse.core.internal.paging;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.core.paging.SortOrder;

import static org.assertj.core.api.Assertions.assertThat;

class PagingViolationsTest {

    private static Map<String, String> keys() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("title", "TITLE");
        keys.put("publishedYear", "PUBLISHED_YEAR");
        return keys;
    }

    @Test
    void textsAreTheOnesBothProtocolsReport() {
        assertThat(PagingViolations.INVALID).isEqualTo("is not a valid value");
        assertThat(PagingViolations.NOT_SUPPORTED).isEqualTo("is not supported");
        assertThat(PagingViolations.atLeast(1)).isEqualTo("must be greater than or equal to 1");
        assertThat(PagingViolations.atMost(100)).isEqualTo("must be less than or equal to 100");
        assertThat(PagingViolations.notCombinedWith("skip")).isEqualTo("must not be combined with skip");
    }

    @Test
    void allowedKeysBecomeTheSortInTheCallersOrder() {
        PagingViolations.SortReading reading = new PagingViolations.SortReading(keys());

        reading.key("publishedYear", true);
        reading.key("title", false);

        assertThat(reading.problems()).isEmpty();
        assertThat(reading.sort())
                .containsExactly(new SortOrder("PUBLISHED_YEAR", SortOrder.Direction.DESC),
                        new SortOrder("TITLE", SortOrder.Direction.ASC));
    }

    @Test
    void eachProblemIsReportedOnceInTheOrderFirstFound() {
        PagingViolations.SortReading reading = new PagingViolations.SortReading(keys());

        reading.key("title", false);
        reading.key("author", false);
        reading.invalid();
        reading.key("title", true);
        reading.key("isbn", false);
        reading.invalid();

        assertThat(reading.problems())
                .containsExactly("has a key that is not allowed (allowed: title, publishedYear)",
                        "is not a valid value",
                        "names a key more than once");
        assertThat(reading.sort()).isEmpty();
    }
}
