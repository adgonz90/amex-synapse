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
package io.americanexpress.synapse.data.internal.id;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.test.time.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV7GeneratorTest {

    private static final long SEED = 42L;

    private final MutableClock clock = MutableClock.at("2026-01-01T00:00:00Z");

    private final UuidV7Generator generator = new UuidV7Generator(clock, new SplittableRandom(SEED));

    @Test
    void versionAndVariantBits() {
        UUID id = generator.nextId();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void timestampIsTheClockMillis() {
        UUID id = generator.nextId();

        assertThat(timestamp(id)).isEqualTo(clock.millis()).isEqualTo(1_767_225_600_000L);
    }

    @Test
    void monotonicWithinAMillisecond() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 4096; i++) {
            ids.add(generator.nextId());
        }

        for (int i = 1; i < ids.size(); i++) {
            assertThat(compareUnsigned(ids.get(i), ids.get(i - 1))).as("id %d after id %d", i, i - 1).isPositive();
        }
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void counterOverflowMovesToTheNextMillisecond() {
        UuidV7Generator fixed = new UuidV7Generator(clock, new FixedRandom());
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 2050; i++) {
            ids.add(fixed.nextId());
        }

        // The first id draws 0x7FF; the next 2048 count up to 0xFFF in the same millisecond.
        assertThat(counter(ids.get(0))).isEqualTo(0x7FF);
        assertThat(timestamp(ids.get(2048))).isEqualTo(clock.millis());
        assertThat(counter(ids.get(2048))).isEqualTo(0xFFF);
        // The 2050th id has no room left in the millisecond, so it moves to the next one and draws again.
        assertThat(timestamp(ids.get(2049))).isEqualTo(clock.millis() + 1);
        assertThat(counter(ids.get(2049))).isEqualTo(0x7FF);
        assertThat(compareUnsigned(ids.get(2049), ids.get(2048))).isPositive();
    }

    @Test
    void clockGoingBackStaysMonotonic() {
        UUID previous = generator.nextId();
        clock.advance(Duration.ofMillis(-5));

        UUID next = generator.nextId();

        assertThat(compareUnsigned(next, previous)).isPositive();
        assertThat(timestamp(next)).isEqualTo(timestamp(previous));
        assertThat(counter(next)).isEqualTo(counter(previous) + 1);
    }

    @Test
    void newMillisecondRestartsTheCounter() {
        UUID first = generator.nextId();
        clock.advance(Duration.ofMillis(1));

        UUID second = generator.nextId();

        SplittableRandom replay = new SplittableRandom(SEED);
        int firstDraw = replay.nextInt(0x800);
        replay.nextLong();
        int secondDraw = replay.nextInt(0x800);
        assertThat(counter(first)).isEqualTo(firstDraw);
        assertThat(timestamp(second)).isEqualTo(timestamp(first) + 1);
        assertThat(counter(second)).isEqualTo(secondDraw);
    }

    @Test
    void randomBitsFillTheLowWord() {
        UUID id = generator.nextId();

        SplittableRandom replay = new SplittableRandom(SEED);
        replay.nextInt(0x800);
        long random = replay.nextLong();
        assertThat(id.getLeastSignificantBits())
                .isEqualTo((random & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L);
    }

    @Test
    void publicConstructorUsesASecureRandom() {
        UuidV7Generator secure = new UuidV7Generator(clock);

        UUID first = secure.nextId();
        UUID second = secure.nextId();

        assertThat(first.version()).isEqualTo(7);
        assertThat(first.variant()).isEqualTo(2);
        assertThat(timestamp(first)).isEqualTo(clock.millis());
        assertThat(compareUnsigned(second, first)).isPositive();
    }

    private static long timestamp(UUID id) {
        return id.getMostSignificantBits() >>> 16;
    }

    private static int counter(UUID id) {
        return (int) (id.getMostSignificantBits() & 0xFFF);
    }

    private static int compareUnsigned(UUID a, UUID b) {
        int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }

    /** Draws the largest starting counter every time. */
    private static final class FixedRandom implements RandomGenerator {

        @Override
        public long nextLong() {
            return 0L;
        }

        @Override
        public int nextInt(int bound) {
            return 0x7FF;
        }
    }
}
