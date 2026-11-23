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
package io.americanexpress.synapse.api.grpc.internal.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import com.google.protobuf.Any;
import com.google.protobuf.Empty;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.rpc.BadRequest;
import com.google.rpc.ErrorInfo;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import io.americanexpress.synapse.api.grpc.GrpcBindings;
import io.americanexpress.synapse.api.grpc.fixture.proto.Author;
import io.americanexpress.synapse.api.grpc.fixture.proto.CatalogServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.CreateItemRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.DeleteItemRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.FailRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.GetItemRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.Item;
import io.americanexpress.synapse.api.grpc.fixture.proto.LabelItemRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.LabelServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.ListTagsRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.WatchItemsRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.WatchServiceGrpc;
import io.americanexpress.synapse.core.context.RequestContext;
import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.masking.Masked;
import io.americanexpress.synapse.core.operation.BusinessOperation;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calls a {@link GrpcBindings} service on a real Netty server: one {@code .unary} binding with {@code .notFound} and
 * the other methods bound by name, each call running the operation pipeline. A hand-written {@code *ImplBase} service
 * runs next to it.
 */
@SpringBootTest(classes = GrpcBindingsIT.TestApplication.class, properties = {"spring.grpc.server.port=0",
        "management.tracing.sampling.probability=1.0", "management.logging.export.otlp.enabled=false",
        "management.tracing.export.otlp.enabled=false", "management.otlp.metrics.export.enabled=false"})
@ExtendWith(OutputCaptureExtension.class)
@ActiveProfiles("test")
class GrpcBindingsIT {

    private static final String SERVICE = "synapse.fixture.v1.CatalogService";

    /** A value that must never reach the caller or the log. */
    private static final String SECRET = "4111-SECRET-1111";

    /** The prefix of a {@code Fail} code that the operation passes to the card rule. */
    static final String CARD = "card:";

    private static final UUID KNOWN = UUID.fromString("0d4f1c9e-8a43-4a52-9a43-6f1e2b3c4d5e");

    /** An item whose operation sets both members of the lookup oneof, an operation bug. */
    private static final UUID BOTH_LOOKUPS = UUID.fromString("7a1e2b3c-4d5e-4f60-8a71-b2c3d4e5f607");

    private static final Instant CREATED = Instant.parse("2026-11-05T10:15:30.123456789Z");

