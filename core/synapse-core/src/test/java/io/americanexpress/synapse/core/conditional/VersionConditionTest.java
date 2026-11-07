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
package io.americanexpress.synapse.core.conditional;

import java.util.Arrays;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.core.conditional.VersionCondition.AnyVersion;
import io.americanexpress.synapse.core.conditional.VersionCondition.Version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class VersionConditionTest {

    @Test
    void anyMatchesEveryVersion() {
        VersionCondition<Long> any = VersionCondition.any();

        assertThat(any.matches(1L)).isTrue();
        assertThat(any.matches(99L)).isTrue();
        assertThat(any).isInstanceOf(AnyVersion.class).isSameAs(VersionCondition.<String>any());
        assertThat(any).isEqualTo(new AnyVersion<Long>());
    }

    @Test
    @SuppressWarnings("NullAway")
    void versionMatchesOnlyItsValue() {
        VersionCondition<Long> five = VersionCondition.of(5L);

        assertThat(five.matches(5L)).isTrue();
        assertThat(five.matches(6L)).isFalse();
        assertThat(five).isEqualTo(new Version<>(5L));
        assertThatNullPointerException().isThrownBy(() -> VersionCondition.of(null));
        assertThatNullPointerException().isThrownBy(() -> new Version<Long>(null));
    }

    @Test
    void toStringShowsTheWireForm() {
        assertThat(VersionCondition.any()).hasToString("*");
        assertThat(VersionCondition.of(5L)).hasToString("\"5\"");
    }

    @Test
    void switchCoversEveryCase() {
        assertThat(Arrays
                .<@Nullable VersionCondition<Long>>asList(null, VersionCondition.any(),
                        VersionCondition.of(5L)))
                .extracting(VersionConditionTest::describe)
                .containsExactly("none", "any", "v5");
    }

    private static String describe(@Nullable VersionCondition<Long> condition) {
        return switch (condition) {
            case null -> "none";
            case AnyVersion<Long> any -> "any";
            case Version<Long>(Long v) -> "v" + v;
        };
    }
}
