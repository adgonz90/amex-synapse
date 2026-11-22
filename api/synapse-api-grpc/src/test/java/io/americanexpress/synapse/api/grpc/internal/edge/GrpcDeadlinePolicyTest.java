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
package io.americanexpress.synapse.api.grpc.internal.edge;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import io.grpc.Deadline;
import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.test.time.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;

class GrpcDeadlinePolicyTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final MutableClock clock = MutableClock.at("2026-01-01T00:00:00Z");

    private final GrpcDeadlinePolicy policy = new GrpcDeadlinePolicy(Duration.ofSeconds(10), Duration.ofSeconds(30),
            clock);

    private final FakeTicker ticker = new FakeTicker();

    @Test
    void defaultWithoutGrpcDeadline() {
        assertThat(policy.deadline(null)).isEqualTo(Instant.parse("2026-01-01T00:00:10Z"));
    }

    @Test
    void grpcDeadlineWithinMax() {
        Deadline deadline = Deadline.after(500, TimeUnit.MILLISECONDS, ticker);

        assertThat(policy.deadline(deadline)).isEqualTo(Instant.parse("2026-01-01T00:00:00.500Z"));
    }

    @Test
    void cappedByMax() {
        Deadline deadline = Deadline.after(120, TimeUnit.SECONDS, ticker);

        assertThat(policy.deadline(deadline)).isEqualTo(Instant.parse("2026-01-01T00:00:30Z"));
    }

    @Test
    void expiredDeadlineIsInThePast() {
        Deadline deadline = Deadline.after(-1, TimeUnit.MILLISECONDS, ticker);

        assertThat(policy.deadline(deadline)).isBefore(NOW).isEqualTo(NOW.minusMillis(1));
    }

    @Test
    void deadlineIsComputedFromTheClockAtCallTime() {
        Deadline deadline = Deadline.after(2, TimeUnit.SECONDS, ticker);
        clock.advance(Duration.ofSeconds(5));

        assertThat(policy.deadline(deadline)).isEqualTo(Instant.parse("2026-01-01T00:00:07Z"));
        assertThat(policy.deadline(null)).isEqualTo(Instant.parse("2026-01-01T00:00:15Z"));
    }

    /** A ticker that stands still, so a deadline's remaining time is exactly what it was created with. */
    static final class FakeTicker extends Deadline.Ticker {

        @Override
        public long nanoTime() {
            return 0;
        }
    }
}
