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

import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.grpc.MethodDescriptor;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServiceDescriptor;
import io.grpc.protobuf.ProtoUtils;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import io.americanexpress.synapse.api.grpc.GrpcBindings;
import io.americanexpress.synapse.api.grpc.fixture.proto.CatalogServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.GetItemRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.NoItemsServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.PagedBookServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.PlainServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.WatchServiceGrpc;
import io.americanexpress.synapse.api.grpc.internal.binding.ServiceDeclaration;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures;
import io.americanexpress.synapse.api.grpc.internal.mapping.MappingException;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapsePagingProperties;
import io.americanexpress.synapse.core.internal.paging.AesGcmCursorCodec;
import io.americanexpress.synapse.core.internal.paging.CursorKeys;
import io.americanexpress.synapse.core.internal.paging.InputDigest;
import io.americanexpress.synapse.core.internal.paging.PageCursors;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.operation.OperationName;
import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.core.operation.ServiceOperation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ServiceCompilerTest {

    private static final String SERVICE = "synapse.fixture.v1.CatalogService";

    private static final String PLAIN = "synapse.fixture.v1.PlainService";

    private static final String PRESENCE = "synapse.fixture.v1.Presence";

    private static final List<String> METHODS = List
            .of(SERVICE + "/GetItem", SERVICE + "/CreateItem", SERVICE + "/DeleteItem", SERVICE + "/Ping",
                    SERVICE + "/ListTags", SERVICE + "/Fail");

    private final ServiceCompiler compiler = Compilers.compiler();

    /** The operations {@code .bindAllByName()} finds for every method but {@code GetItem}. */
    private static List<ServiceOperation<?, ?>> remainingOperations() {
        return List
                .of(new CreateItemOperation(), new BindingFixtures.DeleteItemOperation(),
                        new BindingFixtures.PingOperation(), new BindingFixtures.ListTagsOperation(),
                        new BindingFixtures.FailOperation());
    }

    private static GrpcBindings getItemAndTheRestByName() {
        return GrpcBindings
                .service(CatalogServiceGrpc.getServiceDescriptor())
                .unary(CatalogServiceGrpc.getGetItemMethod(), new BindingFixtures.GetItemOperation())
                .bindAllByName()
                .build();
    }

    @Test
    void unboundMethodFails() {
        ServiceDeclaration declaration = GrpcBindingsAccess
                .declaration(GrpcBindings
                        .service(CatalogServiceGrpc.getServiceDescriptor())
                        .unary(CatalogServiceGrpc.getGetItemMethod(), new BindingFixtures.GetItemOperation())
                        .build());

        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> compiler.compile(declaration, remainingOperations()))
                .satisfies(e -> {
                    assertThat(e.description())
                            .isEqualTo("gRPC method " + SERVICE + "/CreateItem (create-item): no operation is bound");
                    assertThat(e.action())
                            .isEqualTo("Bind it with .unary(...), or add .bindAllByName() and an operation named"
                                    + " create-item.");
                });
    }

    @Test
    void unboundMethodWithBindAllByNameNamesTheMissingOperation() {
        ServiceDeclaration declaration = GrpcBindingsAccess.declaration(getItemAndTheRestByName());
        List<ServiceOperation<?, ?>> withoutPing = remainingOperations()
                .stream()
                .filter(operation -> !(operation instanceof BindingFixtures.PingOperation))
                .toList();

        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> compiler.compile(declaration, withoutPing))
                .satisfies(e -> {
                    assertThat(e.description())
                            .isEqualTo("gRPC method " + SERVICE + "/Ping (ping): no operation is bound");
                    assertThat(e.action())
                            .isEqualTo(
                                    "Bind it with .unary(...), or add .bindAllByName() and an operation named ping.");
                });
    }

    @Test
    void unboundHintIsAppendedToTheAction() {
        ServiceCompiler hinted = Compilers.compiler("In @SynapseGrpcTest, declare a @MockitoBean for it.");
        ServiceDeclaration declaration = GrpcBindingsAccess.declaration(getItemAndTheRestByName());

        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> hinted.compile(declaration, List.of()))
                .satisfies(e -> {
                    assertThat(e.description())
                            .isEqualTo("gRPC method " + SERVICE + "/CreateItem (create-item): no operation is bound");
                    assertThat(e.action())
                            .isEqualTo("Bind it with .unary(...), or add .bindAllByName() and an operation named"
                                    + " create-item. In @SynapseGrpcTest, declare a @MockitoBean for it.");
                });
    }

    @Test
    void bindAllByNameBindsRemainingMethods() {
        ServiceDeclaration declaration = GrpcBindingsAccess.declaration(getItemAndTheRestByName());

        ServerServiceDefinition definition = compiler.compile(declaration, remainingOperations());

        assertThat(definition.getMethods())
                .extracting(method -> method.getMethodDescriptor().getFullMethodName())
                .containsExactlyInAnyOrderElementsOf(METHODS);
        assertThat(ServiceCompiler.operationNames(declaration))
                .containsExactly(Map.entry(SERVICE + "/GetItem", "get-item"),
                        Map.entry(SERVICE + "/CreateItem", "create-item"),
                        Map.entry(SERVICE + "/DeleteItem", "delete-item"), Map.entry(SERVICE + "/Ping", "ping"),
                        Map.entry(SERVICE + "/ListTags", "list-tags"), Map.entry(SERVICE + "/Fail", "fail"));
    }

    @Test
    void bindAllByNameNeverReplacesAUnaryDeclaration() {
        // A by-name operation for GetItem exists too, but the .unary declaration wins and no candidate check runs.
        List<ServiceOperation<?, ?>> operations = new java.util.ArrayList<>(remainingOperations());
        operations.add(new GetItemOperation());
        operations.add(new OtherGetItemOperation());

        ServerServiceDefinition definition = compiler
                .compile(GrpcBindingsAccess.declaration(getItemAndTheRestByName()), operations);

        assertThat(definition.getMethods()).hasSize(6);
    }

    @Test
    void bindAllByNameWithTwoCandidatesFails() {
        List<ServiceOperation<?, ?>> operations = new java.util.ArrayList<>(remainingOperations());
        operations.add(new OtherPingOperation());
        ServiceDeclaration declaration = GrpcBindingsAccess.declaration(getItemAndTheRestByName());

        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> compiler.compile(declaration, operations))
                .satisfies(e -> {
                    assertThat(e.description())
                            .isEqualTo(
                                    "gRPC method " + SERVICE + "/Ping (ping): more than one operation is named ping ("
                                            + BindingFixtures.PingOperation.class.getName() + ", "
                                            + OtherPingOperation.class.getName() + ")");
                    assertThat(e.action())
                            .isEqualTo("Give the operations different names with @OperationName, or bind the method"
                                    + " with .unary(...).");
                });
    }

    @Test
    void methodsBoundByNameGetTheBindingChecks() {
        // ListTags bound by name to an operation that returns a page in an Optional: the paging check applies to it.
        List<ServiceOperation<?, ?>> operations = remainingOperations()
                .stream()
                .filter(operation -> !(operation instanceof BindingFixtures.ListTagsOperation))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        operations.add(new ListTagsPageOperation());

        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(
                        () -> compiler.compile(GrpcBindingsAccess.declaration(getItemAndTheRestByName()), operations))
                .withMessage("gRPC method " + SERVICE + "/ListTags (list-tags): the output type Optional holds a "
                        + "PageResult, but a PageResult must be the operation's direct output");
    }

    @Test
    void bindingChecksRunBeforeTheMessagesAreMapped() {
        // A page in an Optional also can't be mapped to Item; the binding check's message is the one reported.
        ServiceDeclaration declaration = GrpcBindingsAccess
                .declaration(GrpcBindings
                        .service(CatalogServiceGrpc.getServiceDescriptor())
                        .unary(CatalogServiceGrpc.getGetItemMethod(), new BindingFixtures.OptionalPageOperation())
                        .bindAllByName()
                        .build());

        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> compiler.compile(declaration, remainingOperations()))
                .withMessageEndingWith("a PageResult must be the operation's direct output");
    }

    @Test
    void generatedCursorKeyIsReportedOnceWhenAMethodReturnsAPage() {
        PageCursors generated = generatedKeyCursors();
        ServiceCompiler pagingCompiler = new ServiceCompiler(generated, Compilers.PAGING);

        List<ILoggingEvent> first = warnings(() -> pagingCompiler.compile(pagedBooks(), List.of()));
        List<ILoggingEvent> again = warnings(() -> pagingCompiler.compile(pagedBooks(), List.of()));

        assertThat(first).singleElement().satisfies(event -> {
            assertThat(event.getLoggerName()).isEqualTo("io.americanexpress.synapse.core");
            assertThat(event.getFormattedMessage()).startsWith("synapse.paging.cursor.key is not set");
        });
        // Once per PageCursors bean, so once per context whichever plugins page.
        assertThat(again).isEmpty();
    }

    @Test
    void generatedCursorKeyIsReportedOnlyWithAPagedMethod() {
        ServiceCompiler plain = new ServiceCompiler(generatedKeyCursors(), Compilers.PAGING);

        assertThat(warnings(() -> plain
                .compile(GrpcBindingsAccess.declaration(getItemAndTheRestByName()), remainingOperations())))
                .isEmpty();
    }

    @Test
    void generatedCursorKeyIsNotReportedWhenCompilingFails() {
        ServiceCompiler failing = new ServiceCompiler(generatedKeyCursors(), Compilers.PAGING);
        ServiceDeclaration noItems = GrpcBindingsAccess
                .declaration(GrpcBindings
                        .service(NoItemsServiceGrpc.getServiceDescriptor())
                        .unary(NoItemsServiceGrpc.getListMethod(), new BindingFixtures.PagedBooksOperation())
                        .build());

        assertThat(warnings(() -> assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> failing.compile(noItems, List.of())))).isEmpty();
    }

    private static ServiceDeclaration pagedBooks() {
        return GrpcBindingsAccess
                .declaration(GrpcBindings
                        .service(PagedBookServiceGrpc.getServiceDescriptor())
                        .unary(PagedBookServiceGrpc.getListBooksMethod(), new BindingFixtures.PagedBooksOperation())
                        .unary(PagedBookServiceGrpc.getListTitlesMethod(), new BindingFixtures.PagedBooksOperation())
                        .unary(PagedBookServiceGrpc.getListAllMethod(), new BindingFixtures.ListAllBooksOperation())
                        .build());
    }

    /** Page cursors whose key is generated, as without {@code synapse.paging.cursor.key}. */
    private static PageCursors generatedKeyCursors() {
        SecureRandom random = new SecureRandom();
        CursorKeys keys = CursorKeys.from(new SynapsePagingProperties.Cursor(null, List.of(), null), random);
        return new PageCursors(new AesGcmCursorCodec(keys, Clock.systemUTC(), null, random), new InputDigest());
    }

    /** Runs the action and returns the WARN lines it logged on Synapse's loggers (gRPC's and core's). */
    private static List<ILoggingEvent> warnings(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger("io.americanexpress.synapse");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
    }

    @Test
    void mappingProblemIsABindingProblem() {
        ServiceDeclaration declaration = GrpcBindingsAccess
                .declaration(GrpcBindings
                        .service(CatalogServiceGrpc.getServiceDescriptor())
                        .unary(CatalogServiceGrpc.getGetItemMethod(), new SkuItemOperation())
                        .bindAllByName()
                        .build());

        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> compiler.compile(declaration, remainingOperations()))
                .satisfies(e -> {
                    assertThat(e.description())
                            .isEqualTo("gRPC method " + SERVICE
                                    + "/GetItem (sku-item): synapse.fixture.v1.Item.sku is part of oneof lookup");
                    assertThat(e.action()).isEqualTo("Map it explicitly with .map(\"sku\", ...).");
                    assertThat(e.getCause()).isInstanceOf(MappingException.class);
                });
    }

    @Test
    void presenceProblemsOfEveryMethodAreOneFailure() {
        ServiceDeclaration declaration = plain(new FirstPlainOperation(), new SecondPlainOperation());

        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> compiler.compile(declaration, List.of()))
                .satisfies(e -> {
                    assertThat(e.description())
                            .isEqualTo("gRPC method " + PLAIN + "/First (first-plain): FirstInput.title is required, "
                                    + "but " + PRESENCE + ".title has no presence, so an unsent field would bind its "
                                    + "default\n"
                                    + "gRPC method " + PLAIN + "/First (first-plain): FirstInput.pages is required, "
                                    + "but " + PRESENCE + ".pages has no presence, so an unsent field would bind its "
                                    + "default\n"
                                    + "gRPC method " + PLAIN + "/Second (second-plain): SecondInput.author is "
                                    + "required, but " + PRESENCE + ".author has no presence, so an unsent field "
                                    + "would bind its default");
                    assertThat(e.action())
                            .isEqualTo("Declare these fields optional (proto3 optional), use a wrapper type, or make "
                                    + "the components @Nullable.");
                    assertThat(e.getCause()).isInstanceOf(MappingException.class);
                    assertThat(e.getSuppressed()).singleElement().isInstanceOf(MappingException.class);
                });
    }

    @Test
    void otherMappingProblemsStillFailAtOnce() {
        // Pin (R-11): today's behaviour, kept: a problem other than presence fails at once, in each of these orders.
        String unmappable = "Change the component type, or map another field with .map(...).";

        // An unmappable type in the first method: today's failure, without the second method's presence problem.
        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> compiler
                        .compile(plain(new ListedPlainOperation(), new SecondPlainOperation()), List.of()))
                .satisfies(e -> {
                    assertThat(e.description())
                            .isEqualTo("gRPC method " + PLAIN + "/First (listed-plain): ListedInput.title can't "
                                    + "be mapped to " + PRESENCE + ".title (string)");
                    assertThat(e.action()).isEqualTo(unmappable);
                });
        // After a method that fails only on presence, another problem still fails at once.
        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> compiler
                        .compile(plain(new FirstPlainOperation(), new ListedPlainOperation()), List.of()))
                .satisfies(e -> assertThat(e.description())
                        .isEqualTo("gRPC method " + PLAIN + "/Second (listed-plain): ListedInput.title can't be "
                                + "mapped to " + PRESENCE + ".title (string)"));
        // The response of a method whose request fails only on presence is still checked, and its problem wins.
        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> compiler
                        .compile(plain(new TitledPlainOperation(), new SecondPlainOperation()), List.of()))
                .satisfies(e -> assertThat(e.description())
                        .isEqualTo("gRPC method " + PLAIN + "/First (titled-plain): TitledOutput.title has no field "
                                + "in google.protobuf.Empty"));
    }

    private static ServiceDeclaration plain(ServiceOperation<?, ?> first, ServiceOperation<?, ?> second) {
        return GrpcBindingsAccess
                .declaration(GrpcBindings
                        .service(PlainServiceGrpc.getServiceDescriptor())
                        .unary(PlainServiceGrpc.getFirstMethod(), first)
                        .unary(PlainServiceGrpc.getSecondMethod(), second)
                        .build());
    }

    @Test
    void compiledDescriptorKeepsNamesAndSchema() {
        ServiceDescriptor generated = CatalogServiceGrpc.getServiceDescriptor();

        ServerServiceDefinition definition = compiler
                .compile(GrpcBindingsAccess.declaration(getItemAndTheRestByName()), remainingOperations());

        ServiceDescriptor compiled = definition.getServiceDescriptor();
        assertThat(compiled.getName()).isEqualTo(generated.getName());
        assertThat(compiled.getSchemaDescriptor()).isSameAs(generated.getSchemaDescriptor());
        assertThat(compiled.getMethods())
                .extracting(MethodDescriptor::getFullMethodName)
                .containsExactlyElementsOf(METHODS);
        for (MethodDescriptor<?, ?> method : generated.getMethods()) {
            ServerMethodDefinition<?, ?> bound = definition.getMethod(method.getFullMethodName());
            assertThat(bound).isNotNull();
            MethodDescriptor<?, ?> descriptor = bound.getMethodDescriptor();
            assertThat(descriptor.getSchemaDescriptor()).isSameAs(method.getSchemaDescriptor());
            assertThat(descriptor.getType()).isEqualTo(MethodDescriptor.MethodType.UNARY);
            assertThat(descriptor.getRequestMarshaller()).isSameAs(RawBytesMarshaller.INSTANCE);
            assertThat(descriptor.getResponseMarshaller()).isInstanceOf(DynamicMessageMarshaller.class);
        }
    }

    @Test
    void aMethodWithoutProtobufMarshallersFails() {
        MethodDescriptor<byte[], byte[]> raw = MethodDescriptor
                .newBuilder(RawBytesMarshaller.INSTANCE, RawBytesMarshaller.INSTANCE)
                .setFullMethodName("test.RawService/Load")
                .setType(MethodDescriptor.MethodType.UNARY)
                .build();
        ServiceDescriptor service = ServiceDescriptor.newBuilder("test.RawService").addMethod(raw).build();
        ServiceDeclaration declaration = GrpcBindingsAccess
                .declaration(GrpcBindings
                        .service(service)
                        .unary(raw, new BindingFixtures.LoadItemOperation())
                        .build());

        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> compiler.compile(declaration, List.of()))
                .withMessage("gRPC method test.RawService/Load (load-item): the request marshaller is not a protobuf"
                        + " message marshaller");
    }

    @Test
    void aMethodWithoutAProtobufResponseMarshallerFails() {
        MethodDescriptor<GetItemRequest, byte[]> raw = MethodDescriptor
                .newBuilder(ProtoUtils.marshaller(GetItemRequest.getDefaultInstance()), RawBytesMarshaller.INSTANCE)
                .setFullMethodName("test.RawService/Load")
                .setType(MethodDescriptor.MethodType.UNARY)
                .build();
        ServiceDescriptor service = ServiceDescriptor.newBuilder("test.RawService").addMethod(raw).build();
        ServiceDeclaration declaration = GrpcBindingsAccess
                .declaration(GrpcBindings
                        .service(service)
                        .unary(raw, new BindingFixtures.LoadItemOperation())
                        .build());

        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> compiler.compile(declaration, List.of()))
                .withMessage("gRPC method test.RawService/Load (load-item): the response marshaller is not a protobuf"
                        + " message marshaller")
                .satisfies(e -> assertThat(e.action())
                        .isEqualTo("Bind the methods of a service generated by protoc with grpc-java's plugin."));
    }

    @Test
    void wrongServiceFailsBeforeAnythingIsResolved() {
        ServiceDeclaration declaration = GrpcBindingsAccess
                .declaration(GrpcBindings
                        .service(CatalogServiceGrpc.getServiceDescriptor())
                        .unary(WatchServiceGrpc.getWatchItemsMethod(), new BindingFixtures.WatchItemsOperation())
                        .build());

        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> compiler.compile(declaration, List.of()))
                .withMessage("gRPC method synapse.fixture.v1.WatchService/WatchItems (watch-items): belongs to"
                        + " synapse.fixture.v1.WatchService, not to " + SERVICE);
    }

    /** The input of {@link CreateItemOperation}: only what the request carries, so it binds by name. */
    record CreateItemInput(BindingFixtures.ItemRecord item) {
    }

    /** Named {@code create-item}. */
    static class CreateItemOperation implements CommandOperation<CreateItemInput, BindingFixtures.ItemRecord> {

        @Override
        public BindingFixtures.ItemRecord execute(CreateItemInput input) {
            return input.item();
        }
    }

    /** A second operation named {@code ping}. */
    @OperationName("ping")
    static class OtherPingOperation implements QueryOperation<Void, Void> {

        @Override
        public Void execute(Void input) {
            throw new UnsupportedOperationException();
        }
    }

    /** Named {@code get-item}, as the operation bound with {@code .unary} is. */
    static class GetItemOperation extends BindingFixtures.GetItemOperation {
    }

    /** Also named {@code get-item}. */
    @OperationName("get-item")
    static class OtherGetItemOperation extends BindingFixtures.GetItemOperation {
    }

    /** Named {@code list-tags}; returns a page in an {@code Optional}. */
    @OperationName("list-tags")
    static class ListTagsPageOperation extends BindingFixtures.OptionalPageOperation {
    }

    /** An output whose {@code sku} component is a oneof member, mapped without {@code .map}. */
    record SkuItem(String id, String sku) {
    }

    /** Returns a {@link SkuItem}. */
    static class SkuItemOperation implements QueryOperation<BindingFixtures.GetItem, SkuItem> {

        @Override
        public SkuItem execute(BindingFixtures.GetItem input) {
            return new SkuItem(input.id(), "SKU-1");
        }
    }

    /** Binds two required scalars of {@code Presence} that have no presence. */
    record FirstInput(String title, int pages) {
    }

    /** Named {@code first-plain}. */
    static class FirstPlainOperation implements QueryOperation<FirstInput, Void> {

        @Override
        public Void execute(FirstInput input) {
            throw new UnsupportedOperationException();
        }
    }

    /** Binds one required scalar of {@code Presence} that has no presence. */
    record SecondInput(String author) {
    }

    /** Named {@code second-plain}. */
    static class SecondPlainOperation implements QueryOperation<SecondInput, Void> {

        @Override
        public Void execute(SecondInput input) {
            throw new UnsupportedOperationException();
        }
    }

    /** A component that can't be mapped to {@code Presence.title}. */
    record ListedInput(List<String> title) {
    }

    /** Named {@code listed-plain}. */
    static class ListedPlainOperation implements QueryOperation<ListedInput, Void> {

        @Override
        public Void execute(ListedInput input) {
            throw new UnsupportedOperationException();
        }
    }

    /** An output that {@code google.protobuf.Empty} can't hold. */
    record TitledOutput(String title) {
    }

    /** Named {@code titled-plain}: its request fails on presence and its response can't be mapped. */
    static class TitledPlainOperation implements QueryOperation<SecondInput, TitledOutput> {

        @Override
        public TitledOutput execute(SecondInput input) {
            throw new UnsupportedOperationException();
        }
    }
}
