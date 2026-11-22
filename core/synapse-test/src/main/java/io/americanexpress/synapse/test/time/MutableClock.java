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
package io.americanexpress.synapse.test.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link Clock} whose time a test sets or advances. It is always in UTC and safe to share between threads.
 */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;

    private MutableClock(Instant start) {
        this.now = new AtomicReference<>(start);
    }

    /**
     * Creates a clock that starts at the instant.
     *
     * @param start the first instant the clock reports
     * @return the clock
     */
    public static MutableClock at(Instant start) {
        return new MutableClock(Objects.requireNonNull(start, "start"));
    }

    /**
     * Creates a clock that starts at an ISO-8601 instant such as {@code 2026-01-01T00:00:00Z}.
     *
     * @param isoInstant the first instant the clock reports
     * @return the clock
     * @throws java.time.format.DateTimeParseException if the text is not an ISO-8601 instant
     */
    public static MutableClock at(String isoInstant) {
        return at(Instant.parse(isoInstant));
    }

    /**
     * Sets the time.
     *
     * @param instant the instant the clock reports from now on
     */
    public void set(Instant instant) {
        now.set(Objects.requireNonNull(instant, "instant"));
    }

    /**
     * Moves the time by the duration (a negative duration moves it back).
     *
     * @param duration the amount to add
     */
    public void advance(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        now.updateAndGet(current -> current.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    /**
     * Returns a clock in the zone that reads the same mutable instant: setting or advancing this clock is visible
     * through it. For UTC that is this clock.
     *
     * @param zone the zone
     * @return a clock in the zone
     */
    @Override
    public Clock withZone(ZoneId zone) {
        Objects.requireNonNull(zone, "zone");
        return ZoneOffset.UTC.equals(zone) ? this : new ZonedView(this, zone);
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    /** A view of a {@link MutableClock} in another zone; it has no state of its own. */
    private static final class ZonedView extends Clock {

        private final MutableClock source;

        private final ZoneId zone;

        ZonedView(MutableClock source, ZoneId zone) {
            this.source = source;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            return source.withZone(newZone);
        }

        @Override
        public Instant instant() {
            return source.instant();
        }
    }
}
