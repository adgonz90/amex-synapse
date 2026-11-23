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
package io.americanexpress.synapse.samples.inventory.api.grpc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.Any;
import com.google.protobuf.Int32Value;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import com.google.rpc.BadRequest;
import com.google.rpc.ErrorInfo;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.grpc.client.ImportGrpcClients;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.core.paging.SortOrder;
import io.americanexpress.synapse.samples.inventory.contract.InventoryErrors;
import io.americanexpress.synapse.samples.inventory.contract.ItemStatus;
import io.americanexpress.synapse.samples.inventory.contract.proto.AdjustStockRequest;
import io.americanexpress.synapse.samples.inventory.contract.proto.CreateItemRequest;
import io.americanexpress.synapse.samples.inventory.contract.proto.DeleteItemRequest;
import io.americanexpress.synapse.samples.inventory.contract.proto.Discount;
import io.americanexpress.synapse.samples.inventory.contract.proto.GetItemRequest;
import io.americanexpress.synapse.samples.inventory.contract.proto.InventoryServiceGrpc;
import io.americanexpress.synapse.samples.inventory.contract.proto.Item;
import io.americanexpress.synapse.samples.inventory.contract.proto.ListItemsRequest;
import io.americanexpress.synapse.samples.inventory.contract.proto.ListItemsResponse;
import io.americanexpress.synapse.samples.inventory.contract.proto.Location;
import io.americanexpress.synapse.samples.inventory.contract.proto.Money;
import io.americanexpress.synapse.samples.inventory.operation.AdjustStockOperation;
import io.americanexpress.synapse.samples.inventory.operation.CreateItemOperation;
import io.americanexpress.synapse.samples.inventory.operation.DeleteItemOperation;
import io.americanexpress.synapse.samples.inventory.operation.GetItemOperation;
import io.americanexpress.synapse.samples.inventory.operation.ListItemsOperation;
import io.americanexpress.synapse.test.grpc.SynapseGrpcTest;

import static io.americanexpress.synapse.samples.inventory.api.grpc.InventoryFixtures.ID;
import static io.americanexpress.synapse.samples.inventory.api.grpc.InventoryFixtures.RESTOCKED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;

/**
 * The gRPC slice: the bindings and the gRPC edge on Boot's in-process transport, with every bound operation mocked. It
 * checks how the messages map to the operations' records and how errors map to status codes and error details; the
 * operations themselves are tested in {@code InventoryOperationsTest}. The messages here are the generated classes;
 * the contract's records, which share their names, come from {@link InventoryFixtures} or are written with their
 * package.
 */
@SynapseGrpcTest
@ImportGrpcClients(types = InventoryServiceGrpc.InventoryServiceBlockingStub.class)
class InventoryGrpcTest {

    private static final Metadata.Key<String> CHANNEL = Metadata.Key.of("x-channel", Metadata.ASCII_STRING_MARSHALLER);

    @Autowired
    InventoryServiceGrpc.InventoryServiceBlockingStub stub;

    @MockitoBean
    GetItemOperation getItem;

    @MockitoBean
    CreateItemOperation createItem;

    @MockitoBean
    ListItemsOperation listItems;

    @MockitoBean
    AdjustStockOperation adjustStock;

    @MockitoBean
    DeleteItemOperation deleteItem;

