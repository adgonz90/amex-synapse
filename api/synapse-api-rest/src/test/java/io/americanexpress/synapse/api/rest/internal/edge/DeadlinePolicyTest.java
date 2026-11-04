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
package io.americanexpress.synapse.api.rest.internal.edge;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.americanexpress.synapse.test.time.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;

class DeadlinePolicyTest {

    private static final Instant DEFAULT_DEADLINE = Instant.parse("2026-01-01T00:00:10Z");

    private final MutableClock clock = MutableClock.at("2026-01-01T00:00:00Z");

    private final DeadlinePolicy policy = new DeadlinePolicy(Duration.ofSeconds(10), Duration.ofSeconds(30), clock);

    @Test
    void defaultWhenAbsent() {
        assertThat(policy.deadline(null)).isEqualTo(DEFAULT_DEADLINE);
    }

    @Test
    void requestedBudgetWithinMax() {
        assertThat(policy.deadline("500")).isEqualTo(Instant.parse("2026-01-01T00:00:00.500Z"));
    }

    @Test
    void requestedBudgetAtMaxIsKept() {
        assertThat(policy.deadline("30000")).isEqualTo(Instant.parse("2026-01-01T00:00:30Z"));
    }

    @Test
    void cappedByMax() {
        assertThat(policy.deadline("999999")).isEqualTo(Instant.parse("2026-01-01T00:00:30Z"));
    }

    @Test
    void valueTooLargeForALongIsCappedByMax() {
        assertThat(policy.deadline("99999999999999999999999")).isEqualTo(Instant.parse("2026-01-01T00:00:30Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "-5", "0", "1.5", "", "+5", " 5", "5ms", "00"})
    void malformedIsIgnored(String header) {
        assertThat(policy.deadline(header)).isEqualTo(DEFAULT_DEADLINE);
    }

    @Test
    void followsTheClock() {
        clock.advance(Duration.ofSeconds(5));

        assertThat(policy.deadline(null)).isEqualTo(Instant.parse("2026-01-01T00:00:15Z"));
        assertThat(policy.deadline("1000")).isEqualTo(Instant.parse("2026-01-01T00:00:06Z"));
    }
}
