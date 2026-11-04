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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Counts outermost operation calls in flight so shutdown can drain them. Once {@link #startDraining()} is called,
 * {@link #tryEnter()} rejects new calls while the ones already running finish, until {@link #stopDraining()} is
 * called when the context starts again.
 *
 * <p>
 * The pipeline uses {@link #tryEnter()} only for calls that have no request context, which it must open itself (for
 * example {@code @Scheduled} methods and runners). A call that finds a context, opened by an inbound edge or inherited
 * from an admitted request on another thread (async fan-out), is counted with {@link #enter()} and never rejected:
 * inbound edges stop in an earlier lifecycle phase than the drain, so such a call belongs to work the drain is
 * already waiting for.
 *
 * <p>
 * Thread-safe. It uses a {@link ReentrantLock} rather than {@code synchronized} so waiting never pins a virtual
 * thread's carrier on Java 21.
 */
public final class InflightTracker {

    private final AtomicInteger inflight = new AtomicInteger();

    private volatile boolean draining;

    private final ReentrantLock lock = new ReentrantLock();

    private final Condition idle = lock.newCondition();

    /**
     * Creates a tracker with nothing in flight.
     */
    public InflightTracker() {
        // nothing to initialize
    }

    /**
     * Counts a new call unless the tracker is draining.
     *
     * @return {@code true} if the call was counted and must be matched by {@link #exit()}; {@code false} when the
     *         tracker is draining and the call must be rejected
     */
    public boolean tryEnter() {
        // Increment before reading the flag: startDraining() sets the flag before awaitIdle() reads the count, so
        // either this call sees the flag or the drain sees this call.
        inflight.incrementAndGet();
        if (draining) {
            exit();
            return false;
        }
        return true;
    }

    /**
     * Counts a new call even while draining. Use it for calls that belong to work already admitted; the call must be
     * matched by {@link #exit()}.
     */
    public void enter() {
        inflight.incrementAndGet();
    }

    /**
     * Marks a counted call as finished.
     *
     * @throws IllegalStateException when no call is in flight
     */
    public void exit() {
        int left = inflight.decrementAndGet();
        if (left < 0) {
            inflight.incrementAndGet();
            throw new IllegalStateException("exit() called without a matching tryEnter()");
        }
        if (left == 0) {
            lock.lock();
            try {
                idle.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Returns the number of calls in flight.
     *
     * @return the count
     */
    public int inflight() {
        return inflight.get();
    }

    /**
     * Rejects new calls from now on. Calls already in flight continue.
     */
    public void startDraining() {
        draining = true;
    }

    /**
     * Admits new calls again after {@link #startDraining()}, for a context that is restarted after being stopped or
     * paused.
     */
    public void stopDraining() {
        draining = false;
    }

    /**
     * Waits until no call is in flight.
     *
     * @param timeout the longest time to wait
     * @return {@code true} if no call is in flight, {@code false} if calls were still running when the timeout
     *         elapsed
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        long nanos = timeout.toNanos();
        lock.lock();
        try {
            while (inflight.get() > 0) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = idle.awaitNanos(nanos);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }
}
