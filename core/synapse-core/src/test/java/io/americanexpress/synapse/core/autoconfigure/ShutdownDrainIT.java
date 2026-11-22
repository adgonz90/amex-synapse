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
package io.americanexpress.synapse.core.autoconfigure;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.LifecycleAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Closes a real application context while an operation runs: the close waits for the operation, and with
 * {@code spring.lifecycle.timeout-per-shutdown-phase} set it gives up on a stuck one.
 */
@Tag("smoke")
class ShutdownDrainIT {

    private static final long SECONDS = 10;

    private final ApplicationContextRunner runner = OperationFixtures
            .runner()
            .withConfiguration(AutoConfigurations.of(LifecycleAutoConfiguration.class))
            .withUserConfiguration(Operations.class);

    @Test
    void closeWaitsForARunningOperation() throws Exception {
        runner.run(context -> {
            Gate gate = context.getBean(Gate.class);
            BlockingOperation blocking = context.getBean(BlockingOperation.class);
            OperationFixtures.GetBookOperation probe = context.getBean(OperationFixtures.GetBookOperation.class);
            CompletableFuture<String> running = CompletableFuture.supplyAsync(() -> blocking.execute("x"));
            try {
                assertThat(gate.started.await(SECONDS, TimeUnit.SECONDS)).isTrue();

                CompletableFuture<Void> closing = CompletableFuture.runAsync(context::close);
                awaitDraining(probe);
                assertThat(closing).isNotDone();
                assertThat(running).isNotDone();

                gate.release.countDown();
                closing.get(SECONDS, TimeUnit.SECONDS);
                assertThat(running.get(SECONDS, TimeUnit.SECONDS)).isEqualTo("done");
            } finally {
                gate.release.countDown();
            }
        });
    }

    @Test
    void closeGivesUpOnAStuckOperationAfterTheShutdownTimeout() {
        runner.withPropertyValues("spring.lifecycle.timeout-per-shutdown-phase=200ms").run(context -> {
            Gate gate = context.getBean(Gate.class);
            BlockingOperation blocking = context.getBean(BlockingOperation.class);
            OperationFixtures.GetBookOperation probe = context.getBean(OperationFixtures.GetBookOperation.class);
            CompletableFuture<String> running = CompletableFuture.supplyAsync(() -> blocking.execute("x"));
            try {
                assertThat(gate.started.await(SECONDS, TimeUnit.SECONDS)).isTrue();
                long begin = System.nanoTime();

                CompletableFuture<Void> closing = CompletableFuture.runAsync(context::close);
                closing.get(2, TimeUnit.SECONDS);

                assertThat(Duration.ofNanos(System.nanoTime() - begin)).isLessThan(Duration.ofSeconds(2));
                assertThat(running).isNotDone();
                assertThatThrownBy(() -> probe.execute(new OperationFixtures.GetBookRequest("a")))
                        .isInstanceOfSatisfying(SynapseException.class,
                                failure -> assertThat(failure.details()).isEqualTo(SynapseErrors.UNAVAILABLE));
            } finally {
                gate.release.countDown();
                running.get(SECONDS, TimeUnit.SECONDS);
            }
        });
    }

    /** Waits until the drain has begun, which is when a call without a context starts to be rejected. */
    private static void awaitDraining(OperationFixtures.GetBookOperation probe) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SECONDS);
        while (System.nanoTime() < deadline) {
            try {
                probe.execute(new OperationFixtures.GetBookRequest("a"));
            } catch (SynapseException failure) {
                if (failure.details() == SynapseErrors.UNAVAILABLE) {
                    return;
                }
                throw failure;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("The drain did not begin");
    }

    /** What the blocking operation shares with the test. */
    static final class Gate {

        final CountDownLatch started = new CountDownLatch(1);

        final CountDownLatch release = new CountDownLatch(1);
    }

    /** A query named {@code blocking} that signals when it runs, then waits to be released. */
    static class BlockingOperation implements QueryOperation<String, String> {

        private final Gate gate;

        BlockingOperation(Gate gate) {
            this.gate = gate;
        }

        @Override
        public String execute(String input) {
            gate.started.countDown();
            try {
                gate.release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "done";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Operations {

        @Bean
        Gate gate() {
            return new Gate();
        }

        @Bean
        BlockingOperation blockingOperation(Gate gate) {
            return new BlockingOperation(gate);
        }

        @Bean
        OperationFixtures.GetBookOperation getBookOperation() {
            return new OperationFixtures.GetBookOperation();
        }
    }
}