    private static final Metadata.Key<String> CORRELATION = Metadata.Key
            .of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);

    @LocalGrpcServerPort
    private int port;

    /** A call's outcome and the metadata the client received. */
    record Result<T>(@Nullable T value, @Nullable StatusRuntimeException failure, @Nullable Metadata headers,
            @Nullable Metadata trailers) {

        T ok() {
            return Objects.requireNonNull(value, () -> "the call failed: " + failure);
        }

        StatusRuntimeException error() {
            return Objects.requireNonNull(failure, "the call succeeded");
        }

        /** The echoed correlation id: in the headers, or in the trailers of a trailers-only response. */
        @Nullable
        String echoed() {
            if (headers != null && headers.containsKey(CORRELATION)) {
                return headers.get(CORRELATION);
            }
            return trailers == null ? null : trailers.get(CORRELATION);
        }
    }

    /** Calls the server at {@code port} on a channel that sends {@code pairs} as metadata. */
    static <T> Result<T> call(int port, Function<Channel, T> rpc, String... pairs) throws InterruptedException {
        Metadata metadata = new Metadata();
        for (int i = 0; i < pairs.length; i += 2) {
            metadata.put(Metadata.Key.of(pairs[i], Metadata.ASCII_STRING_MARSHALLER), pairs[i + 1]);
        }
        AtomicReference<@Nullable Metadata> headers = new AtomicReference<>();
        AtomicReference<@Nullable Metadata> trailers = new AtomicReference<>();
        ManagedChannel channel = Grpc
                .newChannelBuilderForAddress("localhost", port, InsecureChannelCredentials.create())
                .build();
        try {
            Channel intercepted = ClientInterceptors
                    .intercept(channel, MetadataUtils.newAttachHeadersInterceptor(metadata),
                            MetadataUtils.newCaptureMetadataInterceptor(headers, trailers));
            T value = rpc.apply(intercepted);
            return new Result<>(value, null, headers.get(), trailers.get());
        } catch (StatusRuntimeException e) {
            return new Result<>(null, e, headers.get(), trailers.get());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private <T> Result<T> call(Function<CatalogServiceGrpc.CatalogServiceBlockingStub, T> rpc, String... pairs)
            throws InterruptedException {
        return call(port, channel -> rpc
                .apply(CatalogServiceGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS)), pairs);
    }

    static com.google.rpc.Status details(StatusRuntimeException e) {
        return Objects.requireNonNull(StatusProto.fromThrowable(e), "status details");
    }

    static <T extends Message> T detail(StatusRuntimeException e, Class<T> type) {
        for (Any any : details(e).getDetailsList()) {
            if (any.is(type)) {
                try {
                    return any.unpack(type);
                } catch (InvalidProtocolBufferException ex) {
                    throw new AssertionError(ex);
                }
            }
        }
        throw new AssertionError("no " + type.getSimpleName() + " in " + details(e));
    }

    private static GetItemRequest get(UUID id) {
        return GetItemRequest.newBuilder().setId(id.toString()).build();
    }

    private static List<Item> drain(Iterator<Item> items) {
        List<Item> drained = new ArrayList<>();
        items.forEachRemaining(drained::add);
        return drained;
    }

    @Test
    @Tag("smoke")
    void servesAnOperationWithNoOtherConfiguration() throws InterruptedException {
        Item item = call(stub -> stub.getItem(get(KNOWN))).ok();

        assertThat(item.getId()).isEqualTo(KNOWN.toString());
        assertThat(item.getName()).isEqualTo("Dune");
        assertThat(item.getCreatedAt().getSeconds()).isEqualTo(CREATED.getEpochSecond());
        assertThat(item.getCreatedAt().getNanos()).isEqualTo(CREATED.getNano());
        assertThat(item.getTtl().getSeconds()).isEqualTo(90);
        assertThat(item.getTtl().getNanos()).isEqualTo(5);
        assertThat(item.hasRating()).isTrue();
        assertThat(item.getRating().getValue()).isEqualTo(4);
        assertThat(item.hasNote()).isTrue();
        assertThat(item.getNote()).isEqualTo("signed");
        assertThat(item.getGenre()).isEqualTo(io.americanexpress.synapse.api.grpc.fixture.proto.Genre.GENRE_FICTION);
        assertThat(item.getTagsList()).containsExactly("classic", "space");
        assertThat(item.getCountsMap()).isEqualTo(Map.of("views", 12L, "likes", 3L));
        assertThat(item.getAuthor())
                .isEqualTo(Author.newBuilder().setName("Frank Herbert").setCountryCode("US").build());
        assertThat(item.getLookupCase()).isEqualTo(Item.LookupCase.SKU);
        assertThat(item.getSku()).isEqualTo("SKU-1");
        assertThat(item.getVersion()).isEqualTo(7L);
    }

    @Test
    void bindsTheRemainingMethodsByName() throws InterruptedException {
        assertThat(call(stub -> stub.ping(Empty.getDefaultInstance())).ok()).isEqualTo(Empty.getDefaultInstance());
        assertThat(call(stub -> stub.listTags(ListTagsRequest.newBuilder().setId("i").build())).ok().getTagsList())
                .containsExactly("classic", "space");
        Item created = call(stub -> stub
                .createItem(CreateItemRequest
                        .newBuilder()
                        .setItem(Item
                                .newBuilder()
                                .setName("Emma")
                                .setAuthor(Author.newBuilder().setName("Jane Austen").setCountryCode("GB")))
                        .build()))
                .ok();
        assertThat(created.getId()).isEqualTo("created-Emma");
        assertThat(created.getName()).isEqualTo("Emma");
    }

    @Test
    void notFoundCarriesErrorInfo() throws InterruptedException {
        Result<Item> result = call(stub -> stub.getItem(get(UUID.randomUUID())), "x-correlation-id", "c-404");

        StatusRuntimeException e = result.error();
        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(e.getStatus().getDescription()).isNullOrEmpty();
        ErrorInfo info = detail(e, ErrorInfo.class);
        assertThat(info.getReason()).isEqualTo("ITEM_NOT_FOUND");
        assertThat(info.getMetadataMap()).containsEntry("correlationId", "c-404");
        assertThat(result.echoed()).isEqualTo("c-404");
    }

    @Test
    void notFoundCorrelationIdMatchesTheTrailerWhenGenerated() throws InterruptedException {
        Result<Item> result = call(stub -> stub.getItem(get(UUID.randomUUID())));

        ErrorInfo info = detail(result.error(), ErrorInfo.class);
        assertThat(result.echoed()).isNotBlank().isEqualTo(info.getMetadataMap().get("correlationId"));
    }

    @Test
    void validationFailureIsInvalidArgumentWithBadRequest() throws InterruptedException {
        Result<Item> result = call(stub -> stub
                .createItem(CreateItemRequest
                        .newBuilder()
                        .setItem(Item
                                .newBuilder()
                                .setName(" ")
                                .setAuthor(Author.newBuilder().setName("Jane Austen").setCountryCode("")))
                        .build()));

        StatusRuntimeException e = result.error();
        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(detail(e, ErrorInfo.class).getReason()).isEqualTo("VALIDATION_FAILED");
        // Bean Validation names the record components; BadRequest names the proto fields.
        assertThat(detail(e, BadRequest.class).getFieldViolationsList())
                .extracting(BadRequest.FieldViolation::getField)
                .containsExactlyInAnyOrder("item.name", "item.author.country_code");
    }

    @Test
    void unsentNestedFieldIsMustNotBeNullOnBothProtocols() throws InterruptedException {
        // A non-null component is required at every depth, and REST answers the same input the same way
        // (RestRoutesIT leaves the author's countryCode out): the reader's "must not be null", not @NotBlank's.
        Result<Item> result = call(stub -> stub
                .createItem(CreateItemRequest
                        .newBuilder()
                        .setItem(
                                Item.newBuilder().setName("Emma").setAuthor(Author.newBuilder().setName("Jane Austen")))
                        .build()));

        StatusRuntimeException e = result.error();
        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(detail(e, ErrorInfo.class).getReason()).isEqualTo("VALIDATION_FAILED");
        assertThat(detail(e, BadRequest.class).getFieldViolationsList())
                .singleElement()
                .satisfies(violation -> {
                    assertThat(violation.getField()).isEqualTo("item.author.country_code");
                    assertThat(violation.getDescription()).isEqualTo("must not be null");
                });
    }

    @Test
    void emptyRepeatedElementIsKeptAndValidated() throws InterruptedException {
        // REST keeps an empty query, path or cookie list item too (RestRoutesIT), so List<@NotBlank String> answers
        // VALIDATION_FAILED over both protocols.
        Result<Item> result = call(stub -> stub
                .createItem(CreateItemRequest
                        .newBuilder()
                        .setItem(Item
                                .newBuilder()
                                .setName("Emma")
                                .setAuthor(Author.newBuilder().setName("Jane Austen").setCountryCode("GB"))
                                .addTags("")
                                .addTags("x"))
                        .build()));

        StatusRuntimeException e = result.error();
        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(detail(e, ErrorInfo.class).getReason()).isEqualTo("VALIDATION_FAILED");
        assertThat(detail(e, BadRequest.class).getFieldViolationsList())
                .extracting(BadRequest.FieldViolation::getField)
                .containsExactly("item.tags[0]");
    }

    @Test
    void scalarItemViolationNamesTheItem() throws InterruptedException {
        Result<Item> result = call(stub -> stub
                .createItem(CreateItemRequest
                        .newBuilder()
                        .setItem(Item
                                .newBuilder()
                                .setName("Emma")
                                .setAuthor(Author.newBuilder().setName("Jane Austen").setCountryCode("GB"))
                                .addTags("a")
                                .addTags(""))
                        .build()));

        StatusRuntimeException e = result.error();
        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        // Core keeps a scalar item's index (ViolationPaths), and the proto path keeps it too.
        assertThat(detail(e, BadRequest.class).getFieldViolationsList())
                .extracting(BadRequest.FieldViolation::getField)
                .containsExactly("item.tags[1]");
    }

    @Test
    void notEmptyGivesTheSameViolationOnBothProtocols() throws InterruptedException {
        // A repeated field is never missing, and REST binds an unsent collection empty too (RestRoutesIT), so the same
        // operation's @NotEmpty answers over both protocols.
        Result<Empty> result = call(port, channel -> LabelServiceGrpc
                .newBlockingStub(channel)
                .withDeadlineAfter(10, TimeUnit.SECONDS)
                .labelItem(LabelItemRequest.getDefaultInstance()));

        StatusRuntimeException e = result.error();
        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(detail(e, ErrorInfo.class).getReason()).isEqualTo("VALIDATION_FAILED");
        assertThat(detail(e, BadRequest.class).getFieldViolationsList())
                .singleElement()
                .satisfies(violation -> {
                    assertThat(violation.getField()).isEqualTo("tags");
                    assertThat(violation.getDescription()).isEqualTo("must not be empty");
                });
    }

    @Test
    void readerViolationsNameTheProtoField() throws InterruptedException {
        Result<Item> result = call(stub -> stub.getItem(GetItemRequest.newBuilder().setId("not-a-uuid").build()));

        StatusRuntimeException e = result.error();
        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(detail(e, BadRequest.class).getFieldViolationsList())
                .singleElement()
                .satisfies(violation -> {
                    assertThat(violation.getField()).isEqualTo("id");
                    assertThat(violation.getDescription()).isEqualTo("is not a valid value");
                });
    }

    @Test
    void unexpectedExceptionIsInternal() throws InterruptedException {
        Result<?> result = call(stub -> stub.fail(FailRequest.newBuilder().setCode("boom").build()));

        StatusRuntimeException e = result.error();
        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(detail(e, ErrorInfo.class).getReason()).isEqualTo("INTERNAL");
        String everything = e.getStatus() + " " + details(e) + " " + result.trailers();
        assertThat(everything).doesNotContain("Exception").doesNotContain("at io.").doesNotContain("hunter2");
    }

    @Test
    void oneofWithTwoValuesIsInternalAndNamesTheMethod(CapturedOutput output) throws InterruptedException {
        int start = output.getAll().length();

        Result<Item> result = call(stub -> stub.getItem(get(BOTH_LOOKUPS)));

        // sku and barcode both write the lookup oneof: writing both would keep only the last, so the call fails.
        StatusRuntimeException e = result.error();
        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(detail(e, ErrorInfo.class).getReason()).isEqualTo("INTERNAL");
        String everything = e.getStatus() + " " + details(e) + " " + result.trailers();
        assertThat(everything).doesNotContain("oneof").doesNotContain("barcode").doesNotContain("Exception");
        assertThat(output.getAll().substring(start))
                .contains("Unhandled error in gRPC call to get-item")
                .contains("gRPC method " + SERVICE + "/GetItem (get-item): ItemView.sku and ItemView.barcode both "
                        + "have a value, but they write members of oneof synapse.fixture.v1.Item.lookup, which "
                        + "holds one");
    }

    @Test
    void operationRunsInsideThePipeline(CapturedOutput output) throws InterruptedException {
        int start = output.getAll().length();

        call(stub -> stub.fail(FailRequest.newBuilder().setCode("boom").build()));

        String logged = output.getAll().substring(start);
        assertThat(logged.lines().filter(line -> line.contains("Operation fail failed with INTERNAL"))).hasSize(1);
        assertThat(logged).doesNotContain("Unhandled error in gRPC call");
    }

    @Test
    void malformedRequestIsInvalidArgument() throws InterruptedException {
        MethodDescriptor<byte[], byte[]> raw = MethodDescriptor
                .newBuilder(RawBytesMarshaller.INSTANCE, RawBytesMarshaller.INSTANCE)
                .setFullMethodName(CatalogServiceGrpc.getGetItemMethod().getFullMethodName())
                .setType(MethodDescriptor.MethodType.UNARY)
                .build();

        Result<byte[]> result = call(port, channel -> ClientCalls
                .blockingUnaryCall(channel, raw, CallOptions.DEFAULT.withDeadlineAfter(10, TimeUnit.SECONDS),
                        new byte[]{(byte) 0xFF, (byte) 0xFF}));

        StatusRuntimeException e = result.error();
        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        ErrorInfo info = detail(e, ErrorInfo.class);
        assertThat(info.getReason()).isEqualTo("MALFORMED_REQUEST");
        assertThat(result.echoed()).isNotBlank().isEqualTo(info.getMetadataMap().get("correlationId"));
    }

    @Test
    void escapeHatchServiceGetsTheSameEdge() throws InterruptedException {
        Result<List<Item>> found = call(port, channel -> {
            Iterator<Item> items = WatchServiceGrpc
                    .newBlockingStub(channel)
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .watchItems(WatchItemsRequest.newBuilder().setId("i").build());
            return drain(items);
        }, "x-correlation-id", "c-watch");
        Result<List<Item>> missing = call(port, channel -> {
            Iterator<Item> items = WatchServiceGrpc
                    .newBlockingStub(channel)
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .watchItems(WatchItemsRequest.newBuilder().setId("missing").build());
            return drain(items);
        }, "x-correlation-id", "c-missing");

        // The service read the request context the edge opened.
        assertThat(found.ok()).singleElement().extracting(Item::getId).isEqualTo("c-watch");
        assertThat(found.echoed()).isEqualTo("c-watch");
        StatusRuntimeException e = missing.error();
        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(detail(e, ErrorInfo.class).getReason()).isEqualTo("ITEM_NOT_FOUND");
        assertThat(detail(e, ErrorInfo.class).getMetadataMap()).containsEntry("correlationId", "c-missing");
        assertThat(missing.echoed()).isEqualTo("c-missing");
    }

    @Test
    void exposureInventoryIsLogged(CapturedOutput output) {
        assertThat(output)
                .contains("Synapse gRPC exposes " + SERVICE + "/GetItem -> get-item on port " + port)
                .contains("Synapse gRPC exposes " + SERVICE + "/CreateItem -> create-item on port " + port)
                .contains("Synapse gRPC exposes " + SERVICE + "/DeleteItem -> delete-item on port " + port)
                .contains("Synapse gRPC exposes " + SERVICE + "/Ping -> ping on port " + port)
                .contains("Synapse gRPC exposes " + SERVICE + "/ListTags -> list-tags on port " + port)
                .contains("Synapse gRPC exposes " + SERVICE + "/Fail -> fail on port " + port)
                .doesNotContain("Synapse gRPC exposes synapse.fixture.v1.WatchService");
    }

    @Nested
    @TestPropertySource(properties = "synapse.api.grpc.operations.get-item.errors.status.[ITEM_NOT_FOUND]=FAILED_PRECONDITION")
    class OperationOverride {

        @LocalGrpcServerPort
        private int overridePort;

        @Test
        void perOperationOverrideChangesOneMethod() throws InterruptedException {
            Result<Item> getItem = call(overridePort, channel -> CatalogServiceGrpc
                    .newBlockingStub(channel)
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .getItem(get(UUID.randomUUID())));
            Result<Empty> deleteItem = call(overridePort, channel -> CatalogServiceGrpc
                    .newBlockingStub(channel)
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .deleteItem(DeleteItemRequest.newBuilder().setId("i").build()));

            assertThat(getItem.error().getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
            assertThat(detail(getItem.error(), ErrorInfo.class).getReason()).isEqualTo("ITEM_NOT_FOUND");
            assertThat(deleteItem.error().getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
            assertThat(detail(deleteItem.error(), ErrorInfo.class).getReason()).isEqualTo("ITEM_NOT_FOUND");
        }
    }

    @Nested
    @TestPropertySource(properties = "synapse.errors.include-detail=true")
    class WithDetail {

        @LocalGrpcServerPort
        private int detailPort;

        @Test
        void rejectedBusinessInputIsInternalWithoutDetail(CapturedOutput output) throws InterruptedException {
            Result<?> result = call(detailPort, channel -> CatalogServiceGrpc
                    .newBlockingStub(channel)
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .fail(FailRequest.newBuilder().setCode(CARD + SECRET).build()));

            // The service built the business input: its violation is the service's bug, never the caller's.
            StatusRuntimeException e = result.error();
            assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
            assertThat(e.getStatus().getDescription()).isEqualTo("An internal error occurred");
            assertThat(detail(e, ErrorInfo.class).getReason()).isEqualTo("INTERNAL");
            assertThat(details(e).getDetailsList()).noneMatch(any -> any.is(BadRequest.class));
            String everything = e.getStatus() + " " + details(e) + " " + result.trailers();
            assertThat(everything).doesNotContain(SECRET).doesNotContain("number").doesNotContain("too long");
            assertThat(output.getOut())
                    .contains("Operation fail failed with INTERNAL", "number: ${validatedValue} is too long")
                    .doesNotContain(SECRET);
        }
    }

    /** The fixture's error codes. */
    enum ItemErrors implements ErrorDetails {

        /** No item with the id. */
        ITEM_NOT_FOUND;

        @Override
        public String code() {
            return name();
        }

        @Override
        public String messageTemplate() {
            return "Item was not found";
        }

        @Override
        public ErrorCategory category() {
            return ErrorCategory.NOT_FOUND;
        }
    }

    /** The Java side of {@code synapse.fixture.v1.Genre}. */
    enum Genre {
        FICTION, SCIENCE
    }

    /** The input of {@code GetItem}. */
    record ItemKey(UUID id) {
    }

    /** An item's author. */
    record AuthorView(String name, String countryCode) {
    }

    /** An item, with every built-in type the mapping supports. */
    record ItemView(UUID id, String name, Instant createdAt, Duration ttl, @Nullable Integer rating,
            @Nullable String note, Genre genre, List<String> tags, Map<String, Long> counts, AuthorView author,
            @Nullable String sku, @Nullable String barcode, long version) {
    }

    /** The author of a new item. */
    record NewAuthor(String name, @NotBlank String countryCode) {
    }

    /** A new item. */
    record NewItem(@NotBlank String name, @Valid NewAuthor author, List<@NotBlank String> tags) {
    }

    /** The input of {@code CreateItem}. */
    record CreateItemInput(@Valid NewItem item) {
    }

    /** The output of {@code CreateItem}. */
    record CreatedItem(String id, String name) {
    }

    /** The input of {@code DeleteItem} and {@code ListTags}. */
    record ItemId(String id) {
    }

    /** The input of {@code Fail}. */
    record FailInput(String code) {
    }

    /** The output of {@code Fail}. */
    record FailOutput(String message) {
    }

    /** Finds the one known item. */
    static class GetItemOperation implements QueryOperation<ItemKey, Optional<ItemView>> {

        @Override
        public Optional<ItemView> execute(ItemKey input) {
            if (!KNOWN.equals(input.id()) && !BOTH_LOOKUPS.equals(input.id())) {
                return Optional.empty();
            }
            String barcode = BOTH_LOOKUPS.equals(input.id()) ? "4006381333931" : null;
            return Optional
                    .of(new ItemView(input.id(), "Dune", CREATED, Duration.ofSeconds(90, 5), 4, "signed",
                            Genre.FICTION, List.of("classic", "space"), Map.of("views", 12L, "likes", 3L),
                            new AuthorView("Frank Herbert", "US"), "SKU-1", barcode, 7L));
        }
    }

    /** Creates an item. */
    static class CreateItemOperation implements CommandOperation<CreateItemInput, CreatedItem> {

        @Override
        public CreatedItem execute(CreateItemInput input) {
            return new CreatedItem("created-" + input.item().name(), input.item().name());
        }
    }

    /** Never finds the item to delete. */
    static class DeleteItemOperation implements CommandOperation<ItemId, Void> {

        @Override
        public Void execute(ItemId input) {
            throw new SynapseException(ItemErrors.ITEM_NOT_FOUND);
        }
    }

    /** Answers. */
    static class PingOperation implements QueryOperation<Void, Void> {

        @Override
        @SuppressWarnings("NullAway") // A Void output is null.
        public Void execute(Void input) {
            return null;
        }
    }

    /** Lists an item's tags. */
    static class ListTagsOperation implements QueryOperation<ItemId, List<String>> {

        @Override
        public List<String> execute(ItemId input) {
            return List.of("classic", "space");
        }
    }

    /** The input of {@code LabelItem}, the operation RestRoutesIT binds the same way: at least one tag. */
    record LabelInput(@NotEmpty List<String> tags) {
    }

    /** Labels an item. */
    static class LabelItemOperation implements CommandOperation<LabelInput, Void> {

        @Override
        @SuppressWarnings("NullAway") // A Void output is null.
        public Void execute(LabelInput input) {
            return null;
        }
    }

    /** A card number: masked, with a constraint message that would show the rejected value. */
    record Card(@Masked @Size(max = 3, message = "${validatedValue} is too long") String number) {
    }

    /** A business rule on a card, named {@code card-rule}. */
    static class CardRule implements BusinessOperation<Card, String> {

        @Override
        public String execute(Card input) {
            return input.number();
        }
    }

    /**
     * Fails with an internal message that must never reach the caller, or, for a code that starts with
     * {@value #CARD}, passes the rest to the card rule as a card number, which the rule rejects as too long.
     */
    static class FailOperation implements QueryOperation<FailInput, FailOutput> {

        private final CardRule rule;

        FailOperation(CardRule rule) {
            this.rule = rule;
        }

        @Override
        public FailOutput execute(FailInput input) {
            if (input.code().startsWith(CARD)) {
                return new FailOutput(rule.execute(new Card(input.code().substring(CARD.length()))));
            }
            throw new IllegalStateException("database password=hunter2");
        }
    }

    /** A hand-written service (the escape hatch) next to the bindings. */
    static class WatchService extends WatchServiceGrpc.WatchServiceImplBase {

        @Override
        public void watchItems(WatchItemsRequest request, StreamObserver<Item> responseObserver) {
            if ("missing".equals(request.getId())) {
                throw new SynapseException(ItemErrors.ITEM_NOT_FOUND);
            }
            String correlationId = RequestContext.find().map(RequestContext::correlationId).orElse("none");
            responseObserver.onNext(Item.newBuilder().setId(correlationId).build());
            responseObserver.onCompleted();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {

        @Bean
        GetItemOperation getItemOperation() {
            return new GetItemOperation();
        }

        @Bean
        CreateItemOperation createItemOperation() {
            return new CreateItemOperation();
        }

        @Bean
        DeleteItemOperation deleteItemOperation() {
            return new DeleteItemOperation();
        }

        @Bean
        PingOperation pingOperation() {
            return new PingOperation();
        }

        @Bean
        ListTagsOperation listTagsOperation() {
            return new ListTagsOperation();
        }

        @Bean
        CardRule cardRule() {
            return new CardRule();
        }

        @Bean
        FailOperation failOperation(CardRule cardRule) {
            return new FailOperation(cardRule);
        }

        @Bean
        GrpcBindings catalogBindings(GetItemOperation getItem) {
            return GrpcBindings
                    .service(CatalogServiceGrpc.getServiceDescriptor())
                    .unary(CatalogServiceGrpc.getGetItemMethod(), getItem)
                    .map("sku", ItemView::sku)
                    .map("barcode", ItemView::barcode)
                    .notFound(ItemErrors.ITEM_NOT_FOUND)
                    .bindAllByName()
                    .build();
        }

        @Bean
        LabelItemOperation labelItemOperation() {
            return new LabelItemOperation();
        }

        @Bean
        GrpcBindings labelBindings(LabelItemOperation labelItem) {
            return GrpcBindings
                    .service(LabelServiceGrpc.getServiceDescriptor())
                    .unary(LabelServiceGrpc.getLabelItemMethod(), labelItem)
                    .build();
        }

        @Bean
        WatchService watchService() {
            return new WatchService();
        }
    }
}
