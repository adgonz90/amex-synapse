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

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.random.RandomGenerator;

import io.americanexpress.synapse.data.EntityIdGenerator;

/**
 * Generates UUIDv7 ids (RFC 9562, section 6.2, method 1): a 48-bit Unix timestamp in milliseconds from the
 * {@link Clock}, a 12-bit counter in {@code rand_a} and 62 random bits in {@code rand_b}. Ids from one generator are
 * strictly increasing, also within a millisecond and when the clock goes back: the counter starts at a random value
 * below {@code 0x800} each millisecond, leaving room to count, and when it passes {@code 0xFFF} the generator moves on
 * to the next millisecond.
 */
public final class UuidV7Generator implements EntityIdGenerator {

    private static final int COUNTER_SEED_BOUND = 0x800;

    private static final int COUNTER_MAX = 0xFFF;

    private final Clock clock;

    private final RandomGenerator random;

    private long lastMillis = Long.MIN_VALUE;

    private int counter;

    /**
     * Creates a generator that reads the time from the clock and draws its random bits from a {@link SecureRandom}.
     *
     * @param clock the clock that gives the timestamp
     */
    public UuidV7Generator(Clock clock) {
        this(clock, new SecureRandom());
    }

    UuidV7Generator(Clock clock, RandomGenerator random) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public synchronized UUID nextId() {
        long now = clock.millis();
        if (now > lastMillis) {
            lastMillis = now;
            counter = random.nextInt(COUNTER_SEED_BOUND);
        } else {
            counter++;
            if (counter > COUNTER_MAX) {
                lastMillis++;
                counter = random.nextInt(COUNTER_SEED_BOUND);
            }
        }
        long msb = (lastMillis & 0xFFFF_FFFF_FFFFL) << 16 | 0x7000 | counter;
        long lsb = (random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb);
    }
}
