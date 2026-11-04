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
package io.americanexpress.synapse.core.masking;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaskTypeTest {

    private static final String SMILEY = "😀";

    @Test
    void allMasksEveryCodePoint() {
        assertThat(MaskType.ALL.apply("4111")).isEqualTo("****");
        assertThat(MaskType.ALL.apply("a" + SMILEY + "b")).isEqualTo("***");
        assertThat(MaskType.ALL.apply("")).isEmpty();
    }

    @Test
    void fixedLengthHidesLength() {
        assertThat(MaskType.FIXED_LENGTH.apply("x")).isEqualTo("***");
        assertThat(MaskType.FIXED_LENGTH.apply("a".repeat(40))).isEqualTo("***");
    }

    @Test
    void lastFourShowsLastFour() {
        assertThat(MaskType.LAST_FOUR.apply("4111111111111111")).isEqualTo("************1111");
        assertThat(MaskType.LAST_FOUR.apply("12345")).isEqualTo("*2345");
    }

    @Test
    void lastFourMasksShortValues() {
        assertThat(MaskType.LAST_FOUR.apply("1234")).isEqualTo("****");
        assertThat(MaskType.LAST_FOUR.apply("12")).isEqualTo("**");
        assertThat(MaskType.LAST_FOUR.apply("")).isEmpty();
    }

    @Test
    void lastFourCountsCodePointsAndKeepsSurrogatePairsWhole() {
        assertThat(MaskType.LAST_FOUR.apply("ab" + SMILEY + "cde")).isEqualTo("**" + SMILEY + "cde");
        assertThat(MaskType.LAST_FOUR.apply(SMILEY + SMILEY + SMILEY + SMILEY)).isEqualTo("****");
    }
}
