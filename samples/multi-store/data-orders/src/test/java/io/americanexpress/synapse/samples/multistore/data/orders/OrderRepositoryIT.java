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
package io.americanexpress.synapse.samples.multistore.data.orders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import jakarta.persistence.EntityManager;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.americanexpress.synapse.core.error.ExceptionTranslator;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.test.data.StoreConnection;
import io.americanexpress.synapse.test.data.SynapseContainers;
import io.americanexpress.synapse.test.data.SynapseDataTest;
import io.americanexpress.synapse.test.operation.OperationInvoker;
import io.americanexpress.synapse.test.operation.SynapseOperations;
import io.americanexpress.synapse.test.time.MutableClock;

import static io.americanexpress.synapse.test.assertj.SynapseAssertions.assertThatSynapseException;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The orders store on PostgreSQL (Docker): {@code @SynapseDataTest("orders")} starts the store alone, migrated by its
 * Flyway scripts and validated against its entity, and runs each test in an orders transaction that is rolled back.
 * {@code @StoreConnection} connects the store to a container instead of a {@code url}.
 */
@SynapseDataTest("orders")
@Tag("smoke")
class OrderRepositoryIT {

    /** One container per store: the ledger module's tests use their own, under the key "ledger". */
    @StoreConnection("orders")
    static final PostgreSQLContainer ORDERS = SynapseContainers.postgres("orders");

    @Autowired
    private OrderRepository repository;

    @Autowired
    private ApplicationContext context;

    /** The slice's clock, fixed at 2026-01-01T00:00:00Z: ids and audit stamps follow it. */
    @Autowired
    private MutableClock clock;

    @Autowired
    @Qualifier("orders")
    private EntityManager entityManager;

    @Autowired
    @Qualifier("orders")
    private JdbcClient jdbc;

    /** Synapse's translators of data access failures, as the operation pipeline of a service has them. */
    @Autowired
    @Qualifier("synapseDataExceptionTranslator")
    private ExceptionTranslator dataTranslator;

    @Autowired
    @Qualifier("synapseJpaExceptionTranslator")
    private ExceptionTranslator jpaTranslator;

    @Test
    void auditingAndUuidV7FromTheClock() {
        Instant placed = Instant.parse("2026-11-07T10:15:30.123456Z");
        clock.set(placed);

        StoredOrder order = repository.saveAndFlush(new StoredOrder("Ada", new BigDecimal("12.50")));

        UUID id = Objects.requireNonNull(order.getId());
        assertThat(id.version()).isEqualTo(7);
        // A UUIDv7 starts with its creation time in milliseconds: the clock's.
        assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(placed.toEpochMilli());
        assertThat(order.getVersion()).isZero();
        assertThat(order.getCreatedAt()).isEqualTo(placed);
        assertThat(order.getModifiedAt()).isEqualTo(placed);
        // No principal signed in: Synapse records synapse.data.auditing.system-user, "system" by default.
        assertThat(order.getCreatedBy()).isEqualTo("system");
        assertThat(order.getModifiedBy()).isEqualTo("system");
        assertThat(jdbc
                .sql("SELECT created_at FROM orders WHERE id = ?")
                .param(id)
                .query(Instant.class)
                .single()).isEqualTo(placed);
    }

    /**
     * The row changes after this copy of the order was read (here a JDBC update stands in for another instance's
     * write), so saving the copy finds another version than the one it read: Synapse's translators make that
     * {@code STALE_VERSION}, as the operation pipeline of the service does.
     */
    @Test
    void staleWriteIsStaleVersion() {
        StoredOrder order = repository.saveAndFlush(new StoredOrder("Grace", new BigDecimal("20.00")));
        UUID id = Objects.requireNonNull(order.getId());
        entityManager.clear();
        StoredOrder copy = repository.findById(id).orElseThrow();
        entityManager.detach(copy);
        assertThat(jdbc.sql("UPDATE orders SET version = version + 1 WHERE id = ?").param(id).update()).isOne();

        OperationInvoker invoker = SynapseOperations
                .with()
                .translator(dataTranslator)
                .translator(jpaTranslator)
                .invoker();

        assertThatSynapseException(() -> invoker.invoke(new SaveOrder(repository), copy))
                .hasCode(SynapseErrors.STALE_VERSION);
    }

    /**
     * The module's own {@code META-INF/synapse/config.yaml} applies to its tests as it is. (That the slice starts this
     * store alone when another is present is checked in service-orders, which has both data modules.)
     */
    @Test
    void theModuleFileConfiguresTheStore() {
        HikariDataSource pool = context.getBean("ordersDataSource", HikariDataSource.class);

        assertThat(pool.getPoolName()).isEqualTo("orders");
        assertThat(pool.getMaximumPoolSize()).isEqualTo(5);
    }

    /** Saves an order: the repository call of the test, run through the operation pipeline. */
    static class SaveOrder implements CommandOperation<StoredOrder, StoredOrder> {

        private final OrderRepository repository;

        SaveOrder(OrderRepository repository) {
            this.repository = repository;
        }

        @Override
        public StoredOrder execute(StoredOrder order) {
            return repository.saveAndFlush(order);
        }
    }
}
