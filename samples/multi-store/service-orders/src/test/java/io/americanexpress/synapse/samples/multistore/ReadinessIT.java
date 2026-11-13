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
package io.americanexpress.synapse.samples.multistore;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.americanexpress.synapse.test.data.StoreConnection;
import io.americanexpress.synapse.test.data.SynapseContainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Readiness with one store down. The orders store opts in to readiness ({@code health.readiness: true} in data-orders'
 * {@code config.yaml}); the ledger doesn't. Each nested class stops one of its own containers, so neither uses the
 * shared ones of {@link SynapseContainers#postgres(String)}, and each has its own context. Problem detail is off, as in
 * every deployed environment, so the 503s carry no message.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.endpoint.health.show-components=always", "synapse.errors.include-detail=false"})
@AutoConfigureRestTestClient
@ActiveProfiles("test")
class ReadinessIT {

    /** More placements at once than the orders store has connections (data-orders' config.yaml: 5). */
    private static final int PLACEMENTS = 6;

    @Nested
    class LedgerDown {

        @StoreConnection("orders")
        static final PostgreSQLContainer ORDERS = new PostgreSQLContainer(SynapseContainers.POSTGRES_IMAGE);

        @StoreConnection("ledger")
        static final PostgreSQLContainer LEDGER = new PostgreSQLContainer(SynapseContainers.POSTGRES_IMAGE);

        @Autowired
        private RestTestClient client;

        @Autowired
        @Qualifier("orders")
        private JdbcClient orders;

        @Autowired
        @Qualifier("ledger")
        private HikariDataSource ledgerPool;

        @LocalServerPort
        private int port;

        @Autowired
        @Qualifier("orders")
        private HikariDataSource ordersPool;

        /**
         * A placement holds an orders connection while it waits for a ledger one. data-ledger's config.yaml caps that
         * wait at 2 seconds, so with the ledger down more placements at once than the orders pool has connections all
         * fail quickly with 503 {@code UNAVAILABLE}, each order is rolled back, and the orders store keeps serving: a
         * read sent while the placements hold every orders connection answers once the first of them fails. With
         * Hikari's default (30 seconds), these placements take more than 30 seconds, and so does the read.
         */
        @Test
        void placementsFailFastAndRollBackWhileTheLedgerIsDown() throws Exception {
            int before = countOrders(orders);
            LEDGER.stop();
            // An outage the pool already knows about: its connections to the stopped database are gone, so every
            // placement below waits for a new one. A ledger connection that breaks in the middle of a placement is
            // the other case (LedgerStopsMidPlacement).
            ledgerPool.getHikariPoolMXBean().softEvictConnections();
            HttpClient http = HttpClient.newHttpClient();
            HttpRequest place = placement(port);

            long start = System.nanoTime();
            List<HttpResponse<String>> responses = new ArrayList<>();
            Duration read;
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<HttpResponse<String>>> calls = new ArrayList<>();
                for (int i = 0; i < PLACEMENTS; i++) {
                    calls.add(executor.submit(() -> http.send(place, HttpResponse.BodyHandlers.ofString())));
                }
                // Every orders connection is held by a placement that waits for the ledger.
                await("the placements hold every orders connection",
                        () -> ordersPool.getHikariPoolMXBean().getActiveConnections() == ordersPool
                                .getMaximumPoolSize());
                assertThat(calls).as("placements still waiting").anyMatch(call -> !call.isDone());
                long reading = System.nanoTime();
                client.get().uri("/orders/{id}", UUID.randomUUID()).exchange().expectStatus().isNotFound();
                read = Duration.ofNanos(System.nanoTime() - reading);
                for (Future<HttpResponse<String>> call : calls) {
                    responses.add(call.get());
                }
            }
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(read).as("a read during the placements").isLessThan(Duration.ofSeconds(5));
            assertThat(elapsed).as("%d placements with the ledger down", PLACEMENTS).isLessThan(Duration.ofSeconds(15));
            assertThat(responses).allSatisfy(ReadinessIT::assertUnavailable);
            assertThat(countOrders(orders)).isEqualTo(before);
        }

        @Test
        void ledgerDownKeepsReadinessUp() {
            expectReady(client);

            LEDGER.stop();

            // The ledger didn't opt in, so the service stays in rotation: the readiness group doesn't hold it...
            expectReady(client);
            client
                    .get()
                    .uri("/health")
                    .exchange()
                    .expectBody()
                    .jsonPath("$.components.db.components.ledger")
                    .doesNotExist()
                    .jsonPath("$.components.readinessState.status")
                    .isEqualTo("UP");
            // ...and the health endpoint shows the ledger down.
            client
                    .get()
                    .uri("/actuator/health")
                    .exchange()
                    .expectStatus()
                    .isEqualTo(503)
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo("DOWN")
                    .jsonPath("$.components.db.components.ledger.status")
                    .isEqualTo("DOWN")
                    .jsonPath("$.components.db.components.orders.status")
                    .isEqualTo("UP");
        }
    }

    /**
     * The ledger stops while placements are inside their ledger transaction: each ledger INSERT fails because the server
     * ended its session, and the rollback fails too, on the connection the pool then closed.
     */
    @Nested
    @ExtendWith(OutputCaptureExtension.class)
    class LedgerStopsMidPlacement {

        /** Fewer than either pool's connections (5 each), so every placement is in its ledger INSERT at once. */
        private static final int HELD = 3;

        @StoreConnection("orders")
        static final PostgreSQLContainer ORDERS = new PostgreSQLContainer(SynapseContainers.POSTGRES_IMAGE);

        @StoreConnection("ledger")
        static final PostgreSQLContainer LEDGER = new PostgreSQLContainer(SynapseContainers.POSTGRES_IMAGE);

        @Autowired
        @Qualifier("orders")
        private JdbcClient orders;

        @LocalServerPort
        private int port;

        /**
         * Each placement fails with 503 {@code UNAVAILABLE} without detail, once its connection breaks, and its order is
         * rolled back. The failure lines are WARN {@code UNAVAILABLE}, none {@code INTERNAL}.
         */
        @Test
        void placementsCutOffByTheLedgerStoppingFailWith503(CapturedOutput output) throws Exception {
            int before = countOrders(orders);
            HttpClient http = HttpClient.newHttpClient();
            HttpRequest place = placement(port);

            List<HttpResponse<String>> responses = new ArrayList<>();
            long stopped;
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
                    Connection ledger = DriverManager
                            .getConnection(LEDGER.getJdbcUrl(), LEDGER.getUsername(), LEDGER.getPassword());
                    Statement statement = ledger.createStatement()) {
                // Test-only: every ledger INSERT waits inside its transaction until the database stops.
                statement
                        .execute("CREATE FUNCTION hold_entry() RETURNS trigger LANGUAGE plpgsql AS $$"
                                + " BEGIN PERFORM pg_sleep(60); RETURN NEW; END $$");
                statement
                        .execute("CREATE TRIGGER hold_entry BEFORE INSERT ON ledger_entry FOR EACH ROW"
                                + " EXECUTE FUNCTION hold_entry()");
                List<Future<HttpResponse<String>>> calls = new ArrayList<>();
                for (int i = 0; i < HELD; i++) {
                    calls.add(executor.submit(() -> http.send(place, HttpResponse.BodyHandlers.ofString())));
                }
                await("every placement waits in its ledger INSERT", () -> held(statement) == HELD);
                stopped = System.nanoTime();
                // A graceful stop, as `docker compose stop` does: PostgreSQL ends every session (57P01) and closes its
                // sockets before it exits. Testcontainers' stop() kills the container, and without Docker's userland
                // proxy the client sockets of a killed container can hang instead of breaking.
                LEDGER.getDockerClient().stopContainerCmd(LEDGER.getContainerId()).withTimeout(30).exec();
                for (Future<HttpResponse<String>> call : calls) {
                    responses.add(call.get());
                }
            }

            assertThat(Duration.ofNanos(System.nanoTime() - stopped)).isLessThan(Duration.ofSeconds(15));
            assertThat(responses).hasSize(HELD).allSatisfy(ReadinessIT::assertUnavailable);
            assertThat(countOrders(orders)).isEqualTo(before);
            assertThat(output.getOut())
                    .contains(
                            "\"errorCategory\":\"UNAVAILABLE\",\"errorCode\":\"UNAVAILABLE\",\"operation\":\"place-order\"")
                    .doesNotContain("\"errorCode\":\"INTERNAL\"");
        }

        private static int held(Statement statement) {
            try (ResultSet held = statement
                    .executeQuery("SELECT COUNT(*) FROM pg_stat_activity WHERE wait_event = 'PgSleep'")) {
                return held.next() ? held.getInt(1) : 0;
            } catch (SQLException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    @Nested
    class OrdersDown {

        @StoreConnection("orders")
        static final PostgreSQLContainer ORDERS = new PostgreSQLContainer(SynapseContainers.POSTGRES_IMAGE);

        @StoreConnection("ledger")
        static final PostgreSQLContainer LEDGER = new PostgreSQLContainer(SynapseContainers.POSTGRES_IMAGE);

        @Autowired
        private RestTestClient client;

        @Test
        void ordersDownTakesReadinessDown() {
            expectReady(client);

            ORDERS.stop();

            // The orders store opted in: the service reports itself not ready.
            client
                    .get()
                    .uri("/health")
                    .exchange()
                    .expectStatus()
                    .isEqualTo(503)
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo("DOWN")
                    .jsonPath("$.components.db.components.orders.status")
                    .isEqualTo("DOWN");
        }
    }

    /** A placement for Ada, sent to the service's port. */
    static HttpRequest placement(int port) {
        return HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + "/orders"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"customer\": \"Ada\", \"amount\": 12.50}"))
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    /** A 503 {@code UNAVAILABLE} without detail or driver text. */
    static void assertUnavailable(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
        assertThat(response.body())
                .contains("\"code\":\"UNAVAILABLE\"")
                .doesNotContain("\"detail\"", "refused", "PSQLException", "Connection is closed");
    }

    static int countOrders(JdbcClient orders) {
        return orders.sql("SELECT COUNT(*) FROM orders").query(Integer.class).single();
    }

    /** Waits up to 10 seconds for the condition. */
    static void await(String description, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out: " + description);
            }
            Thread.sleep(20);
        }
    }

    /** {@code /health}, the readiness group on the server's port, answers 200 UP. */
    static void expectReady(RestTestClient client) {
        client.get().uri("/health").exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("UP");
    }
}
