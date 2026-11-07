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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Empty;
import io.grpc.Context;
import io.grpc.MethodDescriptor;
import io.grpc.stub.StreamObserver;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.api.grpc.fixture.proto.CatalogServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.GetItemRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.Item;
import io.americanexpress.synapse.api.grpc.fixture.proto.Node;
import io.americanexpress.synapse.api.grpc.internal.binding.FieldOverride;
import io.americanexpress.synapse.api.grpc.internal.binding.MethodDeclaration;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures;
import io.americanexpress.synapse.api.grpc.internal.edge.GrpcCallState;
import io.americanexpress.synapse.core.binding.ComponentRef;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.core.internal.binding.ComponentRefs;
import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.core.operation.ServiceOperation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class UnaryOperationHandlerTest {

    private static final Descriptor GET_ITEM_REQUEST = GetItemRequest.getDescriptor();

    private static final Descriptor ITEM = Item.getDescriptor();

    private static final Descriptor EMPTY = Empty.getDescriptor();

    private static final byte[] GET_I1 = GetItemRequest.newBuilder().setId("i-1").build().toByteArray();

    private static UnaryOperationHandler handler(ServiceOperation<?, ?> operation, Descriptor input, Descriptor output,
            @Nullable ErrorDetails notFound, List<FieldOverride> overrides) {
        MethodDescriptor<?, ?> method = CatalogServiceGrpc.getGetItemMethod();
        return UnaryOperationHandler
                .create(MethodDeclaration.declared(method, operation, overrides, List.of(), notFound), input, output);
    }

    private static UnaryOperationHandler handler(ServiceOperation<?, ?> operation, Descriptor input,
            Descriptor output) {
        return handler(operation, input, output, null, List.of());
    }

    private static <T> FieldOverride override(String path, ComponentRef<T, ?> component) {
        return new FieldOverride(path, ComponentRefs.resolve(component));
    }

    /** Invokes the handler with the call's state in the current gRPC context, as the edge interceptor puts it. */
    private static void invoke(UnaryOperationHandler handler, byte[] request, Recorder observer, GrpcCallState state) {
        Context context = Context.current().withValue(GrpcCallState.KEY, state);
        Context previous = context.attach();
        try {
            handler.invoke(request, observer);
        } finally {
            context.detach(previous);
        }
    }

    private static GrpcCallState state() {
        return new GrpcCallState(null, Map.of());
    }

    @Test
    void emptyOptionalIsNotFoundOrTheDeclaredCode() {
        UnaryOperationHandler byDefault = handler(new BindingFixtures.GetItemOperation(), GET_ITEM_REQUEST, ITEM);
        UnaryOperationHandler declared = handler(new BindingFixtures.GetItemOperation(), GET_ITEM_REQUEST, ITEM,
                BindingFixtures.ItemErrors.ITEM_NOT_FOUND, List.of());
        UnaryOperationHandler returningNull = handler(new NullOptionalOperation(), GET_ITEM_REQUEST, ITEM);
        Recorder observer = new Recorder();

        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> byDefault.invoke(GET_I1, observer))
                .satisfies(e -> {
                    assertThat(e.details()).isEqualTo(SynapseErrors.NOT_FOUND);
                    assertThat(e.code()).isEqualTo("NOT_FOUND");
                });
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> declared.invoke(GET_I1, observer))
                .satisfies(e -> assertThat(e.code()).isEqualTo("ITEM_NOT_FOUND"));
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> returningNull.invoke(GET_I1, observer))
                .satisfies(e -> assertThat(e.code()).isEqualTo("NOT_FOUND"));
        assertThat(observer.values).isEmpty();
        assertThat(observer.completed).isZero();
    }

    @Test
    void presentOptionalIsWrittenFromItsValue() {
        UnaryOperationHandler handler = handler(new FoundItemOperation(), GET_ITEM_REQUEST, ITEM);
        Recorder observer = new Recorder();

        handler.invoke(GET_I1, observer);

        assertThat(observer.values).singleElement().satisfies(message -> {
            assertThat(field(message, "id")).isEqualTo("i-1");
            assertThat(field(message, "name")).isEqualTo("found");
        });
        assertThat(observer.completed).isOne();
    }

    @Test
    void writesTheOutputCompletesAndRecordsTheCallState() {
        UnaryOperationHandler handler = handler(new BindingFixtures.LoadItemOperation(), GET_ITEM_REQUEST, ITEM);
        Recorder observer = new Recorder();
        GrpcCallState state = state();

        invoke(handler, GET_I1, observer, state);

        assertThat(observer.values).singleElement().satisfies(message -> {
            assertThat(field(message, "id")).isEqualTo("i-1");
            assertThat(field(message, "name")).isEqualTo("n");
            DynamicMessage author = (DynamicMessage) field(message, "author");
            assertThat(field(author, "country_code")).isEqualTo("US");
        });
        assertThat(observer.completed).isOne();
        assertThat(state.operationName()).isEqualTo("load-item");
        assertThat(state.fieldPaths()).isEqualTo(Map.of("id", "id"));
    }

    @Test
    void voidInputAndOutputAreTheEmptyMessage() {
        UnaryOperationHandler handler = handler(new NothingOperation(), EMPTY, EMPTY);
        Recorder observer = new Recorder();

        handler.invoke(new byte[0], observer);

        assertThat(observer.values).singleElement().isEqualTo(DynamicMessage.getDefaultInstance(EMPTY));
        assertThat(observer.completed).isOne();
    }

    @Test
    void malformedBytesAreAMalformedRequestAndNeverReachTheOperation() {
        CountingOperation operation = new CountingOperation();
        UnaryOperationHandler handler = handler(operation, GET_ITEM_REQUEST, EMPTY);
        GrpcCallState state = state();

        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> invoke(handler, new byte[]{(byte) 0xFF, (byte) 0xFF}, new Recorder(), state))
                .satisfies(e -> {
                    assertThat(e.details()).isEqualTo(SynapseErrors.MALFORMED_REQUEST);
                    assertThat(e.getMessage()).isEqualTo("The request is malformed");
                });
        assertThat(operation.calls.get()).isZero();
        // The operation name is known before parsing, so per-operation status overrides apply to the failure.
        assertThat(state.operationName()).isEqualTo("counting");
    }

    @Test
    void parsingKeepsProtobufsDefaultRecursionLimit() {
        CountingNodeOperation operation = new CountingNodeOperation();
        UnaryOperationHandler handler = handler(operation, Node.getDescriptor(), EMPTY);

        handler.invoke(nested(50).toByteArray(), new Recorder());
        assertThat(operation.calls.get()).isOne();

        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> handler.invoke(nested(150).toByteArray(), new Recorder()))
                .satisfies(e -> assertThat(e.details()).isEqualTo(SynapseErrors.MALFORMED_REQUEST));
        assertThat(operation.calls.get()).isOne();
    }

    @Test
    void readerViolationsKeepTheirProtoPaths() {
        // id reads from the field "name" and name from "id": the reader names the field "name"; mapping that proto
        // path through the component paths again would report it as "id".
        UnaryOperationHandler handler = handler(new SwappedOperation(), ITEM, EMPTY, null,
                List.of(override("name", Swapped::id), override("id", Swapped::name)));
        byte[] request = Item.newBuilder().setId("x").setName("not-a-uuid").build().toByteArray();
        GrpcCallState state = state();

        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> invoke(handler, request, new Recorder(), state))
                .satisfies(e -> assertThat(e.violations())
                        .containsExactly(new Violation("name", "is not a valid value")));
        // The status writer maps violations through the state's field paths: none are set for a reader failure.
        assertThat(state.fieldPaths()).isEmpty();
    }

    @Test
    void pipelineViolationsAreMappedThroughTheFieldPaths() {
        UnaryOperationHandler handler = handler(new SwappedOperation(), ITEM, EMPTY, null,
                List.of(override("name", Swapped::id), override("id", Swapped::name)));
        UUID id = UUID.randomUUID();
        byte[] request = Item.newBuilder().setId("x").setName(id.toString()).build().toByteArray();
        GrpcCallState state = state();

        invoke(handler, request, new Recorder(), state);

        assertThat(state.fieldPaths()).isEqualTo(Map.of("id", "name", "name", "id"));
    }

    @Test
    void rootRecordFailureLeavesItsCauseOutOfTheError() {
        UnaryOperationHandler handler = handler(new CheckedOperation(), GET_ITEM_REQUEST, EMPTY);
        byte[] request = GetItemRequest.newBuilder().setId("bad").build().toByteArray();

        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> handler.invoke(request, new Recorder()))
                .satisfies(e -> {
                    assertThat(e.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED);
                    assertThat(e.getMessage()).isEqualTo("The request is invalid").doesNotContain("secret");
                    assertThat(e.args()).isEmpty();
                    assertThat(e.violations()).isEmpty();
                });
    }

    private static Object field(DynamicMessage message, String name) {
        return message.getField(message.getDescriptorForType().findFieldByName(name));
    }

    private static Node nested(int depth) {
        Node node = Node.newBuilder().setName("leaf").build();
        for (int i = 1; i < depth; i++) {
            node = Node.newBuilder().setName("n" + i).setNext(node).build();
        }
        return node;
    }

    /** Records what the handler sends. */
    static final class Recorder implements StreamObserver<DynamicMessage> {

        final List<DynamicMessage> values = new ArrayList<>();

        int completed;

        @Override
        public void onNext(DynamicMessage value) {
            values.add(value);
        }

        @Override
        public void onError(Throwable t) {
            throw new AssertionError("the handler throws instead", t);
        }

        @Override
        public void onCompleted() {
            completed++;
        }
    }

    /** Returns {@code null} for an {@code Optional} output, which means empty. */
    static class NullOptionalOperation
            implements QueryOperation<BindingFixtures.GetItem, Optional<BindingFixtures.ItemRecord>> {

        @Override
        @SuppressWarnings("NullAway") // The case under test: an Optional output that is null.
        public Optional<BindingFixtures.ItemRecord> execute(BindingFixtures.GetItem input) {
            return null;
        }
    }

    /** Finds every item. */
    static class FoundItemOperation
            implements QueryOperation<BindingFixtures.GetItem, Optional<BindingFixtures.ItemRecord>> {

        @Override
        public Optional<BindingFixtures.ItemRecord> execute(BindingFixtures.GetItem input) {
            return Optional
                    .of(new BindingFixtures.ItemRecord(input.id(), "found", new BindingFixtures.AuthorRecord("a",
                            "US")));
        }
    }

    /** Takes and returns nothing. */
    static class NothingOperation implements QueryOperation<Void, Void> {

        @Override
        @SuppressWarnings("NullAway") // A Void output is null.
        public Void execute(Void input) {
            return null;
        }
    }

    /** Counts its calls. */
    static class CountingOperation implements QueryOperation<BindingFixtures.GetItem, Void> {

        final AtomicInteger calls = new AtomicInteger();

        @Override
        @SuppressWarnings("NullAway") // A Void output is null.
        public Void execute(BindingFixtures.GetItem input) {
            calls.incrementAndGet();
            return null;
        }
    }

    /** A record of a message that contains itself. */
    record NodeRecord(String name, @Nullable NodeRecord next) {
    }

    /** Counts its calls. */
    static class CountingNodeOperation implements QueryOperation<NodeRecord, Void> {

        final AtomicInteger calls = new AtomicInteger();

        @Override
        @SuppressWarnings("NullAway") // A Void output is null.
        public Void execute(NodeRecord input) {
            calls.incrementAndGet();
            return null;
        }
    }

    /** Components mapped to each other's fields. */
    record Swapped(UUID id, String name) {
    }

    /** Takes a {@link Swapped}. */
    static class SwappedOperation implements QueryOperation<Swapped, Void> {

        @Override
        @SuppressWarnings("NullAway") // A Void output is null.
        public Void execute(Swapped input) {
            return null;
        }
    }

    /** A record whose constructor rejects one value with an internal message. */
    record Checked(String id) {

        Checked {
            if ("bad".equals(id)) {
                throw new IllegalArgumentException("secret detail");
            }
        }
    }

    /** Takes a {@link Checked}. */
    static class CheckedOperation implements QueryOperation<Checked, Void> {

        @Override
        @SuppressWarnings("NullAway") // A Void output is null.
        public Void execute(Checked input) {
            return null;
        }
    }
}
