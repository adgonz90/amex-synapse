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

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.zaxxer.hikari.HikariDataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.americanexpress.synapse.samples.multistore.data.orders.OrderRepository;
import io.americanexpress.synapse.test.data.StoreConnection;
import io.americanexpress.synapse.test.data.SynapseContainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.willAnswer;

/**
 * The whole service on a real server, with each store on its own PostgreSQL container (Docker), migrated by its own
 * Flyway at startup. The stores' settings come from the data modules' jars, as in every environment; only where to
 * connect comes from the containers ({@code @StoreConnection}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
@Tag("smoke")
class OrdersApplicationIT {

    @StoreConnection("orders")
    static final PostgreSQLContainer ORDERS = SynapseContainers.postgres("orders");

    @StoreConnection("ledger")
    static final PostgreSQLContainer LEDGER = SynapseContainers.postgres("ledger");

    @Autowired
    private RestTestClient client;

    @Autowired
    @Qualifier("orders")
    private JdbcClient orders;

    @Autowired
    @Qualifier("ledger")
    private JdbcClient ledger;

    @Autowired
    private ConfigurableEnvironment environment;

    @Autowired
    private ApplicationContext context;

    /** The real repository, spied on so that one test can change an order while a cancel is running. */
    @MockitoSpyBean
    private OrderRepository repository;

    @Test
    void placeWritesBothStores() {
        var placed = client
                .post()
                .uri("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"customer\": \"Ada\", \"amount\": 12.50}")
                .exchange()
                .expectStatus()
                .isCreated()
                .expectHeader()
                .valueEquals("ETag", "\"0\"")
                .returnResult(String.class);
        UUID id = idFrom(placed.getResponseHeaders().getFirst("Location"));

        assertThat(orders.sql("SELECT COUNT(*) FROM orders WHERE id = ?").param(id).query(Integer.class).single())
                .isOne();
        assertThat(ledger
                .sql("SELECT amount FROM ledger_entry WHERE order_id = ?")
                .param(id)
                .query(BigDecimal.class)
                .list()).containsExactly(new BigDecimal("12.50"));
    }

    @Test
    void placeRecordsTheSystemUser() {
        UUID id = place("Grace", "20.00");

        // No principal is signed in, so both stores record synapse.data.auditing.system-user ("system" by default).
        assertThat(orders.sql("SELECT created_by FROM orders WHERE id = ?").param(id).query(String.class).single())
                .isEqualTo("system");
        assertThat(ledger
                .sql("SELECT entry_created_by FROM ledger_entry WHERE order_id = ?")
                .param(id)
                .query(String.class)
                .single()).isEqualTo("system");
    }

    @Test
    void anInvalidOrderIsRejectedBeforeEitherStore() {
        int entries = ledger.sql("SELECT COUNT(*) FROM ledger_entry").query(Integer.class).single();

        client
                .post()
                .uri("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"customer\": \"Ada\", \"amount\": -1}")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("VALIDATION_FAILED")
                .jsonPath("$.violations[0].field")
                .isEqualTo("amount");
        assertThat(ledger.sql("SELECT COUNT(*) FROM ledger_entry").query(Integer.class).single()).isEqualTo(entries);
    }

    @Test
    void staleIfMatchIs412() {
        UUID id = place("Linus", "7.25");

        // The order is at version 0. A cancel of version 5 sent If-Match, so the stale version is 412, not 409.
        client
                .delete()
                .uri("/orders/{id}", id)
                .header("If-Match", "\"5\"")
                .exchange()
                .expectStatus()
                .isEqualTo(412)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("PRECONDITION_FAILED");
        assertThat(orders.sql("SELECT COUNT(*) FROM orders WHERE id = ?").param(id).query(Integer.class).single())
                .isOne();

        client.delete().uri("/orders/{id}", id).header("If-Match", "\"0\"").exchange().expectStatus().isNoContent();
        assertThat(orders.sql("SELECT COUNT(*) FROM orders WHERE id = ?").param(id).query(Integer.class).single())
                .isZero();
        client
                .get()
                .uri("/orders/{id}", id)
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("ORDER_NOT_FOUND");
    }

    @Test
    void eachStoreRanItsOwnMigrations() {
        String history = "SELECT script FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank";
        String tables = "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' "
                + "ORDER BY table_name";

        assertThat(orders.sql(history).query(String.class).list()).containsExactly("V1__orders.sql");
        assertThat(ledger.sql(history).query(String.class).list()).containsExactly("V1__ledger.sql");
        assertThat(orders.sql(tables).query(String.class).list()).containsExactly("flyway_schema_history", "orders");
        assertThat(ledger.sql(tables).query(String.class).list())
                .containsExactly("flyway_schema_history", "ledger_entry");
    }

    /**
     * Each data module's {@code META-INF/synapse/config.yaml} is a configuration source of the service, named after
     * the jar it came from (as the startup report lists it), and sets its store's pool. The service references
     * neither: being on the class path is enough. It reads the environment rather than the startup log, which a
     * context shared from Spring's test context cache doesn't write again.
     */
    @Test
    void moduleFilesFromTwoJarsLoad() {
        List<String> sources = environment.getPropertySources().stream().map(PropertySource::getName).toList();

        assertThat(sources)
                .anySatisfy(name -> assertThat(name)
                        .matches("Synapse module config \\[config\\.yaml\\] data-orders-[^ ]+\\.jar"))
                .anySatisfy(name -> assertThat(name)
                        .matches("Synapse module config \\[config\\.yaml\\] data-ledger-[^ ]+\\.jar"));
        assertThat(context.getBean("ordersDataSource", HikariDataSource.class).getMaximumPoolSize()).isEqualTo(5);
        assertThat(context.getBean("ledgerDataSource", HikariDataSource.class).getMaximumPoolSize()).isEqualTo(5);
    }

    /**
     * Another request changes the order after the cancel has read it and before it deletes it. JPA's {@code @Version}
     * makes the delete find no row at that version, which is {@code STALE_VERSION}, and the cancel sent
     * {@code If-Match}, so REST answers 412. The order stays, at the other request's version. (The other request is
     * a JDBC update, committed on its own connection from another thread while the cancel's transaction is open.)
     */
    @Test
    void aChangeBetweenTheCancelsReadAndItsDeleteIs412() {
        UUID id = place("Edsger", "3.00");
        willAnswer(invocation -> {
            // The spy delegates to the real repository through its default answer.
            Object found = Mockito
                    .mockingDetails(repository)
                    .getMockCreationSettings()
                    .getDefaultAnswer()
                    .answer(invocation);
            CompletableFuture
                    .runAsync(() -> orders
                            .sql("UPDATE orders SET version = version + 1 WHERE id = ?")
                            .param(id)
                            .update())
                    .join();
            return found;
        }).given(repository).findById(id);

        client
                .delete()
                .uri("/orders/{id}", id)
                .header("If-Match", "\"0\"")
                .exchange()
                .expectStatus()
                .isEqualTo(412)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("PRECONDITION_FAILED");
        assertThat(orders.sql("SELECT version FROM orders WHERE id = ?").param(id).query(Long.class).single())
                .isOne();
    }

    private UUID place(String customer, String amount) {
        var placed = client
                .post()
                .uri("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"customer\": \"" + customer + "\", \"amount\": " + amount + "}")
                .exchange()
                .expectStatus()
                .isCreated()
                .returnResult(String.class);
        return idFrom(placed.getResponseHeaders().getFirst("Location"));
    }

    private static UUID idFrom(@Nullable String location) {
        String path = Objects.requireNonNull(location, "Location");
        assertThat(path).startsWith("/orders/");
        return UUID.fromString(path.substring("/orders/".length()));
    }
}
