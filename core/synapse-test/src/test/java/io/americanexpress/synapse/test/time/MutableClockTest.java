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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MutableClockTest {

    @Test
    void mutableClockAdvancesAndSets() {
        MutableClock clock = MutableClock.at("2026-01-01T00:00:00Z");

        clock.advance(Duration.ofSeconds(90));
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-01-01T00:01:30Z"));
        assertThat(clock.millis()).isEqualTo(Instant.parse("2026-01-01T00:01:30Z").toEpochMilli());

        clock.advance(Duration.ofSeconds(-30));
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-01-01T00:01:00Z"));

        clock.set(Instant.parse("2030-05-05T05:05:05Z"));
        assertThat(clock.instant()).isEqualTo(Instant.parse("2030-05-05T05:05:05Z"));
        assertThat(MutableClock.at(Instant.EPOCH).instant()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void zonesShareTheMutableInstant() {
        MutableClock clock = MutableClock.at(Instant.EPOCH);

        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(clock.withZone(ZoneOffset.UTC)).isSameAs(clock);
        Clock paris = clock.withZone(ZoneId.of("Europe/Paris"));
        assertThat(paris.getZone()).isEqualTo(ZoneId.of("Europe/Paris"));
        assertThat(paris.instant()).isEqualTo(Instant.EPOCH);
        clock.advance(Duration.ofHours(1));
        assertThat(paris.instant()).isEqualTo(Instant.EPOCH.plusSeconds(3600));
        clock.set(Instant.parse("2030-01-01T00:00:00Z"));
        assertThat(paris.instant()).isEqualTo(Instant.parse("2030-01-01T00:00:00Z"));
        assertThat(paris.withZone(ZoneOffset.UTC)).isSameAs(clock);
        assertThat(paris.withZone(ZoneId.of("Asia/Tokyo")).getZone()).isEqualTo(ZoneId.of("Asia/Tokyo"));
    }

    @Test
    void advancingFromManyThreadsLosesNoUpdate() throws InterruptedException {
        MutableClock clock = MutableClock.at(Instant.EPOCH);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Void>> tasks = IntStream.range(0, 1000).<Callable<Void>>mapToObj(i -> () -> {
                clock.advance(Duration.ofSeconds(1));
                return null;
            }).toList();
            for (Future<Void> done : pool.invokeAll(tasks)) {
                assertThat(done.isDone()).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(clock.instant()).isEqualTo(Instant.EPOCH.plusSeconds(1000));
    }
}
