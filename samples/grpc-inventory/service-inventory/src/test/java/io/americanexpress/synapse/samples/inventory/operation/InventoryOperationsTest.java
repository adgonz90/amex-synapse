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
package io.americanexpress.synapse.samples.inventory.operation;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;
import io.americanexpress.synapse.core.paging.SortOrder;
import io.americanexpress.synapse.samples.inventory.contract.AdjustStockRequest;
import io.americanexpress.synapse.samples.inventory.contract.CreateItemRequest;
import io.americanexpress.synapse.samples.inventory.contract.DeleteItemRequest;
import io.americanexpress.synapse.samples.inventory.contract.Discount;
import io.americanexpress.synapse.samples.inventory.contract.GetItemRequest;
import io.americanexpress.synapse.samples.inventory.contract.InventoryErrors;
import io.americanexpress.synapse.samples.inventory.contract.Item;
import io.americanexpress.synapse.samples.inventory.contract.ItemStatus;
import io.americanexpress.synapse.samples.inventory.contract.ListItemsRequest;
import io.americanexpress.synapse.samples.inventory.contract.Location;
import io.americanexpress.synapse.samples.inventory.contract.Money;
import io.americanexpress.synapse.samples.inventory.data.InMemoryItems;
import io.americanexpress.synapse.test.operation.SynapseOperationTest;
import io.americanexpress.synapse.test.time.MutableClock;

import static io.americanexpress.synapse.test.assertj.SynapseAssertions.assertThatSynapseException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The operations with the real pipeline (validation, error translation), without a server. The slice leaves out every
 * other component, so the test adds the in-memory store; it registers a {@link MutableClock}, which the operations
 * read the time from. The tests share the store and the clock (one Spring context), so each one creates the items it
 * changes and sets the time it depends on.
 */
@SynapseOperationTest
@Import(InMemoryItems.class)
class InventoryOperationsTest {

    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");

    private static final Location SHELF = new Location("east", "A1", 3);

    @Autowired
    MutableClock clock;

    @Autowired
    GetItemOperation getItem;

    @Autowired
    CreateItemOperation createItem;

    @Autowired
    ListItemsOperation listItems;

    @Autowired
    AdjustStockOperation adjustStock;

    @Autowired
    DeleteItemOperation deleteItem;

    @BeforeEach
    void setTime() {
        clock.set(NOW);
    }