    @Test
    void mapsEveryFieldKind() {
        given(getItem.execute(any()))
                .willReturn(Optional.of(InventoryFixtures.widget()), Optional.of(InventoryFixtures.bare()));
        given(createItem.execute(any())).willReturn(InventoryFixtures.widget());

        Item item = inventory().getItem(get(ID));

        assertThat(item.getId()).isEqualTo(ID.toString());
        // An enum by name, after the proto enum's prefix: ItemStatus.ACTIVE is STATUS_ACTIVE.
        assertThat(item.getStatus()).isEqualTo(Item.Status.STATUS_ACTIVE);
        assertThat(item.getTagsList()).containsExactly("hardware", "blue");
        assertThat(item.getAttributesMap()).containsExactly(entry("color", "blue"));
        assertThat(item.getLocation()).isEqualTo(location("east", "A1", 3));
        assertThat(item.getQuantity()).isEqualTo(40);
        // Instant to Timestamp and Duration to Duration, nanoseconds included.
        assertThat(item.getRestockedAt())
                .isEqualTo(Timestamp
                        .newBuilder()
                        .setSeconds(RESTOCKED.getEpochSecond())
                        .setNanos(RESTOCKED.getNano())
                        .build());
        assertThat(item.getLeadTime())
                .isEqualTo(com.google.protobuf.Duration.newBuilder().setSeconds(259_200).setNanos(500_000_000).build());
        // A non-null Integer sets the Int32Value wrapper; a String sets the StringValue.
        assertThat(item.hasReorderLevel()).isTrue();
        assertThat(item.getReorderLevel().getValue()).isEqualTo(10);
        assertThat(item.getCreatedVia().getValue()).isEqualTo("web");
        assertThat(item.getVersion()).isEqualTo(1);
        // .etag(Item::version): the version as an entity tag, with its double quotes.
        assertThat(item.getEtag()).isEqualTo("\"1\"");

        Item bare = inventory().getItem(get(ID));

        // A null component leaves its field unset: no Timestamp, Duration, wrapper or price.
        assertThat(bare.getStatus()).isEqualTo(Item.Status.STATUS_DISCONTINUED);
        assertThat(bare.hasRestockedAt()).isFalse();
        assertThat(bare.hasLeadTime()).isFalse();
        assertThat(bare.hasReorderLevel()).isFalse();
        assertThat(bare.hasCreatedVia()).isFalse();
        assertThat(bare.getPriceCase()).isEqualTo(Item.PriceCase.PRICE_NOT_SET);
        assertThat(bare.getTagsList()).isEmpty();
        assertThat(bare.getEtag()).isEqualTo("\"3\"");

        inventory()
                .createItem(newItem()
                        .addTags("new")
                        .putAttributes("size", "L")
                        .setQuantity(4)
                        .setLeadTime(com.google.protobuf.Duration.newBuilder().setSeconds(172_800).build())
                        .setReorderLevel(Int32Value.of(2))
                        .build());

        // The same kinds from the request into the operation's input record.
        io.americanexpress.synapse.samples.inventory.contract.CreateItemRequest request = createdRequests().getFirst();
        assertThat(request.tags()).containsExactly("new");
        assertThat(request.attributes()).containsExactly(entry("size", "L"));
        assertThat(request.location())
                .isEqualTo(new io.americanexpress.synapse.samples.inventory.contract.Location("east", "A1", 3));
        assertThat(request.quantity()).isEqualTo(4);
        assertThat(request.leadTime()).isEqualTo(java.time.Duration.ofDays(2));
        assertThat(request.reorderLevel()).isEqualTo(2);
    }

    @Test
    void oneofNeedsItsMapping() {
        given(getItem.execute(any()))
                .willReturn(Optional.of(InventoryFixtures.widget()), Optional.of(InventoryFixtures.discounted()));
        given(createItem.execute(any())).willReturn(InventoryFixtures.widget());

        Item fixed = inventory().getItem(get(ID));
        Item discounted = inventory().getItem(get(ID));

        // Item.fixedPrice and Item.discountedPrice are mapped to the members of oneof price; the one that is set wins.
        assertThat(fixed.getPriceCase()).isEqualTo(Item.PriceCase.FIXED);
        assertThat(fixed.getFixed()).isEqualTo(money("USD", "19.99"));
        assertThat(discounted.getPriceCase()).isEqualTo(Item.PriceCase.DISCOUNTED);
        assertThat(discounted.getDiscounted())
                .isEqualTo(Discount.newBuilder().setListPrice(money("USD", "49.00")).setPercentOff(20).build());

        inventory().createItem(newItem().setFixed(money("USD", "19.99")).build());
        inventory()
                .createItem(newItem()
                        .setDiscounted(Discount.newBuilder().setListPrice(money("USD", "49.00")).setPercentOff(20))
                        .build());

        // The member the caller set fills its component; the other is null.
        List<io.americanexpress.synapse.samples.inventory.contract.CreateItemRequest> requests = createdRequests();
        assertThat(requests.get(0).fixedPrice())
                .isEqualTo(new io.americanexpress.synapse.samples.inventory.contract.Money("USD",
                        new BigDecimal("19.99")));
        assertThat(requests.get(0).discountedPrice()).isNull();
        assertThat(requests.get(1).fixedPrice()).isNull();
        assertThat(requests.get(1).discountedPrice())
                .isEqualTo(new io.americanexpress.synapse.samples.inventory.contract.Discount(
                        new io.americanexpress.synapse.samples.inventory.contract.Money("USD", new BigDecimal("49.00")),
                        20));
    }

