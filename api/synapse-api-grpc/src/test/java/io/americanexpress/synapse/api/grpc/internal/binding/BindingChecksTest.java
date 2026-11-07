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
package io.americanexpress.synapse.api.grpc.internal.binding;

import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.api.grpc.GrpcBindings;
import io.americanexpress.synapse.api.grpc.fixture.proto.CatalogServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.WatchServiceGrpc;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.AuthorRecord;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.CreateItem;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.CreateItemOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.DeleteItemOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.EchoOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.FindEntityOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.GetItem;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.GetItemOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.GrowOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.ItemErrors;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.ItemRecord;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.ListItemsOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.ListTagsOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.LoadItemOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.PageItemsOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.PingOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.ShelveOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.Unrelated;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.WatchItemsOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.WideOperation;
import io.americanexpress.synapse.api.grpc.internal.service.GrpcBindingsAccess;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.binding.ComponentRef;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class BindingChecksTest {

    private static final String CATALOG = "synapse.fixture.v1.CatalogService";

    private static final String GET_ITEM = "gRPC method " + CATALOG + "/GetItem (get-item): ";

    @Test
    void validBindingsPass() {
        GrpcBindings bindings = catalog()
                .unary(CatalogServiceGrpc.getGetItemMethod(), new GetItemOperation())
                .map("id", GetItem::id)
                .map("title", ItemRecord::name)
                .notFound(ItemErrors.ITEM_NOT_FOUND)
                .unary(CatalogServiceGrpc.getCreateItemMethod(), new CreateItemOperation())
                .map("item.author.name", AuthorRecord::name)
                .context(CreateItem::channel, context -> "web")
                .unary(CatalogServiceGrpc.getDeleteItemMethod(), new DeleteItemOperation())
                .unary(CatalogServiceGrpc.getPingMethod(), new PingOperation())
                .unary(CatalogServiceGrpc.getListTagsMethod(), new ListTagsOperation())
                .build();

        assertThatCode(() -> BindingChecks.check(GrpcBindingsAccess.declaration(bindings))).doesNotThrowAnyException();
    }

    @Test
    void componentsReachedThroughArraysAndMapsPass() {
        GrpcBindings bindings = catalog()
                .unary(CatalogServiceGrpc.getCreateItemMethod(), new ShelveOperation())
                .map("writer", AuthorRecord::name)
                .map("key", ItemRecord::id)
                .build();

        assertThatCode(() -> BindingChecks.check(GrpcBindingsAccess.declaration(bindings))).doesNotThrowAnyException();
    }

    @Test
    void methodOfAnotherServiceFails() {
        GrpcBindings bindings = catalog()
                .unary(WatchServiceGrpc.getWatchItemsMethod(), new WatchItemsOperation())
                .build();

        assertFails(bindings,
                "gRPC method synapse.fixture.v1.WatchService/WatchItems (watch-items): belongs to "
                        + "synapse.fixture.v1.WatchService, not to " + CATALOG,
                "Use the method descriptors of the service the bindings were created for.");
    }

    @Test
    void streamingMethodFails() {
        GrpcBindings bindings = GrpcBindings
                .service(WatchServiceGrpc.getServiceDescriptor())
                .unary(WatchServiceGrpc.getWatchItemsMethod(), new WatchItemsOperation())
                .build();

        assertFails(bindings,
                "gRPC method synapse.fixture.v1.WatchService/WatchItems (watch-items): is SERVER_STREAMING; only "
                        + "unary methods can be bound in Synapse 2.0",
                "Implement it in a hand-written *ImplBase service.");
    }

    @Test
    void methodDeclaredTwiceFails() {
        GrpcBindings bindings = catalog()
                .unary(CatalogServiceGrpc.getGetItemMethod(), new GetItemOperation())
                .unary(CatalogServiceGrpc.getDeleteItemMethod(), new DeleteItemOperation())
                .unary(CatalogServiceGrpc.getGetItemMethod(), new LoadItemOperation())
                .build();

        assertFails(bindings, "gRPC method " + CATALOG + "/GetItem (load-item): bound more than once",
                "Bind each method once.");
    }

    @Test
    void componentOfAnotherRecordFails() {
        GrpcBindings bindings = catalog()
                .unary(CatalogServiceGrpc.getGetItemMethod(), new GetItemOperation())
                .map("id", Unrelated::value)
                .build();

        assertFails(bindings,
                GET_ITEM + ".map(\"id\", ...) names Unrelated.value, which is not in the input type GetItem or the "
                        + "output type ItemRecord",
                "Name a component of the operation's input or output record, or of a record nested in one.");
    }

    @Test
    void contextComponentOutsideTheInputFails() {
        GrpcBindings bindings = catalog()
                .unary(CatalogServiceGrpc.getGetItemMethod(), new GetItemOperation())
                .context(asInput(Unrelated::value), context -> "x")
                .build();

        assertFails(bindings,
                GET_ITEM + ".context(...) names Unrelated.value, which is not in the input type GetItem",
                "Name a component of the operation's input record, or of a record nested in it.");
    }

    @Test
    void contextComponentOfTheOutputFails() {
        GrpcBindings bindings = catalog()
                .unary(CatalogServiceGrpc.getGetItemMethod(), new GetItemOperation())
                .context(asInput(ItemRecord::name), context -> "x")
                .build();

        assertFails(bindings, GET_ITEM + ".context(...) names ItemRecord.name, which is not in the input type GetItem",
                "Name a component of the operation's input record, or of a record nested in it.");
    }

    @Test
    void twoSourcesFail() {
        GrpcBindings mapAndContext = catalog()
                .unary(CatalogServiceGrpc.getGetItemMethod(), new GetItemOperation())
                .map("id", GetItem::id)
                .context(GetItem::id, context -> "x")
                .build();
        GrpcBindings twoMaps = catalog()
                .unary(CatalogServiceGrpc.getGetItemMethod(), new GetItemOperation())
                .map("id", GetItem::id)
                .map("key", GetItem::id)
                .build();
        GrpcBindings twoContexts = catalog()
                .unary(CatalogServiceGrpc.getGetItemMethod(), new GetItemOperation())
                .context(GetItem::id, context -> "x")
                .context(GetItem::id, context -> "y")
                .build();

        String action = "Declare one source for each component.";
        assertFails(mapAndContext, GET_ITEM + "GetItem.id has more than one source: .map(\"id\", ...) and "
                + ".context(...)", action);
        assertFails(twoMaps, GET_ITEM + "GetItem.id has more than one source: .map(\"id\", ...) and "
                + ".map(\"key\", ...)", action);
        assertFails(twoContexts, GET_ITEM + "GetItem.id has more than one source: .context(...) and "
                + ".context(...)", action);
    }

    @Test
    void pagingTypesFail() {
        GrpcBindings pageQuery = catalog()
                .unary(CatalogServiceGrpc.getListTagsMethod(), new ListItemsOperation())
                .build();
        GrpcBindings pageResult = catalog()
                .unary(CatalogServiceGrpc.getListTagsMethod(), new PageItemsOperation())
                .build();

        String action = "Expose this operation over REST until then.";
        assertFails(pageQuery, "gRPC method " + CATALOG + "/ListTags (list-items): PageQuery is not supported over "
                + "gRPC yet; gRPC page tokens come in plan 3b", action);
        assertFails(pageResult, "gRPC method " + CATALOG + "/ListTags (page-items): PageResult is not supported "
                + "over gRPC yet; gRPC page tokens come in plan 3b", action);
    }

    @Test
    void entityTypeFails() {
        GrpcBindings bindings = catalog()
                .unary(CatalogServiceGrpc.getGetItemMethod(), new FindEntityOperation())
                .build();

        assertFails(bindings,
                "gRPC method " + CATALOG + "/GetItem (find-entity): io.americanexpress.synapse.api.grpc.internal."
                        + "binding.fixture.BindingFixtures$ItemEntity is a persistence entity",
                "Bind a record from the service's model and map the entity in the operation.");
    }

    @Test
    void inputThatIsNotARecordFails() {
        GrpcBindings bindings = catalog()
                .unary(CatalogServiceGrpc.getGetItemMethod(), new EchoOperation())
                .build();

        assertFails(bindings,
                "gRPC method " + CATALOG + "/GetItem (echo): the input type java.lang.String is not a record or Void",
                "Declare the operation's input as a record, or as Void if it takes none.");
    }

    @Test
    void notFoundOnNonOptionalFails() {
        GrpcBindings bindings = catalog()
                .unary(CatalogServiceGrpc.getGetItemMethod(), new LoadItemOperation())
                .notFound(ItemErrors.ITEM_NOT_FOUND)
                .build();

        assertFails(bindings,
                "gRPC method " + CATALOG + "/GetItem (load-item): .notFound(...) needs an Optional output, but the "
                        + "output type is ItemRecord",
                "Return an Optional from the operation, or remove .notFound(...).");
    }

    @Test
    void typeDeeperThanTheWalkFollowsFails() {
        GrpcBindings bindings = catalog()
                .unary(CatalogServiceGrpc.getPingMethod(), new GrowOperation())
                .build();

        assertFails(bindings,
                "gRPC method " + CATALOG + "/Ping (grow): the input type Grow is too deeply nested: more than 32 "
                        + "levels of records and containers",
                "Flatten the operation's input and output types.");
    }

    @Test
    void typeWithMoreTypesThanTheWalkVisitsFails() {
        GrpcBindings bindings = catalog()
                .unary(CatalogServiceGrpc.getGetItemMethod(), new WideOperation())
                .build();

        assertFails(bindings,
                "gRPC method " + CATALOG + "/GetItem (wide): the output type Wide1 is too deeply nested: more than "
                        + "10000 nested types",
                "Flatten the operation's input and output types.");
    }

    @Test
    void failureNamesTheMethodAndOperation() {
        MethodDeclaration method = GrpcBindingsAccess
                .declaration(catalog()
                        .unary(CatalogServiceGrpc.getGetItemMethod(), new GetItemOperation())
                        .build())
                .methods()
                .getFirst();

        SynapseConfigurationException failure = BindingChecks.failure(method, "a problem", "An action.");

        assertThat(failure.description()).isEqualTo(GET_ITEM + "a problem");
        assertThat(failure.action()).isEqualTo("An action.");
    }

    private static GrpcBindings.Builder catalog() {
        return GrpcBindings.service(CatalogServiceGrpc.getServiceDescriptor());
    }

    /**
     * Passes a component of another record where an input component is expected, as raw types allow: the compiler
     * rejects it otherwise.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T, I> ComponentRef<I, String> asInput(ComponentRef<T, String> component) {
        return (ComponentRef) component;
    }

    private static void assertFails(GrpcBindings bindings, String description, String action) {
        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> BindingChecks.check(GrpcBindingsAccess.declaration(bindings)))
                .satisfies(e -> {
                    assertThat(e.description()).isEqualTo(description);
                    assertThat(e.action()).isEqualTo(action);
                });
    }
}
