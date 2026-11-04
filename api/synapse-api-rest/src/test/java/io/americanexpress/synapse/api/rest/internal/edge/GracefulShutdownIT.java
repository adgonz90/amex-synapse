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
package io.americanexpress.synapse.api.rest.internal.edge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.core.lifecycle.SynapseLifecyclePhases;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Closes a running application while requests are in flight. Each test starts its own application on a random port,
 * because closing it is the point of the test.
 *
 * <p>
 * Boot's graceful shutdown of Tomcat pauses each connector and then closes its server socket, before it waits for the
 * active requests. A paused Tomcat endpoint destroys any connection it still accepts, and once the server socket is
 * closed new connections are refused. So once the connector reports itself paused, a request on a new connection is
 * never answered 200; the test waits for that state instead of sleeping.
 */
class GracefulShutdownIT {

    private static final int IN_FLIGHT = 3;

    @Test
    void inFlightRequestsCompleteAndNewOnesAreRejected() throws Exception {
        ConfigurableApplicationContext context = start();
        Gate gate = context.getBean(Gate.class);
        int port = port(context);
        CompletableFuture<Void> closing = null;
        List<CompletableFuture<HttpResponse<String>>> inFlight = new ArrayList<>();
        try (HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            for (int i = 0; i < IN_FLIGHT; i++) {
                inFlight
                        .add(http
                                .sendAsync(HttpRequest
                                        .newBuilder(URI.create("http://localhost:" + port + "/hold"))
                                        .timeout(Duration.ofSeconds(30))
                                        .build(), HttpResponse.BodyHandlers.ofString()));
            }
            assertThat(gate.entered.await(10, TimeUnit.SECONDS)).as("all requests are in flight").isTrue();

            closing = CompletableFuture.runAsync(context::close);
            awaitPaused(context);

            String answer = sendOnNewConnection(port);
            assertThat(answer).as("a request sent after shutdown began").doesNotStartWith("HTTP/1.1 200");
            assertThat(inFlight).noneMatch(CompletableFuture::isDone);

            gate.release.countDown();
            for (CompletableFuture<HttpResponse<String>> response : inFlight) {
                assertThat(response.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            }
            closing.get(30, TimeUnit.SECONDS);
            assertThat(context.isActive()).isFalse();
            assertThat(gate.holds.get()).isEqualTo(IN_FLIGHT);
        } finally {
            gate.release.countDown();
            if (closing == null) {
                context.close();
            }
        }
    }

    @Test
    void phasesMatchBoot() {
        try (ConfigurableApplicationContext context = start()) {
            // WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE is deprecated for removal since Boot 4.0;
            // WebServerApplicationContext holds the same values.
            assertThat(WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE)
                    .isEqualTo(SmartLifecycle.DEFAULT_PHASE - 1024);
            assertThat(WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE)
                    .isEqualTo(SynapseLifecyclePhases.INBOUND);
            // server.shutdown defaults to graceful: Boot registers the graceful shutdown lifecycle only then.
            SmartLifecycle gracefulShutdown = context.getBean("webServerGracefulShutdown", SmartLifecycle.class);
            assertThat(gracefulShutdown).isInstanceOf(WebServerGracefulShutdownLifecycle.class);
            assertThat(gracefulShutdown.getPhase()).isEqualTo(WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE);
            assertThat(context.getBean("webServerStartStop", SmartLifecycle.class).getPhase())
                    .isEqualTo(SynapseLifecyclePhases.INBOUND);
            // Stopping runs higher phases first: graceful shutdown, then the server stop, then the drain.
            assertThat(gracefulShutdown.getPhase()).isGreaterThan(SynapseLifecyclePhases.INBOUND);
            assertThat(SynapseLifecyclePhases.INBOUND).isGreaterThan(SynapseLifecyclePhases.DRAIN);
        }
    }

    private static ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(TestApplication.class)
                .properties("server.port=0", "management.logging.export.otlp.enabled=false",
                        "management.tracing.export.otlp.enabled=false", "management.otlp.metrics.export.enabled=false")
                .run();
    }

    private static int port(ConfigurableApplicationContext context) {
        return webServer(context).getPort();
    }

    private static WebServer webServer(ConfigurableApplicationContext context) {
        return Objects
                .requireNonNull(((ServletWebServerApplicationContext) context).getWebServer(),
                        "the web server is not running");
    }

    /** Waits until graceful shutdown has paused the connector; from then on it accepts no new request. */
    private static void awaitPaused(ConfigurableApplicationContext context) throws InterruptedException {
        TomcatWebServer server = (TomcatWebServer) webServer(context);
        Connector connector = server.getTomcat().getConnector();
        AbstractProtocol<?> protocol = (AbstractProtocol<?>) connector.getProtocolHandler();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!protocol.isPaused()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("graceful shutdown did not pause the connector");
            }
            Thread.sleep(10);
        }
    }

    /**
     * Sends a request on a new connection and returns the status line, or how the connection failed. A raw socket,
     * so no client retries or reuses a pooled connection.
     */
    private static String sendOnNewConnection(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 5_000);
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            out
                    .write("GET /hold HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String statusLine = in.readLine();
            return statusLine == null ? "closed without a response" : statusLine;
        } catch (ConnectException e) {
            return "connection refused";
        } catch (IOException e) {
            return "connection failed: " + e;
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {

        @Bean
        Gate gate() {
            return new Gate();
        }

        @Bean
        HoldOperation holdOperation(Gate gate) {
            return new HoldOperation(gate);
        }

        @Bean
        RestBindings holds(HoldOperation hold) {
            return RestBindings.builder().get("/hold", hold).build();
        }
    }

    /** Counts the calls and holds them until released. */
    static class Gate {

        final AtomicInteger holds = new AtomicInteger();

        final CountDownLatch entered = new CountDownLatch(IN_FLIGHT);

        final CountDownLatch release = new CountDownLatch(1);
    }

    record Held(int number) {
    }

    static class HoldOperation implements QueryOperation<Void, Held> {

        private final Gate gate;

        HoldOperation(Gate gate) {
            this.gate = gate;
        }

        @Override
        public Held execute(Void input) {
            int number = gate.holds.incrementAndGet();
            gate.entered.countDown();
            try {
                if (!gate.release.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the held request was not released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return new Held(number);
        }
    }
}
