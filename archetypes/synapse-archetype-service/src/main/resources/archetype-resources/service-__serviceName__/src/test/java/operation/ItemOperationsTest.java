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
package ${package}.operation;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.data.jpa.IdEntity;
import io.americanexpress.synapse.data.jpa.VersionedEntity;
import io.americanexpress.synapse.test.operation.SynapseOperationTest;

import ${package}.contract.CreateItemRequest;
import ${package}.contract.GetItemRequest;
import ${package}.contract.Item;
import ${package}.data.${serviceName}.ItemRepository;
import ${package}.data.${serviceName}.StoredItem;

import static io.americanexpress.synapse.test.assertj.SynapseAssertions.assertThatSynapseException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/**
 * The operations with the real pipeline (validation, error translation), without a web server or a database: the
 * slice starts only the operations, so the store's repository is a mock. {@code ServiceJvmIT} runs them on
 * PostgreSQL.
 */
@SynapseOperationTest
class ItemOperationsTest {

    private static final UUID ID = UUID.fromString("019a5e3c-4f00-7000-8000-000000000001");

    @Autowired
    CreateItemOperation createItem;

    @Autowired
    GetItemOperation getItem;

    @MockitoBean
    ItemRepository items;

    @Test
    void createMapsAndSaves() {
        given(items.saveAndFlush(any())).willAnswer(invocation -> saved(invocation.getArgument(0), 0));

        Item created = createItem.execute(new CreateItemRequest("Lamp", "A desk lamp"));

        assertThat(created).isEqualTo(new Item(ID, "Lamp", "A desk lamp", 0));
        ArgumentCaptor<StoredItem> saved = ArgumentCaptor.forClass(StoredItem.class);
        then(items).should().saveAndFlush(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("Lamp");
        assertThat(saved.getValue().getDescription()).isEqualTo("A desk lamp");
    }

    @Test
    void thePipelineValidatesTheRequestBeforeTheOperationRuns() {
        assertThatSynapseException(() -> createItem.execute(new CreateItemRequest(" ", "d".repeat(1001))))
                .hasCode(SynapseErrors.VALIDATION_FAILED)
                .hasCategory(ErrorCategory.INVALID_INPUT)
                .hasViolation("name", "must not be blank")
                .hasViolation("description", "size must be between 0 and 1000");
        then(items).shouldHaveNoInteractions();
    }

    @Test
    void getMapsTheStoredItemOrNothing() {
        given(items.findById(ID)).willReturn(Optional.of(saved(new StoredItem("Lamp", null), 4)));

        assertThat(getItem.execute(new GetItemRequest(ID))).contains(new Item(ID, "Lamp", null, 4));
        assertThat(getItem.execute(new GetItemRequest(UUID.randomUUID()))).isEmpty();
    }

    /** The item with the id and version that JPA sets when it saves one. */
    private static StoredItem saved(StoredItem item, long version) {
        ReflectionTestUtils.setField(item, IdEntity.class, "id", ID, UUID.class);
        ReflectionTestUtils.setField(item, VersionedEntity.class, "version", version, Long.class);
        return item;
    }
}
