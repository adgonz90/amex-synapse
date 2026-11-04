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
package io.americanexpress.synapse.core.internal.pipeline;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InflightTrackerTest {

    private final InflightTracker tracker = new InflightTracker();

    @Test
    void countsEntriesAndExits() {
        assertThat(tracker.inflight()).isZero();
        assertThat(tracker.tryEnter()).isTrue();
        assertThat(tracker.tryEnter()).isTrue();
        assertThat(tracker.inflight()).isEqualTo(2);

        tracker.exit();

        assertThat(tracker.inflight()).isEqualTo(1);
    }

    @Test
    void rejectsNewEntriesWhileDraining() {
        assertThat(tracker.tryEnter()).isTrue();

        tracker.startDraining();

        assertThat(tracker.tryEnter()).isFalse();
        assertThat(tracker.inflight()).isEqualTo(1);
    }

    @Test
    void admitsNewEntriesAgainOnceDrainingStops() {
        assertThat(tracker.tryEnter()).isTrue();
        tracker.startDraining();
        assertThat(tracker.tryEnter()).isFalse();

        tracker.stopDraining();

        assertThat(tracker.tryEnter()).isTrue();
        assertThat(tracker.inflight()).isEqualTo(2);
    }

    @Test
    void enterCountsEvenWhileDraining() throws InterruptedException {
        tracker.startDraining();

        tracker.enter();

        assertThat(tracker.inflight()).isEqualTo(1);
        assertThat(tracker.awaitIdle(Duration.ZERO)).isFalse();
        tracker.exit();
        assertThat(tracker.awaitIdle(Duration.ZERO)).isTrue();
    }

    @Test
    void exitWithoutEnterFails() {
        assertThatThrownBy(tracker::exit).isInstanceOf(IllegalStateException.class);
        assertThat(tracker.inflight()).isZero();
    }

    @Test
    void awaitIdleReturnsImmediatelyWhenIdle() throws InterruptedException {
        assertThat(tracker.awaitIdle(Duration.ZERO)).isTrue();
    }

    @Test
    void awaitIdleTimesOutWhileBusy() throws InterruptedException {
        tracker.tryEnter();

        long start = System.nanoTime();
        assertThat(tracker.awaitIdle(Duration.ofMillis(50))).isFalse();

        assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(50));
        assertThat(tracker.awaitIdle(Duration.ZERO)).isFalse();
    }

    @Test
    void awaitIdleWakesWhenTheLastCallExits() throws Exception {
        tracker.tryEnter();
        tracker.tryEnter();
        tracker.startDraining();
        CountDownLatch waiting = new CountDownLatch(1);

        CompletableFuture<Boolean> idle = CompletableFuture.supplyAsync(() -> {
            waiting.countDown();
            try {
                return tracker.awaitIdle(Duration.ofSeconds(30));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        });
        waiting.await();
        tracker.exit();
        assertThat(idle).isNotDone();
        tracker.exit();

        assertThat(idle.get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(tracker.inflight()).isZero();
    }
}
