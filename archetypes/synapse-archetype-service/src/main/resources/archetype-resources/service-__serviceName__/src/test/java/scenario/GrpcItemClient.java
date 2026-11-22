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
package ${package}.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.StringValue;
import com.google.rpc.BadRequest;
import com.google.rpc.ErrorInfo;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.MetadataUtils;
import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.error.SynapseErrors;

import ${package}.contract.proto.CreateItemRequest;
import ${package}.contract.proto.GetItemRequest;
import ${package}.contract.proto.Item;
import ${package}.contract.proto.ItemServiceGrpc;
import ${package}.scenario.Outcome.ItemView;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The items over gRPC, with the stubs generated in contract-${serviceName} on a plaintext channel. It checks the status
 * of every failure against its code ({@code INVALID_ARGUMENT}, {@code NOT_FOUND}, {@code ALREADY_EXISTS}), and the
 * {@code etag} of every answer: set on a create and a read, as REST sends {@code ETag}. gRPC has no status for a
 * created item: a {@code CreateItem} response is {@link Outcome.Kind#CREATED}.
 */
public final class GrpcItemClient implements ItemClient {

    private static final Metadata.Key<String> CORRELATION = Metadata.Key
            .of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);

    private final ManagedChannel channel;

    /**
     * Creates the client.
     *
     * @param host the host of the gRPC server
     * @param port its port
     */
    public GrpcItemClient(String host, int port) {
        this.channel = Grpc.newChannelBuilderForAddress(host, port, InsecureChannelCredentials.create()).build();
    }

    @Override
    public Outcome create(String name, @Nullable String description) {
        CreateItemRequest.Builder request = CreateItemRequest.newBuilder().setName(name);
        if (description != null) {
            request.setDescription(StringValue.of(description));
        }
        return call(() -> {
            Item item = items().createItem(request.build());
            assertThat(item.getEtag()).as("etag of CreateItem").isNotEmpty();
            return Outcome.created(view(item), item.getEtag());
        });
    }

    @Override
    public Outcome get(UUID id) {
        return read(items(), id);
    }

    /** A GetItem on the stub. */
    private static Outcome read(ItemServiceGrpc.ItemServiceBlockingStub stub, UUID id) {
        return call(() -> {
            Item item = stub.getItem(GetItemRequest.newBuilder().setId(id.toString()).build());
            assertThat(item.getEtag()).as("etag of GetItem").isNotEmpty();
            return Outcome.ok(view(item), item.getEtag());
        });
    }

    @Override
    public Echo echoedCorrelationId(UUID id, String correlationId) {
        Metadata sent = new Metadata();
        sent.put(CORRELATION, correlationId);
        AtomicReference<@Nullable Metadata> headers = new AtomicReference<>();
        AtomicReference<@Nullable Metadata> trailers = new AtomicReference<>();
        Outcome outcome = read(items()
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(sent),
                        MetadataUtils.newCaptureMetadataInterceptor(headers, trailers)),
                id);
        // A failure answers with trailers only; they carry the correlation id too.
        Metadata received = headers.get();
        if (received != null && received.containsKey(CORRELATION)) {
            return new Echo(outcome, received.get(CORRELATION));
        }
        Metadata last = trailers.get();
        return new Echo(outcome, last == null ? null : last.get(CORRELATION));
    }

    @Override
    public void close() {
        channel.shutdownNow();
        try {
            channel.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ItemServiceGrpc.ItemServiceBlockingStub items() {
        return ItemServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
    }

    private static Outcome call(Supplier<Outcome> call) {
        try {
            return call.get();
        } catch (StatusRuntimeException e) {
            return failure(e);
        }
    }

    /** A failed call: its {@code ErrorInfo.reason}, its {@code BadRequest} fields and the status its code must have. */
    private static Outcome failure(StatusRuntimeException e) {
        com.google.rpc.Status status = StatusProto.fromThrowable(e);
        assertThat(status).as("status details of %s", e.getStatus()).isNotNull();
        String code = null;
        List<String> violations = new ArrayList<>();
        for (Any detail : Objects.requireNonNull(status).getDetailsList()) {
            try {
                if (detail.is(ErrorInfo.class)) {
                    code = detail.unpack(ErrorInfo.class).getReason();
                } else if (detail.is(BadRequest.class)) {
                    detail
                            .unpack(BadRequest.class)
                            .getFieldViolationsList()
                            .forEach(violation -> violations.add(violation.getField()));
                }
            } catch (InvalidProtocolBufferException ex) {
                throw new AssertionError(ex);
            }
        }
        assertThat(code).as("ErrorInfo of %s", e.getStatus()).isNotNull();
        Outcome failure = Outcome.failed(Objects.requireNonNull(code), violations);
        assertThat(e.getStatus().getCode()).as("status of %s", code).isEqualTo(status(Outcome.details(code)));
        return failure;
    }

    /** The status gRPC answers an error with. */
    private static Status.Code status(ErrorDetails code) {
        if (code == SynapseErrors.ALREADY_EXISTS) {
            return Status.Code.ALREADY_EXISTS;
        }
        ErrorCategory category = code.category();
        return switch (category) {
            case INVALID_INPUT -> Status.Code.INVALID_ARGUMENT;
            case NOT_FOUND -> Status.Code.NOT_FOUND;
            case CONFLICT -> Status.Code.ABORTED;
            default -> throw new AssertionError("No scenario expects the category " + category);
        };
    }

    private static ItemView view(Item item) {
        return new ItemView(UUID.fromString(item.getId()), item.getName(),
                item.hasDescription() ? item.getDescription().getValue() : null, item.getVersion());
    }
}
