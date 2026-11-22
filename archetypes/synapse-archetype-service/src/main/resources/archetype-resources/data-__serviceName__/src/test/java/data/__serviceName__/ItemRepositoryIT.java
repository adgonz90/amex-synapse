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
package ${package}.data.${serviceName};

import java.util.Objects;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.ExceptionTranslator;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.test.data.StoreConnection;
import io.americanexpress.synapse.test.data.SynapseContainers;
import io.americanexpress.synapse.test.data.SynapseDataTest;
import io.americanexpress.synapse.test.operation.OperationInvoker;
import io.americanexpress.synapse.test.operation.SynapseOperations;

import static io.americanexpress.synapse.test.assertj.SynapseAssertions.assertThatSynapseException;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ${serviceName} store on PostgreSQL (Docker): {@code @SynapseDataTest("${serviceName}")} starts the store alone,
 * migrated by its Flyway scripts and validated against its entity, and runs each test in a ${serviceName} transaction
 * that is rolled back. {@code @StoreConnection} connects the store to a container instead of a {@code url}.
 */
@SynapseDataTest("${serviceName}")
class ItemRepositoryIT {

    /** The JVM's shared PostgreSQL container: this module has one store, so it needs no key of its own. */
    @StoreConnection("${serviceName}")
    static final PostgreSQLContainer STORE = SynapseContainers.postgres();

    @Autowired
    private ItemRepository repository;

    @Autowired
    @Qualifier("${serviceName}")
    private Flyway flyway;

    /** Synapse's translators of data access failures, as the operation pipeline of a service has them. */
    @Autowired
    @Qualifier("synapseDataExceptionTranslator")
    private ExceptionTranslator dataTranslator;

    @Autowired
    @Qualifier("synapseJpaExceptionTranslator")
    private ExceptionTranslator jpaTranslator;

    /**
     * The context started, so the schema matched {@link StoredItem}: the store validates its entities against the
     * schema at startup, after its Flyway ran. That schema is the one migration V1 made, and nothing is pending.
     */
    @Test
    void migratesAndValidates() {
        assertThat(flyway.info().applied())
                .extracting(MigrationInfo::getVersion)
                .extracting(MigrationVersion::getVersion)
                .containsExactly("1");
        assertThat(flyway.info().pending()).isEmpty();
    }

    /**
     * Two items with one name break the table's unique constraint when the second is flushed: Synapse's translators
     * make that {@code ALREADY_EXISTS} in the category {@code CONFLICT}, as the operation pipeline of the service does
     * (REST 409, gRPC {@code ALREADY_EXISTS}).
     */
    @Test
    void nameIsUnique() {
        repository.saveAndFlush(new StoredItem("Lamp", null));
        assertThat(repository.existsByName("Lamp")).isTrue();
        assertThat(repository.existsByName("Desk")).isFalse();

        OperationInvoker invoker = SynapseOperations
                .with()
                .translator(dataTranslator)
                .translator(jpaTranslator)
                .invoker();

        assertThatSynapseException(
                () -> invoker.invoke(new SaveItem(repository), new StoredItem("Lamp", "Another lamp")))
                .hasCode(SynapseErrors.ALREADY_EXISTS)
                .hasCategory(ErrorCategory.CONFLICT);
    }

    /** JPA's {@code @Version}: 0 when the item is added; the description may be null. */
    @Test
    void savesAtVersionZero() {
        StoredItem item = repository.saveAndFlush(new StoredItem("Chair", null));

        assertThat(item.getVersion()).isZero();
        assertThat(repository.findById(Objects.requireNonNull(item.getId())))
                .get()
                .extracting(StoredItem::getDescription)
                .isNull();
    }

    /** Saves an item: the repository call of the test, run through the operation pipeline. */
    static class SaveItem implements CommandOperation<StoredItem, StoredItem> {

        private final ItemRepository repository;

        SaveItem(ItemRepository repository) {
            this.repository = repository;
        }

        @Override
        public StoredItem execute(StoredItem item) {
            return repository.saveAndFlush(item);
        }
    }
}
