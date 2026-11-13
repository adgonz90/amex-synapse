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
package ${package}.api.grpc;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.StringValue;
import com.google.rpc.BadRequest;
import com.google.rpc.ErrorInfo;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.grpc.client.ImportGrpcClients;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.test.grpc.SynapseGrpcTest;

import ${package}.contract.proto.CreateItemRequest;
import ${package}.contract.proto.GetItemRequest;
import ${package}.contract.proto.Item;
import ${package}.contract.proto.ItemServiceGrpc;
import ${package}.operation.CreateItemOperation;
import ${package}.operation.GetItemOperation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

/**
 * The gRPC slice: the bindings and the gRPC edge on Boot's in-process transport, with every operation mocked. It
 * checks how the messages map to the operations' records (the etag included) and how errors map to status codes and
 * error details; the answers both protocols must give alike are the scenarios of {@code ItemScenarios}. The proto
 * messages and the contract's records share names, so the records are written with their package
 * ({@code contract.Item}).
 */
@SynapseGrpcTest
@ImportGrpcClients(types = ItemServiceGrpc.ItemServiceBlockingStub.class)
class ItemGrpcTest {

    private static final UUID ID = UUID.fromString("019a5e3c-4f00-7000-8000-000000000001");

    private static final ${package}.contract.Item LAMP = new ${package}.contract.Item(ID, "Lamp", "A desk lamp", 1);

    @Autowired
    ItemServiceGrpc.ItemServiceBlockingStub stub;

    @MockitoBean
    CreateItemOperation createItem;

    @MockitoBean
    GetItemOperation getItem;

    @Test
    void getItemMapsTheRecord() {
        given(getItem.execute(new ${package}.contract.GetItemRequest(ID)))
                .willReturn(Optional.of(LAMP), Optional.of(new ${package}.contract.Item(ID, "Lamp", null, 2)));

        Item item = items().getItem(get(ID));
        Item withoutDescription = items().getItem(get(ID));

        assertThat(item.getId()).isEqualTo(ID.toString());
        assertThat(item.getName()).isEqualTo("Lamp");
        // A non-null String sets the StringValue wrapper; null leaves it unset, as REST's null.
        assertThat(item.hasDescription()).isTrue();
        assertThat(item.getDescription().getValue()).isEqualTo("A desk lamp");
        assertThat(withoutDescription.hasDescription()).isFalse();
        assertThat(item.getVersion()).isEqualTo(1);
        // .etag(Item::version): the token REST sends as the ETag header, with its double quotes.
        assertThat(item.getEtag()).isEqualTo("\"1\"");
        assertThat(withoutDescription.getEtag()).isEqualTo("\"2\"");
    }

    @Test
    void unknownItemIsNotFoundWithTheItemCode() {
        given(getItem.execute(any())).willReturn(Optional.empty());

        StatusRuntimeException e = catchThrowableOfType(StatusRuntimeException.class, () -> items().getItem(get(ID)));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        ErrorInfo info = detail(e, ErrorInfo.class);
        assertThat(info.getReason()).isEqualTo("ITEM_NOT_FOUND");
        // The domain is spring.application.name.
        assertThat(info.getDomain()).isEqualTo("${serviceName}");
        assertThat(info.getMetadataMap()).containsKey("correlationId");
    }

    @Test
    void createMapsTheRequestAndCarriesTheEtag() {
        given(createItem.execute(any())).willReturn(LAMP);

        Item created = items()
                .createItem(CreateItemRequest
                        .newBuilder()
                        .setName("Lamp")
                        .setDescription(StringValue.of("A desk lamp"))
                        .build());
        items().createItem(CreateItemRequest.newBuilder().setName("Desk").build());

        // A create carries the new item's etag, as REST's 201 carries its ETag.
        assertThat(created.getEtag()).isEqualTo("\"1\"");
        then(createItem).should().execute(new ${package}.contract.CreateItemRequest("Lamp", "A desk lamp"));
        // An unset description is null.
        then(createItem).should().execute(new ${package}.contract.CreateItemRequest("Desk", null));
    }

    @Test
    void anIdThatIsNotAUuidIsInvalidArgumentOnTheField() {
        // The edge converts the field to the record's UUID before any operation runs. (The record's constraints,
        // such as a blank name, are checked by the operation pipeline: ItemOperationsTest and the scenarios.)
        StatusRuntimeException e = catchThrowableOfType(StatusRuntimeException.class,
                () -> items().getItem(GetItemRequest.newBuilder().setId("not-a-uuid").build()));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(detail(e, ErrorInfo.class).getReason()).isEqualTo("VALIDATION_FAILED");
        assertThat(detail(e, BadRequest.class).getFieldViolationsList())
                .extracting(BadRequest.FieldViolation::getField)
                .containsExactly("id");
        then(getItem).shouldHaveNoInteractions();
    }

    @Test
    void duplicateNameIsAlreadyExists() {
        willThrow(new SynapseException(SynapseErrors.ALREADY_EXISTS)).given(createItem).execute(any());

        StatusRuntimeException e = catchThrowableOfType(StatusRuntimeException.class,
                () -> items().createItem(CreateItemRequest.newBuilder().setName("Lamp").build()));

        // Synapse's ALREADY_EXISTS (the table's unique constraint) has its own status, where another CONFLICT code is
        // ABORTED. application-test.yaml turns synapse.errors.include-detail on, so the description is its message.
        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.ALREADY_EXISTS);
        assertThat(e.getStatus().getDescription()).isEqualTo("The resource already exists");
        assertThat(detail(e, ErrorInfo.class).getReason()).isEqualTo("ALREADY_EXISTS");
    }

    private ItemServiceGrpc.ItemServiceBlockingStub items() {
        return stub.withDeadlineAfter(10, TimeUnit.SECONDS);
    }

    private static GetItemRequest get(UUID id) {
        return GetItemRequest.newBuilder().setId(id.toString()).build();
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
