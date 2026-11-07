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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycleEvent;
import org.springframework.grpc.server.lifecycle.GrpcServerShutdownEvent;
import org.springframework.grpc.server.lifecycle.GrpcServerStartedEvent;

import io.americanexpress.synapse.api.grpc.fixture.proto.CatalogServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.GetItemRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.Item;
import io.americanexpress.synapse.core.lifecycle.SynapseLifecyclePhases;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Closes a context whose Netty gRPC server has calls in flight. Each test starts and closes its own context, so the
 * shared test context cache is never closed under other tests.
 */
class GrpcGracefulShutdownIT {

    private static final int IN_FLIGHT = 3;

    private static final long TIMEOUT_SECONDS = 20;

    private static ConfigurableApplicationContext start(ServerEvents events) {
        return new SpringApplicationBuilder(TestApplication.class)
                .profiles("test")
                .properties("spring.grpc.server.port=0", "management.logging.export.otlp.enabled=false",
                        "management.tracing.export.otlp.enabled=false",
                        "management.otlp.metrics.export.enabled=false")
                .listeners(events)
                .run();
    }

    private static Item getItem(int port, String id) throws InterruptedException {
        ManagedChannel channel = Grpc
                .newChannelBuilderForAddress("localhost", port, InsecureChannelCredentials.create())
                .build();
        try {
            return CatalogServiceGrpc
                    .newBlockingStub(channel)
                    .withDeadlineAfter(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .getItem(GetItemRequest.newBuilder().setId(id).build());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void inFlightCallsCompleteAndNewOnesAreRejected() throws Exception {
        ServerEvents events = new ServerEvents();
        ConfigurableApplicationContext context = start(events);
        Gate gate = context.getBean(Gate.class);
        ExecutorService clients = Executors.newFixedThreadPool(IN_FLIGHT + 1);
        try {
            int port = events.port();
            List<CompletableFuture<Item>> inFlight = new ArrayList<>();
            for (int i = 0; i < IN_FLIGHT; i++) {
                String id = "in-flight-" + i;
                inFlight.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return getItem(port, id);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }, clients));
            }
            assertThat(gate.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("all calls in the operation").isTrue();

            CompletableFuture<Void> closed = CompletableFuture.runAsync(context::close, clients);
            Server server = events.awaitShutdown();
            // Spring gRPC publishes the event just before it calls shutdown(), so wait for the call itself.
            await("the server began its shutdown")
                    .atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .pollInterval(Duration.ofMillis(10))
                    .until(server::isShutdown);

            // close() began and waits for the calls in flight: a new call is refused.
            StatusRuntimeException refused = null;
            try {
                getItem(port, "late");
            } catch (StatusRuntimeException e) {
                refused = e;
            }
            assertThat(refused).as("the late call failed").isNotNull();
            assertThat(Objects.requireNonNull(refused).getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
            assertThat(closed).as("close() waits for the calls in flight").isNotDone();
            assertThat(inFlight).noneMatch(CompletableFuture::isDone);

            gate.release.countDown();

            for (int i = 0; i < IN_FLIGHT; i++) {
                assertThat(inFlight.get(i).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getId()).isEqualTo("in-flight-" + i);
            }
            closed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(context.isActive()).isFalse();
            assertThat(gate.ran).hasValue(IN_FLIGHT);
        } finally {
            gate.release.countDown();
            context.close();
            clients.shutdownNow();
        }
    }

    @Test
    void phasesMatchBoot() {
        ServerEvents events = new ServerEvents();
        try (ConfigurableApplicationContext context = start(events)) {
            GrpcServerLifecycle lifecycle = context.getBean("nettyGrpcServerLifecycle", GrpcServerLifecycle.class);

            assertThat(lifecycle.getPhase()).isEqualTo(Integer.MAX_VALUE).isGreaterThan(SynapseLifecyclePhases.INBOUND);
            assertThat(SynapseLifecyclePhases.INBOUND).isGreaterThan(SynapseLifecyclePhases.DRAIN);
        }
    }

    /** The server's start (with its port) and the start of its shutdown. */
    static final class ServerEvents implements ApplicationListener<GrpcServerLifecycleEvent> {

        private final CompletableFuture<Integer> port = new CompletableFuture<>();

        private final CompletableFuture<Server> shutdown = new CompletableFuture<>();

        @Override
        public void onApplicationEvent(GrpcServerLifecycleEvent event) {
            if (event instanceof GrpcServerStartedEvent started) {
                port.complete(started.getPort());
            } else if (event instanceof GrpcServerShutdownEvent) {
                shutdown.complete(event.getServer());
            }
        }

        int port() throws Exception {
            return port.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        Server awaitShutdown() throws Exception {
            return shutdown.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** Holds every call in the operation until released. */
    static final class Gate {

        final CountDownLatch entered = new CountDownLatch(IN_FLIGHT);

        final CountDownLatch release = new CountDownLatch(1);

        final AtomicInteger ran = new AtomicInteger();
    }

    /** Blocks until the gate is released, then echoes the id. */
    static class BlockingOperation implements QueryOperation<String, String> {

        private final Gate gate;

        BlockingOperation(Gate gate) {
            this.gate = gate;
        }

        @Override
        public String execute(String input) {
            gate.entered.countDown();
            try {
                if (!gate.release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the gate was never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            gate.ran.incrementAndGet();
            return input;
        }
    }

    /** A hand-written service whose {@code GetItem} calls the blocking operation. */
    static class BlockingService extends CatalogServiceGrpc.CatalogServiceImplBase {

        private final BlockingOperation operation;

        BlockingService(BlockingOperation operation) {
            this.operation = operation;
        }

        @Override
        public void getItem(GetItemRequest request, StreamObserver<Item> responseObserver) {
            responseObserver.onNext(Item.newBuilder().setId(operation.execute(request.getId())).build());
            responseObserver.onCompleted();
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApplication {

        @Bean
        Gate gate() {
            return new Gate();
        }

        @Bean
        BlockingOperation blockingOperation(Gate gate) {
            return new BlockingOperation(gate);
        }

        @Bean
        BlockingService blockingService(BlockingOperation operation) {
            return new BlockingService(operation);
        }
    }
}
