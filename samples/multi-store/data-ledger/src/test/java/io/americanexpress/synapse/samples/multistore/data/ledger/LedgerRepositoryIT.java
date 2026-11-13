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
package io.americanexpress.synapse.samples.multistore.data.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.americanexpress.synapse.test.data.StoreConnection;
import io.americanexpress.synapse.test.data.SynapseContainers;
import io.americanexpress.synapse.test.data.SynapseDataTest;
import io.americanexpress.synapse.test.time.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ledger store on PostgreSQL (Docker): {@code @SynapseDataTest("ledger")} starts the store alone, migrated by its
 * Flyway scripts, and runs each test in a ledger transaction that is rolled back.
 */
@SynapseDataTest("ledger")
@Tag("smoke")
class LedgerRepositoryIT {

    /** One container per store: the orders module's tests use their own, under the key "orders". */
    @StoreConnection("ledger")
    static final PostgreSQLContainer LEDGER = SynapseContainers.postgres("ledger");

    @Autowired
    private LedgerRepository repository;

    /** The slice's clock, fixed at 2026-01-01T00:00:00Z: audit stamps follow it. */
    @Autowired
    private MutableClock clock;

    @Autowired
    @Qualifier("ledger")
    private JdbcClient jdbc;

    @Test
    void customAuditColumnsAreFilled() {
        Instant recorded = Instant.parse("2026-11-07T10:15:30.123456Z");
        clock.set(recorded);
        UUID orderId = UUID.randomUUID();

        LedgerEntry entry = repository.save(new LedgerEntry(orderId, new BigDecimal("12.50")));

        assertThat(entry.getId()).isNotNull();
        assertThat(entry.getCreatedAt()).isEqualTo(recorded);
        // No principal signed in: Synapse records synapse.data.auditing.system-user, "system" by default.
        assertThat(entry.getCreatedBy()).isEqualTo("system");
        Map<String, Object> row = jdbc
                .sql("SELECT order_id, amount, entry_created_by, entry_modified_by FROM ledger_entry WHERE id = ?")
                .param(entry.getId())
                .query()
                .singleRow();
        assertThat(row)
                .containsEntry("order_id", orderId)
                .containsEntry("amount", new BigDecimal("12.50"))
                .containsEntry("entry_created_by", "system")
                .containsEntry("entry_modified_by", "system");
        assertThat(jdbc
                .sql("SELECT entry_created FROM ledger_entry WHERE id = ?")
                .param(entry.getId())
                .query(Instant.class)
                .single()).isEqualTo(recorded);
        assertThat(jdbc
                .sql("SELECT entry_modified FROM ledger_entry WHERE id = ?")
                .param(entry.getId())
                .query(Instant.class)
                .single()).isEqualTo(recorded);
        assertThat(repository.findByOrderId(orderId)).extracting(LedgerEntry::getId).containsExactly(entry.getId());
    }
}
