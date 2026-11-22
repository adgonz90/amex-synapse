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
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import io.americanexpress.synapse.core.lifecycle.SynapseLifecyclePhases;

/**
 * Drains in-flight operations at shutdown. In the {@link SynapseLifecyclePhases#DRAIN} phase it stops the
 * {@link InflightTracker} from admitting calls that open their own request context, then reports the phase stopped once
 * the operations still running have finished. When the context is started again (after {@code stop()} or
 * {@code pause()}), it admits calls again.
 *
 * <p>
 * Spring's lifecycle processor waits for the callback of {@link #stop(Runnable)} for at most
 * {@code spring.lifecycle.timeout-per-shutdown-phase}, then carries on with the next phase. The wait here is capped at
 * {@link #MAXIMUM_WAIT} only so that a phase abandoned by the processor does not leave its thread waiting forever.
 */
public final class OperationDrainLifecycle implements SmartLifecycle {

    /** The longest the drain thread waits for operations once the processor has stopped waiting for it. */
    static final Duration MAXIMUM_WAIT = Duration.ofMinutes(10);

    private static final Logger LOGGER = LoggerFactory.getLogger(OperationDrainLifecycle.class);

    private final InflightTracker inflight;

    private final AtomicBoolean running = new AtomicBoolean();

    /**
     * Creates the lifecycle.
     *
     * @param inflight the tracker the pipeline counts calls with
     */
    public OperationDrainLifecycle(InflightTracker inflight) {
        this.inflight = inflight;
    }

    /**
     * Admits calls again. Besides the first start, this runs when a stopped or paused context is started again, for
     * example by Spring's test context cache when a test class reuses a paused context, or by CRaC on restore.
     */
    @Override
    public void start() {
        inflight.stopDraining();
        running.set(true);
    }

    @Override
    public void stop() {
        inflight.startDraining();
        running.set(false);
    }

    @Override
    public void stop(Runnable callback) {
        inflight.startDraining();
        Thread.ofVirtual().name("synapse-drain").start(() -> {
            try {
                if (!inflight.awaitIdle(MAXIMUM_WAIT)) {
                    LOGGER.warn("Gave up draining with {} operation call(s) still in flight", inflight.inflight());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                running.set(false);
                callback.run();
            }
        });
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return SynapseLifecyclePhases.DRAIN;
    }
}
