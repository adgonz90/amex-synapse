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

import java.util.List;

import io.grpc.MethodDescriptor;
import io.grpc.ServiceDescriptor;
import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.api.grpc.GrpcBindings;
import io.americanexpress.synapse.api.grpc.fixture.proto.CatalogServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.DocServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.Int32OrderByServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.Int64PageSizeServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.Int64SkipServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.ListRequestEtagServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.NoHasNextServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.NoItemsServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.NoNextPageTokenServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.NoOrderByServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.NoPageSizeServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.NoPageTokenServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.NoRequestEtagServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.NoResponseEtagServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.NumberHasNextServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.NumberNextPageTokenServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.NumberRequestEtagServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.OneofPageTokenServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.OneofRequestEtagServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.OptionalHasNextServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.PagedBookServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.PlainTotalServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.RepeatedOrderByServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.TwoItemsServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.WatchServiceGrpc;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.AuthorRecord;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.BookSort;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.ClashingSort;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.ConditionOutputOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.CreateDocOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.CreateItem;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.CreateItemOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.DeleteDocOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.DeleteItemOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.DocDeletion;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.DocUpdate;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.DocView;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.DollarSort;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.EchoOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.FindEntityOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.GetDocOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.GetItem;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.GetItemOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.GetTaggedDocOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.GrowOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.ItemErrors;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.ItemRecord;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.ItemSort;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.ListAllBooksOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.ListDocsOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.ListItems;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.ListItemsOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.ListTagsOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.LoadItemOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.NestedConditionOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.NestedPageOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.NoSort;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.ObjectConditionOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.Opaque;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.OptionalPageOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.PageInInputOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.PageItemsOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.PageOfPagesOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.PageQueryOutputOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.PagedBooksOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.PagedOpaque;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.PagedOpaqueOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.PagedRichOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.PagedWithSizeOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.PingOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.ReadDeletionOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.ReadUpdateOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.ShelveOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.TaggedDoc;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.TwoConditions;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.TwoConditionsOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.TwoPagesOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.Unrelated;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.UpdateDocOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.UuidConditionOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.WatchItemsOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.WideOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.WildcardConditionOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.WrappedConditionOperation;
import io.americanexpress.synapse.api.grpc.internal.service.Compilers;
import io.americanexpress.synapse.api.grpc.internal.service.GrpcBindingsAccess;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.binding.ComponentRef;
import io.americanexpress.synapse.core.conditional.VersionCondition;
import io.americanexpress.synapse.core.internal.conditional.VersionTokens;
import io.americanexpress.synapse.core.paging.PageQuery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class BindingChecksTest {

    private static final String CATALOG = "synapse.fixture.v1.CatalogService";

    private static final String GET_ITEM = "gRPC method " + CATALOG + "/GetItem (get-item): ";

    private static final String DOCS = "synapse.fixture.v1.DocService";

    private static final String DELETE_DOC = "gRPC method " + DOCS + "/DeleteDoc ";

    private static final String PAGED = "synapse.fixture.v1.PagedBookService";

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
    void pagingTypesPassTheDeclarationChecks() {
        // A PageQuery component and a PageResult output; their fields are checked when the messages are mapped.
        assertThatCode(() -> BindingChecks
                .check(GrpcBindingsAccess
                        .declaration(catalog()
                                .unary(CatalogServiceGrpc.getListTagsMethod(), new ListItemsOperation())
                                .unary(CatalogServiceGrpc.getGetItemMethod(), new PageItemsOperation())
                                .build())))
                .doesNotThrowAnyException();
    }

    @Test
    void pagingTypesOutsideTheirPlaceFail() {
        String listTags = "gRPC method " + CATALOG + "/ListTags ";
        String direct = "Return the PageResult itself from the operation, so Synapse writes it as the page and wraps "
                + "its cursor.";
        assertFails(catalog().unary(CatalogServiceGrpc.getListTagsMethod(), new NestedPageOperation()).build(),
                listTags + "(nested-page): NestedPage.inner holds a PageQuery, but gRPC binds one only as the input "
                        + "or as a component of the input record itself, from the request's page fields",
                "Declare the PageQuery as a component of NestedPage itself, not inside another type.");
        assertFails(catalog().unary(CatalogServiceGrpc.getListTagsMethod(), new TwoPagesOperation()).build(),
                listTags + "(two-pages): the input type TwoPages has more than one PageQuery component "
                        + "(TwoPages.first, TwoPages.second)",
                "Keep one PageQuery component: the request's page fields fill it.");
        assertFails(catalog().unary(CatalogServiceGrpc.getListTagsMethod(), new PageInInputOperation()).build(),
                listTags + "(page-in-input): the input type PageInInput holds a PageResult, which is output only",
                "Take a PageQuery in the input, and return the PageResult from the operation.");
        assertFails(catalog().unary(CatalogServiceGrpc.getListTagsMethod(), new PageQueryOutputOperation()).build(),
                listTags + "(page-query-output): the output type PageQueryOutput holds a PageQuery, which is input "
                        + "only",
                "Return a PageResult, or remove the PageQuery from the output.");
        assertFails(catalog().unary(CatalogServiceGrpc.getListTagsMethod(), new OptionalPageOperation()).build(),
                listTags + "(optional-page): the output type Optional holds a PageResult, but a PageResult must be "
                        + "the operation's direct output",
                direct);
        assertFails(catalog().unary(CatalogServiceGrpc.getListTagsMethod(), new PageOfPagesOperation()).build(),
                listTags + "(page-of-pages): the output type PageResult holds a PageResult, but a PageResult must be "
                        + "the operation's direct output",
                direct);
    }

    @Test
    void pageQueryBindsOnlyFromThePageFields() {
        String listTags = "gRPC method " + CATALOG + "/ListTags (list-items): ";
        assertFails(catalog()
                .unary(CatalogServiceGrpc.getListTagsMethod(), new ListItemsOperation())
                .map("id", ListItems::page)
                .build(),
                listTags + "ListItems.page is a PageQuery, which binds only from the request's page_size, page_token, "
                        + "skip and order_by fields, not from .map(\"id\", ...)",
                "Remove .map(\"id\", ...).");
        assertFails(catalog()
                .unary(CatalogServiceGrpc.getListTagsMethod(), new ListItemsOperation())
                .context(ListItems::page, context -> PageQuery.first(1))
                .build(),
                listTags + "ListItems.page is a PageQuery, which binds only from the request's page_size, page_token, "
                        + "skip and order_by fields, not from .context(...)",
                "Remove .context(...).");
        // Beside a PageQuery, no other component may read a page field (REST's rule for its paging parameters).
        assertCompileFails(pagedBooks()
                .unary(PagedBookServiceGrpc.getListBooksMethod(), new PagedWithSizeOperation())
                .unary(PagedBookServiceGrpc.getListTitlesMethod(), new PagedBooksOperation())
                .unary(PagedBookServiceGrpc.getListAllMethod(), new ListAllBooksOperation())
                .build(),
                "gRPC method " + PAGED + "/ListBooks (paged-with-size): PagedWithSize.pageSize is mapped to "
                        + "synapse.fixture.v1.ListPagedBooksRequest.page_size, a page field, and the input binds "
                        + "PageQuery from the page fields page_size, page_token, skip and order_by",
                "Map PagedWithSize.pageSize to another field with .map(...), or remove it from the input.");
    }

    @Test
    void pagedInputMustFitTheCursorDigest() {
        // As REST checks a paged route: a garbage token is always INVALID_ARGUMENT, never INTERNAL.
        assertFails(catalog()
                .unary(CatalogServiceGrpc.getListTagsMethod(), new PagedOpaqueOperation())
                .context(PagedOpaque::opaque, context -> new Opaque())
                .build(),
                "gRPC method " + CATALOG + "/ListTags (paged-opaque): PagedOpaque.opaque (type "
                        + Opaque.class.getName() + ") can't be bound into a paging cursor; use a record or simple "
                        + "type",
                "Make the component a record, an enum, a byte[], or a string, number, boolean, UUID or java.time "
                        + "value (or a List, a Set or a Map of them with simple keys), or remove it from the input of "
                        + "this paged method.");
        // bytes, a map with int keys, a set, and a VersionCondition (left out of the digest) are fine.
        assertThatCode(() -> BindingChecks
                .check(GrpcBindingsAccess
                        .declaration(catalog()
                                .unary(CatalogServiceGrpc.getListTagsMethod(), new PagedRichOperation())
                                .build())))
                .doesNotThrowAnyException();
    }

    @Test
    void sortableNeedsOrderBy() {
        String listTags = "gRPC method " + CATALOG + "/ListTags ";
        assertFails(catalog()
                .unary(CatalogServiceGrpc.getListTagsMethod(), new ListTagsOperation())
                .sortable(ItemSort.class)
                .build(),
                listTags + "(list-tags): .sortable(...) needs a method that binds PageQuery, but the input type "
                        + "GetItem has no PageQuery component",
                "Add a PageQuery component to the input, or remove .sortable(...) from this method.");
        assertFails(catalog()
                .unary(CatalogServiceGrpc.getListTagsMethod(), new ListItemsOperation())
                .sortable(ItemSort.class)
                .sortable(ItemSort.class)
                .build(), listTags + "(list-items): .sortable(...) is declared twice",
                "Declare .sortable once per method, with every key callers may sort by.");
        assertFails(catalog()
                .unary(CatalogServiceGrpc.getListTagsMethod(), new ListItemsOperation())
                .sortable(NoSort.class)
                .build(), listTags + "(list-items): .sortable(NoSort.class) names no sort keys",
                "Pass an enum with at least one constant, or remove .sortable(...) so that the method takes no "
                        + "order_by.");
        assertFails(catalog()
                .unary(CatalogServiceGrpc.getListTagsMethod(), new ListItemsOperation())
                .sortable(ClashingSort.class)
                .build(),
                listTags + "(list-items): the sort keys ClashingSort.NAME and ClashingSort.Name have the same "
                        + "order_by name name",
                "Give each constant a name that differs from the others in lower case.");
        assertFails(catalog()
                .unary(CatalogServiceGrpc.getListTagsMethod(), new ListItemsOperation())
                .sortable(DollarSort.class)
                .build(),
                listTags + "(list-items): the sort key DollarSort.TITLE$ is not a valid order_by name: in lower "
                        + "case, a constant's name must be made of letters, digits and '_'",
                "Rename the constant, or sort by another enum.");
        // The request message has no order_by.
        assertCompileFails(GrpcBindings
                .service(NoOrderByServiceGrpc.getServiceDescriptor())
                .unary(NoOrderByServiceGrpc.getListMethod(), new PagedBooksOperation())
                .sortable(BookSort.class)
                .build(),
                "gRPC method synapse.fixture.v1.NoOrderByService/List (paged-books): .sortable(...) sorts by the "
                        + "request's order_by, but synapse.fixture.v1.NoOrderByRequest has no string field order_by",
                "Add string order_by to synapse.fixture.v1.NoOrderByRequest, or remove .sortable(...).");
        // Without .sortable, the same request is fine: order_by is needed only to sort.
        assertThatCode(() -> Compilers
                .compiler()
                .compile(GrpcBindingsAccess
                        .declaration(GrpcBindings
                                .service(NoOrderByServiceGrpc.getServiceDescriptor())
                                .unary(NoOrderByServiceGrpc.getListMethod(), new PagedBooksOperation())
                                .build()),
                        List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void pagedMethodsWithAip158FieldsCompile() {
        assertThatCode(() -> Compilers
                .compiler()
                .compile(GrpcBindingsAccess
                        .declaration(pagedBooks()
                                .unary(PagedBookServiceGrpc.getListBooksMethod(), new PagedBooksOperation())
                                .sortable(BookSort.class)
                                .unary(PagedBookServiceGrpc.getListTitlesMethod(), new PagedBooksOperation())
                                .unary(PagedBookServiceGrpc.getListAllMethod(), new ListAllBooksOperation())
                                .build()),
                        List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void pagedMethodNeedsAip158Fields() {
        String tail = ": AIP-158's page fields carry the PageQuery.";
        String page = "PagedItems.page is a PageQuery, but synapse.fixture.v1.";
        assertPagedFails(NoPageSizeServiceGrpc.getServiceDescriptor(), NoPageSizeServiceGrpc.getListMethod(),
                page + "NoPageSizeRequest has no int32 field page_size",
                "Add int32 page_size to synapse.fixture.v1.NoPageSizeRequest" + tail);
        assertPagedFails(Int64PageSizeServiceGrpc.getServiceDescriptor(), Int64PageSizeServiceGrpc.getListMethod(),
                page + "Int64PageSizeRequest.page_size is int64, not int32",
                "Declare synapse.fixture.v1.Int64PageSizeRequest.page_size as int32" + tail);
        assertPagedFails(NoPageTokenServiceGrpc.getServiceDescriptor(), NoPageTokenServiceGrpc.getListMethod(),
                page + "NoPageTokenRequest has no string field page_token",
                "Add string page_token to synapse.fixture.v1.NoPageTokenRequest" + tail);
        assertPagedFails(OneofPageTokenServiceGrpc.getServiceDescriptor(),
                OneofPageTokenServiceGrpc.getListMethod(),
                page + "OneofPageTokenRequest.page_token is part of oneof position",
                "Move synapse.fixture.v1.OneofPageTokenRequest.page_token out of oneof position" + tail);
        assertPagedFails(Int64SkipServiceGrpc.getServiceDescriptor(), Int64SkipServiceGrpc.getListMethod(),
                page + "Int64SkipRequest.skip is int64, not int32",
                "Declare synapse.fixture.v1.Int64SkipRequest.skip as int32" + tail);

        String output = "the output PageResult<BookRecord> ";
        String token = output + "writes the next page's token to the response's string next_page_token, but "
                + "synapse.fixture.v1.";
        assertPagedFails(NoNextPageTokenServiceGrpc.getServiceDescriptor(),
                NoNextPageTokenServiceGrpc.getListMethod(),
                token + "NoNextPageTokenResponse has no string field next_page_token",
                "Add string next_page_token to synapse.fixture.v1.NoNextPageTokenResponse: AIP-158 pages carry it.");
        assertPagedFails(NumberNextPageTokenServiceGrpc.getServiceDescriptor(),
                NumberNextPageTokenServiceGrpc.getListMethod(),
                token + "NumberNextPageTokenResponse.next_page_token is int32, not string",
                "Declare synapse.fixture.v1.NumberNextPageTokenResponse.next_page_token as string: AIP-158 pages "
                        + "carry it.");
        String items = output + "writes its items to the response's one repeated message field, but "
                + "synapse.fixture.v1.";
        assertPagedFails(NoItemsServiceGrpc.getServiceDescriptor(), NoItemsServiceGrpc.getListMethod(),
                items + "NoItemsResponse has none",
                "Give synapse.fixture.v1.NoItemsResponse one repeated message field for the items, as AIP-158 pages "
                        + "do.");
        assertPagedFails(TwoItemsServiceGrpc.getServiceDescriptor(), TwoItemsServiceGrpc.getListMethod(),
                items + "TwoItemsResponse has 2 (books, more)",
                "Give synapse.fixture.v1.TwoItemsResponse one repeated message field for the items, as AIP-158 pages "
                        + "do.");
        assertPagedFails(PlainTotalServiceGrpc.getServiceDescriptor(), PlainTotalServiceGrpc.getListMethod(),
                output + "writes its total to the response's optional int32 or int64 total_size, but "
                        + "synapse.fixture.v1.PlainTotalResponse.total_size has no presence",
                "Declare synapse.fixture.v1.PlainTotalResponse.total_size as optional int32 or optional int64, so "
                        + "that an unknown total stays unset.");
        assertPagedFails(NoHasNextServiceGrpc.getServiceDescriptor(), NoHasNextServiceGrpc.getListMethod(),
                output + "writes whether another page follows to the response's bool has_next, but "
                        + "synapse.fixture.v1.NoHasNextResponse has no bool field has_next",
                "Add bool has_next to synapse.fixture.v1.NoHasNextResponse: Synapse pages carry it, as REST's "
                        + "hasNext.");
        assertPagedFails(NumberHasNextServiceGrpc.getServiceDescriptor(), NumberHasNextServiceGrpc.getListMethod(),
                output + "writes whether another page follows to the response's bool has_next, but "
                        + "synapse.fixture.v1.NumberHasNextResponse.has_next is int32, not bool",
                "Declare synapse.fixture.v1.NumberHasNextResponse.has_next as bool: Synapse pages carry it, as REST's "
                        + "hasNext.");
        // A proto3 optional bool has_next is fine: it is always written.
        assertThatCode(() -> Compilers
                .compiler()
                .compile(GrpcBindingsAccess
                        .declaration(GrpcBindings
                                .service(OptionalHasNextServiceGrpc.getServiceDescriptor())
                                .unary(OptionalHasNextServiceGrpc.getListMethod(), new PagedBooksOperation())
                                .build()),
                        List.of()))
                .doesNotThrowAnyException();
        // order_by of another type fails with .sortable, which reads it.
        String orderBy = ".sortable(...) sorts by the request's order_by, but synapse.fixture.v1.";
        assertCompileFails(GrpcBindings
                .service(Int32OrderByServiceGrpc.getServiceDescriptor())
                .unary(Int32OrderByServiceGrpc.getListMethod(), new PagedBooksOperation())
                .sortable(BookSort.class)
                .build(),
                "gRPC method synapse.fixture.v1.Int32OrderByService/List (paged-books): " + orderBy
                        + "Int32OrderByRequest.order_by is int32, not string",
                "Declare synapse.fixture.v1.Int32OrderByRequest.order_by as string, or remove .sortable(...).");
        assertCompileFails(GrpcBindings
                .service(RepeatedOrderByServiceGrpc.getServiceDescriptor())
                .unary(RepeatedOrderByServiceGrpc.getListMethod(), new PagedBooksOperation())
                .sortable(BookSort.class)
                .build(),
                "gRPC method synapse.fixture.v1.RepeatedOrderByService/List (paged-books): " + orderBy
                        + "RepeatedOrderByRequest.order_by is repeated string, not string",
                "Declare synapse.fixture.v1.RepeatedOrderByRequest.order_by as string, or remove .sortable(...).");
    }

    /** Compiles {@link PagedBooksOperation} on a one-method service, and checks the failure. */
    private static void assertPagedFails(ServiceDescriptor service, MethodDescriptor<?, ?> method, String problem,
            String action) {
        assertCompileFails(GrpcBindings.service(service).unary(method, new PagedBooksOperation()).build(),
                "gRPC method " + method.getFullMethodName() + " (paged-books): " + problem, action);
    }

    private static GrpcBindings.Builder pagedBooks() {
        return GrpcBindings.service(PagedBookServiceGrpc.getServiceDescriptor());
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

    @Test
    void versionConditionNeedsAStringEtagField() {
        String action = "Add string etag to synapse.fixture.v1.%s: it carries the version as an entity tag, such as "
                + "\"5\" or *.";
        assertCompileFails(GrpcBindings
                .service(NoRequestEtagServiceGrpc.getServiceDescriptor())
                .unary(NoRequestEtagServiceGrpc.getDeleteMethod(), new DeleteDocOperation())
                .build(),
                "gRPC method synapse.fixture.v1.NoRequestEtagService/Delete (delete-doc): DocDeletion.version is a "
                        + "VersionCondition, but synapse.fixture.v1.GetDocRequest has no string field etag",
                action.formatted("GetDocRequest"));
        assertCompileFails(GrpcBindings
                .service(NumberRequestEtagServiceGrpc.getServiceDescriptor())
                .unary(NumberRequestEtagServiceGrpc.getDeleteMethod(), new DeleteDocOperation())
                .build(),
                "gRPC method synapse.fixture.v1.NumberRequestEtagService/Delete (delete-doc): DocDeletion.version is "
                        + "a VersionCondition, but synapse.fixture.v1.NumberEtagRequest.etag is int64, not string",
                "Declare synapse.fixture.v1.NumberEtagRequest.etag as string: it carries the version as an entity "
                        + "tag, such as \"5\" or *.");
        assertCompileFails(GrpcBindings
                .service(ListRequestEtagServiceGrpc.getServiceDescriptor())
                .unary(ListRequestEtagServiceGrpc.getDeleteMethod(), new DeleteDocOperation())
                .build(),
                "gRPC method synapse.fixture.v1.ListRequestEtagService/Delete (delete-doc): DocDeletion.version is "
                        + "a VersionCondition, but synapse.fixture.v1.ListEtagRequest.etag is repeated string, not "
                        + "string",
                "Declare synapse.fixture.v1.ListEtagRequest.etag as string: it carries the version as an entity tag, "
                        + "such as \"5\" or *.");
        assertCompileFails(GrpcBindings
                .service(OneofRequestEtagServiceGrpc.getServiceDescriptor())
                .unary(OneofRequestEtagServiceGrpc.getDeleteMethod(), new DeleteDocOperation())
                .build(),
                "gRPC method synapse.fixture.v1.OneofRequestEtagService/Delete (delete-doc): DocDeletion.version is "
                        + "a VersionCondition, but synapse.fixture.v1.OneofEtagRequest.etag is part of oneof pick",
                "Move synapse.fixture.v1.OneofEtagRequest.etag out of oneof pick: it carries the version as an entity "
                        + "tag, such as \"5\" or *.");
    }

    @Test
    void versionConditionNeedsAVersionTypeOfTheSharedCodec() {
        assertFails(docs().unary(DocServiceGrpc.getDeleteDocMethod(), new UuidConditionOperation()).build(),
                DELETE_DOC + "(uuid-condition): UuidCondition.version is a VersionCondition<UUID>, which is not a "
                        + "version type",
                "Declare a version type: " + VersionTokens.TYPES + ".");
    }

    @Test
    void twoVersionConditionsFail() {
        GrpcBindings bindings = docs()
                .unary(DocServiceGrpc.getDeleteDocMethod(), new TwoConditionsOperation())
                .build();

        assertFails(bindings,
                DELETE_DOC + "(two-conditions): the input TwoConditions has 2 VersionCondition components bound to "
                        + "the etag field (version, other); a gRPC request carries one per field",
                "Bind the If-None-Match one with .map(\"if_none_match\", ...), or keep one VersionCondition component "
                        + "per field.");
    }

    @Test
    void oneVersionConditionPerFieldIsAllowed() {
        // As REST binds one component from If-Match and one from If-None-Match.
        GrpcBindings bindings = docs()
                .unary(DocServiceGrpc.getUpdateDocMethod(), new TwoConditionsOperation())
                .map("if_none_match", TwoConditions::other)
                .build();
        GrpcBindings explicit = docs()
                .unary(DocServiceGrpc.getUpdateDocMethod(), new TwoConditionsOperation())
                .map("etag", TwoConditions::version)
                .map("if_none_match", TwoConditions::other)
                .build();
        GrpcBindings twoIfNoneMatch = docs()
                .unary(DocServiceGrpc.getUpdateDocMethod(), new TwoConditionsOperation())
                .map("if_none_match", TwoConditions::version)
                .map("if_none_match", TwoConditions::other)
                .build();

        assertThatCode(() -> BindingChecks.check(GrpcBindingsAccess.declaration(bindings))).doesNotThrowAnyException();
        assertThatCode(() -> BindingChecks.check(GrpcBindingsAccess.declaration(explicit))).doesNotThrowAnyException();
        assertFails(twoIfNoneMatch,
                "gRPC method " + DOCS + "/UpdateDoc (two-conditions): the input TwoConditions has 2 VersionCondition "
                        + "components bound to the if_none_match field (version, other); a gRPC request carries one "
                        + "per field",
                "Bind the If-None-Match one with .map(\"if_none_match\", ...), or keep one VersionCondition component "
                        + "per field.");
    }

    @Test
    void etagWithoutResponseFieldFails() {
        assertCompileFails(GrpcBindings
                .service(NoResponseEtagServiceGrpc.getServiceDescriptor())
                .unary(NoResponseEtagServiceGrpc.getGetMethod(), new GetDocOperation())
                .etag(DocView::version)
                .build(),
                "gRPC method synapse.fixture.v1.NoResponseEtagService/Get (get-doc): .etag(...) writes the response's "
                        + "string field etag, but synapse.fixture.v1.PlainDoc has no string field etag",
                "Add string etag to synapse.fixture.v1.PlainDoc, or remove .etag(...).");
    }

    @Test
    void etagOnAConditionalWriteFails() {
        GrpcBindings update = docs()
                .unary(DocServiceGrpc.getUpdateDocMethod(), new UpdateDocOperation())
                .etag(DocView::version)
                .build();

        assertFails(update,
                "gRPC method " + DOCS + "/UpdateDoc (update-doc): .etag(...) can't be used on a write whose input has "
                        + "a VersionCondition: Synapse sends no etag in an update or delete response, as REST sends "
                        + "no ETag on PUT or DELETE",
                "Remove .etag(...) from this method, and declare it on the method clients read the resource from.");
    }

    @Test
    void etagOnAQueryAndOnACreateIsAllowed() {
        GrpcBindings bindings = docs()
                .unary(DocServiceGrpc.getGetDocMethod(), new GetDocOperation())
                .etag(DocView::version)
                .unary(DocServiceGrpc.getCreateDocMethod(), new CreateDocOperation())
                .etag(DocView::version)
                .unary(DocServiceGrpc.getUpdateDocMethod(), new UpdateDocOperation())
                .unary(DocServiceGrpc.getDeleteDocMethod(), new DeleteDocOperation())
                .build();

        assertThatCode(() -> BindingChecks.check(GrpcBindingsAccess.declaration(bindings))).doesNotThrowAnyException();
        assertThatCode(() -> Compilers.compiler().compile(GrpcBindingsAccess.declaration(bindings), List.of()))
                .doesNotThrowAnyException();
        assertThat(GrpcBindingsAccess.declaration(bindings).methods().getFirst().etag())
                .satisfies(etag -> {
                    assertThat(etag).isNotNull();
                    assertThat(etag.recordType()).isEqualTo(DocView.class);
                    assertThat(etag.name()).isEqualTo("version");
                });
        assertThat(GrpcBindingsAccess.declaration(bindings).methods().get(2).etag()).isNull();
    }

    @Test
    void requiredVersionConditionOnAReadFails() {
        // REST's rule: on a read the component must be @Nullable, since a read can't require a precondition.
        GrpcBindings bindings = docs()
                .unary(DocServiceGrpc.getDeleteDocMethod(), new ReadDeletionOperation())
                .build();

        assertFails(bindings,
                DELETE_DOC + "(read-deletion): DocDeletion.version is a VersionCondition that isn't @Nullable, but "
                        + "the operation is a QueryOperation: a read can't require a precondition",
                "Declare DocDeletion.version @Nullable, or bind a CommandOperation.");
    }

    @Test
    void optionalVersionConditionOnAReadIsAllowedWithEtag() {
        // A read may pass a condition to its operation, as REST binds a @Nullable one from If-None-Match on GET; a
        // read's response carries the etag.
        GrpcBindings bindings = docs()
                .unary(DocServiceGrpc.getUpdateDocMethod(), new ReadUpdateOperation())
                .map("if_none_match", DocUpdate::version)
                .etag(DocView::version)
                .build();

        assertThatCode(() -> BindingChecks.check(GrpcBindingsAccess.declaration(bindings))).doesNotThrowAnyException();
    }

    @Test
    void versionConditionOutsideTheInputRecordFails() {
        String action = "Declare the VersionCondition as a component of %s itself, not inside another type.";
        assertFails(docs().unary(DocServiceGrpc.getDeleteDocMethod(), new NestedConditionOperation()).build(),
                DELETE_DOC + "(nested-condition): NestedCondition.deletion holds a VersionCondition, but gRPC binds "
                        + "one only as a component of the input record itself, from the request's etag or "
                        + "if_none_match field",
                action.formatted("NestedCondition"));
        assertFails(docs().unary(DocServiceGrpc.getDeleteDocMethod(), new WrappedConditionOperation()).build(),
                DELETE_DOC + "(wrapped-condition): WrappedCondition.version holds a VersionCondition, but gRPC binds "
                        + "one only as a component of the input record itself, from the request's etag or "
                        + "if_none_match field",
                action.formatted("WrappedCondition"));
        assertFails(docs().unary(DocServiceGrpc.getGetDocMethod(), new ConditionOutputOperation()).build(),
                "gRPC method " + DOCS + "/GetDoc (condition-output): the output type DocDeletion holds a "
                        + "VersionCondition, which is input only",
                "Return the version itself, such as a long, and send it as the etag with .etag(...).");
    }

    @Test
    void versionConditionBindsOnlyFromATokenField() {
        String problem = DELETE_DOC + "(delete-doc): DocDeletion.version is a VersionCondition, which binds only from "
                + "the request's etag or if_none_match field, not from ";
        assertFails(docs()
                .unary(DocServiceGrpc.getDeleteDocMethod(), new DeleteDocOperation())
                .map("id", DocDeletion::version)
                .build(), problem + ".map(\"id\", ...)",
                "Map it to \"etag\" (If-Match) or \"if_none_match\" (If-None-Match), or remove .map(\"id\", ...).");
        assertFails(docs()
                .unary(DocServiceGrpc.getDeleteDocMethod(), new DeleteDocOperation())
                .context(DocDeletion::version, context -> VersionCondition.any())
                .build(), problem + ".context(...)", "Remove .context(...).");
        // The two token fields are allowed, as REST's .header("If-Match", ...) and .header("If-None-Match", ...).
        for (String field : List.of("etag", "if_none_match")) {
            assertThatCode(() -> BindingChecks
                    .check(GrpcBindingsAccess
                            .declaration(docs()
                                    .unary(DocServiceGrpc.getDeleteDocMethod(), new DeleteDocOperation())
                                    .map(field, DocDeletion::version)
                                    .build())))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void versionConditionNeedsAConcreteVersionType() {
        assertFails(docs().unary(DocServiceGrpc.getDeleteDocMethod(), new WildcardConditionOperation()).build(),
                DELETE_DOC + "(wildcard-condition): WildcardCondition.version is a VersionCondition without a version "
                        + "type",
                "Declare the version type, for example VersionCondition<Long>.");
        assertFails(docs().unary(DocServiceGrpc.getDeleteDocMethod(), new ObjectConditionOperation()).build(),
                DELETE_DOC + "(object-condition): ObjectCondition.version is a VersionCondition<Object>; the version "
                        + "type must be a concrete type such as Long or String",
                "Declare the type of the resource's version, for example VersionCondition<Long>: the etag's value is "
                        + "converted to it and compared with the version the operation reads.");
    }

    @Test
    void etagNeedsOneSimpleComponentOfASingleOutput() {
        String getDoc = "gRPC method " + DOCS + "/GetDoc ";
        assertFails(docs()
                .unary(DocServiceGrpc.getGetDocMethod(), new GetDocOperation())
                .etag(DocView::version)
                .etag(DocView::id)
                .build(), getDoc + "(get-doc): .etag(...) is declared twice",
                "Declare .etag(...) once per method, with the output component that versions the resource.");
        assertFails(docs()
                .unary(DocServiceGrpc.getGetDocMethod(), new ListDocsOperation())
                .etag(DocView::version)
                .build(), getDoc + "(list-docs): .etag(...) needs a single output, but the output type is List",
                "Return a record or an Optional of one, or remove .etag(...).");
        assertFails(docs()
                .unary(DocServiceGrpc.getGetDocMethod(), new GetDocOperation())
                .etag(Unrelated::value)
                .build(),
                getDoc + "(get-doc): the component Unrelated.value of .etag(...) is not part of the output "
                        + "type DocView",
                "Reference a component of DocView, such as its version.");
        assertFails(docs()
                .unary(DocServiceGrpc.getGetDocMethod(), new GetTaggedDocOperation())
                .etag(TaggedDoc::tags)
                .build(),
                getDoc + "(get-tagged-doc): the etag needs a version type, but TaggedDoc.tags is "
                        + "java.util.List<java.lang.String>",
                "Use a component of a version type for the etag: " + VersionTokens.TYPES + ".");
        // A boolean was a "simple type" before; it isn't a version type, on gRPC as on REST.
        assertFails(docs()
                .unary(DocServiceGrpc.getGetDocMethod(), new GetTaggedDocOperation())
                .etag(TaggedDoc::archived)
                .build(),
                getDoc + "(get-tagged-doc): the etag needs a version type, but TaggedDoc.archived is boolean",
                "Use a component of a version type for the etag: " + VersionTokens.TYPES + ".");
    }

    private static GrpcBindings.Builder docs() {
        return GrpcBindings.service(DocServiceGrpc.getServiceDescriptor());
    }

    private static void assertCompileFails(GrpcBindings bindings, String description, String action) {
        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> Compilers.compiler().compile(GrpcBindingsAccess.declaration(bindings), List.of()))
                .satisfies(e -> {
                    assertThat(e.description()).isEqualTo(description);
                    assertThat(e.action()).isEqualTo(action);
                });
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