    @Test
    void createsAnItemAtVersionOneAndReadsIt() {
        Item created = createItem
                .execute(new CreateItemRequest("NEW-001", List.of("new"), Map.of("size", "L"), SHELF, 12,
                        Duration.ofDays(2), 4, new Money("USD", new BigDecimal("9.50")), null, "grpc"));

        assertThat(created.status()).isEqualTo(ItemStatus.ACTIVE);
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.createdVia()).isEqualTo("grpc");
        // Stock on hand counts as a restock at creation.
        assertThat(created.restockedAt()).isEqualTo(NOW);
        assertThat(created.price()).isEqualTo(new Money("USD", new BigDecimal("9.50")));
        assertThat(getItem.execute(new GetItemRequest(created.id()))).contains(created);
    }

    @Test
    void anItemCreatedWithoutStockWasNeverRestocked() {
        Item created = create("NEW-002", 0, null);

        assertThat(created.restockedAt()).isNull();
        assertThat(created.price()).isNull();
    }

    @Test
    void thePipelineValidatesTheRequestBeforeTheOperationRuns() {
        assertThatSynapseException(() -> createItem
                .execute(new CreateItemRequest(" ", List.of(), Map.of(), new Location("", "A1", 0), -1, null, null,
                        null, null, null)))
                .hasCode(SynapseErrors.VALIDATION_FAILED)
                .hasCategory(ErrorCategory.INVALID_INPUT)
                .hasViolation("sku", "must not be blank")
                .hasViolation("location.warehouse", "must not be blank")
                .hasViolation("location.bin", "must be greater than 0")
                .hasViolation("quantity", "must be greater than or equal to 0");
    }

    @Test
    void addingStockRestocksAndIncrementsTheVersion() {
        Item created = create("ADJ-001", 3, null);
        clock.advance(Duration.ofHours(1));

        Item adjusted = adjustStock.execute(new AdjustStockRequest(created.id(), 5));

        assertThat(adjusted.quantity()).isEqualTo(8);
        assertThat(adjusted.restockedAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(adjusted.version()).isEqualTo(2);
        assertThat(getItem.execute(new GetItemRequest(created.id()))).contains(adjusted);
    }

    @Test
    void removingStockKeepsTheRestockTime() {
        Item created = create("ADJ-002", 3, null);
        clock.advance(Duration.ofHours(1));

        Item adjusted = adjustStock.execute(new AdjustStockRequest(created.id(), -3));

        assertThat(adjusted.quantity()).isZero();
        assertThat(adjusted.restockedAt()).isEqualTo(NOW);
    }

    @Test
    void removingMoreThanIsInStockIsOutOfStock() {
        Item created = create("ADJ-003", 3, null);

        assertThatSynapseException(() -> adjustStock.execute(new AdjustStockRequest(created.id(), -4)))
                .hasCode(InventoryErrors.OUT_OF_STOCK)
                .hasCategory(ErrorCategory.CONFLICT);
        assertThat(getItem.execute(new GetItemRequest(created.id()))).contains(created);
    }

    @Test
    void aDeltaThatOverflowsTheQuantityIsInvalidInput() {
        Item created = create("ADJ-004", 3, null);

        // 3 + Integer.MAX_VALUE doesn't fit the quantity: an invalid request, not OUT_OF_STOCK.
        assertThatSynapseException(() -> adjustStock.execute(new AdjustStockRequest(created.id(), Integer.MAX_VALUE)))
                .hasCode(SynapseErrors.VALIDATION_FAILED)
                .hasCategory(ErrorCategory.INVALID_INPUT)
                .hasViolation("delta", "is too large for the quantity in stock");
        assertThat(getItem.execute(new GetItemRequest(created.id()))).contains(created);
    }

    @Test
    void aZeroDeltaChangesNothing() {
        Item created = create("ADJ-005", 3, null);

        Item adjusted = adjustStock.execute(new AdjustStockRequest(created.id(), 0));

        assertThat(adjusted).isEqualTo(created);
        assertThat(adjusted.version()).isEqualTo(1);
    }

    @Test
    void adjustingAnUnknownItemIsItemNotFound() {
        assertThatSynapseException(() -> adjustStock.execute(new AdjustStockRequest(UUID.randomUUID(), 1)))
                .hasCode(InventoryErrors.ITEM_NOT_FOUND)
                .hasCategory(ErrorCategory.NOT_FOUND);
    }

    @Test
    void deletesAnItemOnce() {
        Item created = create("DEL-001", 1, null);

        deleteItem.execute(new DeleteItemRequest(created.id()));

        assertThat(getItem.execute(new GetItemRequest(created.id()))).isEmpty();
        assertThatSynapseException(() -> deleteItem.execute(new DeleteItemRequest(created.id())))
                .hasCode(InventoryErrors.ITEM_NOT_FOUND);
    }

    @Test
    void listsOnlyTheStatusAskedFor() {
        PageResult<Item> discontinued = listItems
                .execute(new ListItemsRequest(ItemStatus.DISCONTINUED, PageQuery.first(20)));

        // The seed's gizmo is the only discontinued item; the tests create active ones.
        assertThat(discontinued.items()).extracting(Item::id).containsExactly(InMemoryItems.GIZMO);
        assertThat(discontinued.hasNext()).isFalse();
        assertThat(discontinued.total()).isEqualTo(1);
    }

    @Test
    void pagesBySkuOrByTheSortKeys() {
        List<SortOrder> byQuantity = List
                .of(new SortOrder(ListItemsRequest.SortKey.QUANTITY.name(), SortOrder.Direction.DESC));

        PageResult<Item> all = listItems.execute(new ListItemsRequest(null, PageQuery.ofOffset(0, 100, List.of())));
        PageResult<Item> first = listItems.execute(new ListItemsRequest(null, PageQuery.ofOffset(0, 2, byQuantity)));
        PageResult<Item> second = listItems.execute(new ListItemsRequest(null, PageQuery.ofOffset(2, 2, byQuantity)));

        // The default order is by SKU.
        assertThat(all.items()).extracting(Item::sku).isSorted();
        assertThat(all.hasNext()).isFalse();
        // By quantity, largest first: the pages continue each other.
        assertThat(first.items()).hasSize(2);
        assertThat(first.hasNext()).isTrue();
        assertThat(first.total()).isEqualTo(all.total());
        List<Integer> quantities = java.util.stream.Stream
                .concat(first.items().stream(), second.items().stream())
                .map(Item::quantity)
                .toList();
        assertThat(quantities).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        // The operation pages by offset and returns no cursor: the gRPC edge turns the next offset into a token.
        assertThat(first.nextCursor()).isNull();
    }

    @Test
    void aDiscountedPriceIsWhatTheBuyerPays() {
        Item gadget = getItem.execute(new GetItemRequest(InMemoryItems.GADGET)).orElseThrow();

        // 20% off USD 49.00. price() is whichever member of oneof price is set.
        assertThat(gadget.price()).isInstanceOf(Discount.class);
        assertThat(gadget.price().amountDue()).isEqualTo(new Money("USD", new BigDecimal("39.20")));
    }

    @Test
    void anItemHasAtMostOnePrice() {
        Money price = new Money("USD", BigDecimal.ONE);

        // The oneof price holds one member, so the contract's records do too: the request side and the output side.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CreateItemRequest("TWO-001", List.of(), Map.of(), SHELF, 0, null, null, price,
                        new Discount(price, 10), null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Item(UUID.randomUUID(), "TWO-002", ItemStatus.ACTIVE, List.of(), Map.of(), SHELF,
                        0, null, null, null, price, new Discount(price, 10), null, 1));
    }

    private Item create(String sku, int quantity, @Nullable Money price) {
        return createItem
                .execute(new CreateItemRequest(sku, List.of(), Map.of(), SHELF, quantity, null, null, price, null,
                        null));
    }
}