    @Test
    void renamedFieldUsesMap() {
        given(getItem.execute(any())).willReturn(Optional.of(InventoryFixtures.widget()));
        given(createItem.execute(any())).willReturn(InventoryFixtures.widget());

        // Item.sku is written to sku_code, and CreateItemRequest.sku is read from it (.map("sku_code", ...)).
        assertThat(inventory().getItem(get(ID)).getSkuCode()).isEqualTo("WID-001");
        inventory().createItem(newItem().setSkuCode("WID-777").build());

        assertThat(createdRequests().getFirst().sku()).isEqualTo("WID-777");
    }

    @Test
    void unknownItemIsNotFoundWithReason() {
        given(getItem.execute(any())).willReturn(Optional.empty());

        StatusRuntimeException e = catchThrowableOfType(StatusRuntimeException.class,
                () -> inventory().getItem(get(ID)));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        // application-test.yaml turns detail on. ITEM_NOT_FOUND's template needs the id, which the edge doesn't
        // have, so the message is NOT_FOUND's; the reason stays ITEM_NOT_FOUND.
        assertThat(e.getStatus().getDescription()).isEqualTo("The resource was not found");
        ErrorInfo info = detail(e, ErrorInfo.class);
        assertThat(info.getReason()).isEqualTo("ITEM_NOT_FOUND");
        // The domain is spring.application.name.
        assertThat(info.getDomain()).isEqualTo("inventory");
        assertThat(info.getMetadataMap()).containsKey("correlationId");
    }

