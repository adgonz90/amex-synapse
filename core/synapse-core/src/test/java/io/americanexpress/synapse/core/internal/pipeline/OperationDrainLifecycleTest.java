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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.internal.context.ContextBinder;
import io.americanexpress.synapse.core.internal.context.ContextScope;
import io.americanexpress.synapse.core.internal.context.InboundContext;
import io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.GetBookOperation;
import io.americanexpress.synapse.core.lifecycle.SynapseLifecyclePhases;

import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.CLOCK;
import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.VALIDATOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationDrainLifecycleTest {

    private static final long SECONDS = 10;

    private final InflightTracker inflight = new InflightTracker();

    private final ContextBinder binder = PipelineFixtures.binder();

    private final OperationPipeline pipeline = OperationPipeline
            .builder()
            .clock(CLOCK)
            .validator(VALIDATOR)
            .contextBinder(binder)
            .inflight(inflight)
            .build();

    private final OperationDrainLifecycle lifecycle = new OperationDrainLifecycle(inflight);

    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void releaseAndStop() {
        release.countDown();
        executor.shutdownNow();
    }

    /** An operation that signals when it runs and then waits for the test to release it. */
    private GetBookOperation blocking(CountDownLatch started) {
        return pipeline.wrapOperation(new GetBookOperation(in -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "done";
        }));
    }

    @Test
    void runsInTheDrainPhase() {
        assertThat(lifecycle.getPhase()).isEqualTo(SynapseLifecyclePhases.DRAIN);
        assertThat(lifecycle.isAutoStartup()).isTrue();
        assertThat(lifecycle.isRunning()).isFalse();
        lifecycle.start();
        assertThat(lifecycle.isRunning()).isTrue();
        lifecycle.stop();
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    void stopWaitsForInflightOperations() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        GetBookOperation operation = blocking(started);
        Future<String> running = executor.submit(() -> operation.execute("x"));
        assertThat(started.await(SECONDS, TimeUnit.SECONDS)).isTrue();
        lifecycle.start();
        CountDownLatch stopped = new CountDownLatch(1);

        lifecycle.stop(stopped::countDown);

        assertThat(stopped.await(100, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(lifecycle.isRunning()).isTrue();
        release.countDown();
        assertThat(stopped.await(SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(running.get(SECONDS, TimeUnit.SECONDS)).isEqualTo("done");
        assertThat(inflight.inflight()).isZero();
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    void stopCallsBackImmediatelyWhenIdle() throws Exception {
        lifecycle.start();
        CountDownLatch stopped = new CountDownLatch(1);

        lifecycle.stop(stopped::countDown);

        assertThat(stopped.await(SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    void newInvocationsAreRejectedOnceDraining() throws Exception {
        GetBookOperation operation = pipeline.wrapOperation(new GetBookOperation(in -> "done"));
        assertThat(operation.execute("x")).isEqualTo("done");
        CountDownLatch stopped = new CountDownLatch(1);

        lifecycle.stop(stopped::countDown);

        // No RequestContext on this thread, so the pipeline opens one itself and must reject the call.
        assertThatThrownBy(() -> operation.execute("x"))
                .isInstanceOfSatisfying(SynapseException.class,
                        failure -> assertThat(failure.details()).isEqualTo(SynapseErrors.UNAVAILABLE));
        assertThat(inflight.inflight()).isZero();
        assertThat(stopped.await(SECONDS, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void startAfterAStopAdmitsNewInvocationsAgain() throws Exception {
        GetBookOperation operation = pipeline.wrapOperation(new GetBookOperation(in -> "done"));
        lifecycle.start();
        CountDownLatch stopped = new CountDownLatch(1);
        lifecycle.stop(stopped::countDown);
        assertThat(stopped.await(SECONDS, TimeUnit.SECONDS)).isTrue();

        lifecycle.start();

        assertThat(lifecycle.isRunning()).isTrue();
        assertThat(operation.execute("x")).isEqualTo("done");
        assertThat(inflight.inflight()).isZero();
    }

    @Test
    void invocationInAnExistingContextStillRunsAndTheDrainWaitsForIt() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        GetBookOperation operation = blocking(started);
        CountDownLatch stopped = new CountDownLatch(1);
        inflight.enter(); // the admitted request the call belongs to
        lifecycle.stop(stopped::countDown);

        Future<String> running = executor.submit(() -> {
            try (ContextScope scope = binder.open(InboundContext.empty())) {
                return operation.execute("x");
            }
        });

        // The operation ran although the drain had begun: the call belongs to admitted work.
        assertThat(started.await(SECONDS, TimeUnit.SECONDS)).isTrue();
        inflight.exit();
        assertThat(inflight.inflight()).isEqualTo(1);
        assertThat(stopped.await(100, TimeUnit.MILLISECONDS)).isFalse();
        release.countDown();
        assertThat(running.get(SECONDS, TimeUnit.SECONDS)).isEqualTo("done");
        assertThat(stopped.await(SECONDS, TimeUnit.SECONDS)).isTrue();
    }
}