    @Test
    void invalidRequestHasBadRequest() {
        // The edge reads the request first: a message field the input needs but the caller didn't set never reaches
        // the operation.
        StatusRuntimeException unread = catchThrowableOfType(StatusRuntimeException.class,
                () -> inventory().createItem(newItem().clearLocation().build()));

        assertThat(unread.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(detail(unread, ErrorInfo.class).getReason()).isEqualTo("VALIDATION_FAILED");
        assertThat(detail(unread, BadRequest.class).getFieldViolationsList())
                .singleElement()
                .satisfies(violation -> {
                    assertThat(violation.getField()).isEqualTo("location");
                    assertThat(violation.getDescription()).isEqualTo("must not be null");
                });
        then(createItem).shouldHaveNoInteractions();

        // Then the pipeline checks @NotBlank on CreateItemRequest.sku. This slice mocks the operation, so the mock
        // throws what the pipeline does (InventoryOperationsTest checks the real one); the edge names the proto field.
        willThrow(SynapseException
                .ofViolations(SynapseErrors.VALIDATION_FAILED, List.of(new Violation("sku", "must not be blank"))))
                .given(createItem)
                .execute(any());

        StatusRuntimeException blank = catchThrowableOfType(StatusRuntimeException.class,
                () -> inventory().createItem(newItem().setSkuCode(" ").build()));

        assertThat(blank.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(detail(blank, BadRequest.class).getFieldViolationsList())
                .singleElement()
                .satisfies(violation -> {
                    assertThat(violation.getField()).isEqualTo("sku_code");
                    assertThat(violation.getDescription()).isEqualTo("must not be blank");
                });
    }

    @Test
    void perOperationOverride() {
        willThrow(new SynapseException(InventoryErrors.OUT_OF_STOCK, ID, 3)).given(adjustStock).execute(any());
        // No other operation throws OUT_OF_STOCK: a mocked delete-item does, to show that the override in
        // application.yaml is scoped to adjust-stock.
        willThrow(new SynapseException(InventoryErrors.OUT_OF_STOCK, ID, 3)).given(deleteItem).execute(any());

        StatusRuntimeException adjusting = catchThrowableOfType(StatusRuntimeException.class, () -> inventory()
                .adjustStock(AdjustStockRequest.newBuilder().setId(ID.toString()).setDelta(-5).build()));
        StatusRuntimeException deleting = catchThrowableOfType(StatusRuntimeException.class,
                () -> inventory().deleteItem(DeleteItemRequest.newBuilder().setId(ID.toString()).build()));

        // synapse.api.grpc.operations.adjust-stock.errors.status.[OUT_OF_STOCK]=FAILED_PRECONDITION
        assertThat(adjusting.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(adjusting.getStatus().getDescription()).isEqualTo("Item " + ID + " has only 3 in stock");
        assertThat(detail(adjusting, ErrorInfo.class).getReason()).isEqualTo("OUT_OF_STOCK");
        // The category's status: CONFLICT is ABORTED.
        assertThat(deleting.getStatus().getCode()).isEqualTo(Status.Code.ABORTED);
        assertThat(detail(deleting, ErrorInfo.class).getReason()).isEqualTo("OUT_OF_STOCK");
    }

    @Test
    void channelComesFromMetadata() {
        given(createItem.execute(any())).willReturn(InventoryFixtures.widget());
        Metadata metadata = new Metadata();
        metadata.put(CHANNEL, "grpc");

        Item created = inventory()
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                .createItem(newItem().build());
        inventory().createItem(newItem().build());

        // synapse.api.grpc.context.metadata.channel=x-channel puts the metadata in the request context, and
        // .context(CreateItemRequest::channel, ...) binds it; without the metadata the component is null.
        List<io.americanexpress.synapse.samples.inventory.contract.CreateItemRequest> requests = createdRequests();
        assertThat(requests.get(0).channel()).isEqualTo("grpc");
        assertThat(requests.get(1).channel()).isNull();
        // A create carries the new item's etag.
        assertThat(created.getEtag()).isEqualTo("\"1\"");
    }

    @Test
    void listPagesWithTokens() {
        given(listItems.execute(any()))
                .willReturn(InventoryFixtures
                        .page(List.of(InventoryFixtures.numbered(5), InventoryFixtures.numbered(4)), true, 5),
                        InventoryFixtures
                                .page(List.of(InventoryFixtures.numbered(3), InventoryFixtures.numbered(2)), true, 5),
                        InventoryFixtures.page(List.of(InventoryFixtures.numbered(1)), false, 5));
        ListItemsRequest first = ListItemsRequest
                .newBuilder()
                .setStatus(Item.Status.STATUS_ACTIVE)
                .setPageSize(2)
                .setOrderBy("quantity desc")
                .build();

        ListItemsResponse page1 = inventory().listItems(first);
        ListItemsResponse page2 = inventory()
                .listItems(first.toBuilder().setPageToken(page1.getNextPageToken()).build());
        ListItemsResponse page3 = inventory()
                .listItems(first.toBuilder().setPageToken(page2.getNextPageToken()).build());

        assertThat(page1.getItemsList()).extracting(Item::getSkuCode).containsExactly("SKU-5", "SKU-4");
        assertThat(page1.getHasNext()).isTrue();
        assertThat(page1.getTotalSize()).isEqualTo(5);
        assertThat(page1.getNextPageToken()).isNotEmpty();
        assertThat(page2.getItemsList()).extracting(Item::getSkuCode).containsExactly("SKU-3", "SKU-2");
        assertThat(page3.getItemsList()).extracting(Item::getSkuCode).containsExactly("SKU-1");
        assertThat(page3.getHasNext()).isFalse();
        // The last page has no token.
        assertThat(page3.getNextPageToken()).isEmpty();
        // The operation pages by offset: each token carries the next one. order_by arrives as the constant names.
        ArgumentCaptor<io.americanexpress.synapse.samples.inventory.contract.ListItemsRequest> listed = ArgumentCaptor
                .forClass(io.americanexpress.synapse.samples.inventory.contract.ListItemsRequest.class);
        then(listItems).should(times(3)).execute(listed.capture());
        assertThat(listed.getAllValues()).extracting(request -> request.page().offset()).containsExactly(null, 2L, 4L);
        assertThat(listed.getAllValues()).allSatisfy(request -> {
            assertThat(request.status()).isEqualTo(ItemStatus.ACTIVE);
            assertThat(request.page().size()).isEqualTo(2);
            assertThat(request.page().sort()).containsExactly(new SortOrder("QUANTITY", SortOrder.Direction.DESC));
        });
    }

    @Test
    void missingSliceMockIsNamed() {
        EngineExecutionResults results = EngineTestKit
                .engine("junit-jupiter")
                .selectors(selectClass(WithoutAdjustStockMock.class))
                .configurationParameter("junit.jupiter.conditions.deactivate", "org.junit.*DisabledCondition")
                .execute();

        results.testEvents().assertStatistics(stats -> stats.started(1).failed(1));
        Throwable failure = results
                .testEvents()
                .failed()
                .stream()
                .map(event -> event.getRequiredPayload(TestExecutionResult.class).getThrowable().orElseThrow())
                .findFirst()
                .orElseThrow();
        assertThat(failure)
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("@SynapseGrpcTest: declare a @MockitoBean for each operation the bindings use; missing: "
                        + "AdjustStockOperation (bound by InventoryGrpcConfiguration.inventory)");
    }

    /**
     * A slice test that forgot one operation's mock. It fails before any bean is created, so it is launched only by
     * {@link #missingSliceMockIsNamed()}: Maven's surefire leaves nested classes out, and {@link Disabled} keeps an
     * IDE from running it.
     */
    @SynapseGrpcTest
    @Disabled("Launched by missingSliceMockIsNamed through the JUnit EngineTestKit: its context fails on purpose")
    static class WithoutAdjustStockMock {

        @MockitoBean
        GetItemOperation getItem;

        @MockitoBean
        CreateItemOperation createItem;

        @MockitoBean
        ListItemsOperation listItems;

        @MockitoBean
        DeleteItemOperation deleteItem;

        @Test
        void startsTheSlice() {
            // The context fails before this runs.
        }
    }

    private InventoryServiceGrpc.InventoryServiceBlockingStub inventory() {
        return stub.withDeadlineAfter(10, TimeUnit.SECONDS);
    }

    private List<io.americanexpress.synapse.samples.inventory.contract.CreateItemRequest> createdRequests() {
        ArgumentCaptor<io.americanexpress.synapse.samples.inventory.contract.CreateItemRequest> created = ArgumentCaptor
                .forClass(io.americanexpress.synapse.samples.inventory.contract.CreateItemRequest.class);
        then(createItem).should(org.mockito.Mockito.atLeastOnce()).execute(created.capture());
        return created.getAllValues();
    }

    private static GetItemRequest get(UUID id) {
        return GetItemRequest.newBuilder().setId(id.toString()).build();
    }

    /** A valid CreateItem request, without a price. */
    private static CreateItemRequest.Builder newItem() {
        // quantity is optional, so a required int is sent explicitly, 0 included: unsent, it is must not be null.
        return CreateItemRequest
                .newBuilder()
                .setSkuCode("WID-002")
                .setLocation(location("east", "A1", 3))
                .setQuantity(0);
    }

    private static Location location(String warehouse, String aisle, int bin) {
        return Location.newBuilder().setWarehouse(warehouse).setAisle(aisle).setBin(bin).build();
    }

    private static Money money(String currency, String amount) {
        return Money.newBuilder().setCurrencyCode(currency).setAmount(amount).build();
    }

    private static <T extends Message> T detail(StatusRuntimeException e, Class<T> type) {
        com.google.rpc.Status status = Objects.requireNonNull(StatusProto.fromThrowable(e), "status details");
        for (Any any : status.getDetailsList()) {
            if (any.is(type)) {
                try {
                    return any.unpack(type);
                } catch (InvalidProtocolBufferException ex) {
                    throw new AssertionError(ex);
                }
            }
        }
        throw new AssertionError("no " + type.getSimpleName() + " in " + status);
    }
}
