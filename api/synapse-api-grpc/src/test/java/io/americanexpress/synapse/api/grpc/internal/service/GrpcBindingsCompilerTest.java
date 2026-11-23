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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import io.americanexpress.synapse.api.grpc.GrpcBindings;
import io.americanexpress.synapse.api.grpc.fixture.proto.CatalogServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.DocServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.PagedBookServiceGrpc;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapsePagingProperties;
import io.americanexpress.synapse.core.internal.binding.BindingDeclaration;
import io.americanexpress.synapse.core.internal.binding.BindingDeclarations;
import io.americanexpress.synapse.core.internal.paging.PageCursors;
import io.americanexpress.synapse.core.operation.ServiceOperation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class GrpcBindingsCompilerTest {

    private static final String SERVICE = "synapse.fixture.v1.CatalogService";

    private static ObjectProvider<ServiceCompiler> compiler(ServiceCompiler compiler) {
        return new StaticListableBeanFactory(Map.of("compiler", compiler)).getBeanProvider(ServiceCompiler.class);
    }

    private static ObjectProvider<PageCursors> cursors() {
        return new StaticListableBeanFactory(Map.of("cursors", Compilers.cursors()))
                .getBeanProvider(PageCursors.class);
    }

    private static ObjectProvider<SynapsePagingProperties> paging() {
        return new StaticListableBeanFactory(Map.of("paging", Compilers.PAGING))
                .getBeanProvider(SynapsePagingProperties.class);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ServiceOperation<?, ?>> operations(ServiceOperation<?, ?>... operations) {
        Map<String, Object> beans = new LinkedHashMap<>();
        for (ServiceOperation<?, ?> operation : operations) {
            beans.put(operation.getClass().getName(), operation);
        }
        return (ObjectProvider<ServiceOperation<?, ?>>) (ObjectProvider<?>) new StaticListableBeanFactory(beans)
                .getBeanProvider(ServiceOperation.class);
    }

    private static ObjectProvider<ServiceOperation<?, ?>> remainingOperations() {
        return operations(new BindingFixtures.DeleteItemOperation(), new BindingFixtures.PingOperation(),
                new BindingFixtures.ListTagsOperation(), new BindingFixtures.FailOperation());
    }

    private static GrpcBindings byName() {
        return GrpcBindings
                .service(CatalogServiceGrpc.getServiceDescriptor())
                .unary(CatalogServiceGrpc.getGetItemMethod(), new BindingFixtures.GetItemOperation())
                .unary(CatalogServiceGrpc.getCreateItemMethod(), new BindingFixtures.CreateItemOperation())
                .context(BindingFixtures.CreateItem::channel, context -> "grpc")
                .bindAllByName()
                .build();
    }

    @Test
    void compilesEachGrpcBindingsBean() {
        GrpcBindings bindings = byName();
        GrpcBindingsCompiler postProcessor = new GrpcBindingsCompiler(compiler(Compilers.compiler()),
                remainingOperations(), cursors(), paging(), declarations());

        assertThat(postProcessor.postProcessAfterInitialization(bindings, "catalog")).isSameAs(bindings);

        assertThat(bindings.bindService().getServiceDescriptor().getName()).isEqualTo(SERVICE);
        assertThat(bindings.bindService().getMethods()).hasSize(6);
    }

    @Test
    void usesADefaultCompilerOnCoresCursorsWhenThereIsNone() {
        GrpcBindings bindings = byName();
        GrpcBindingsCompiler postProcessor = new GrpcBindingsCompiler(
                new StaticListableBeanFactory().getBeanProvider(ServiceCompiler.class), remainingOperations(),
                cursors(), paging(), declarations());

        postProcessor.postProcessAfterInitialization(bindings, "catalog");

        assertThat(bindings.bindService().getMethods()).hasSize(6);
    }

    @Test
    void bindingsThatFailTheChecksAreNeverCompiled() {
        // Checked before anything is attached: the bean is left uncompiled, so it can't be served.
        GrpcBindings bindings = GrpcBindings
                .service(CatalogServiceGrpc.getServiceDescriptor())
                .unary(CatalogServiceGrpc.getGetItemMethod(), new BindingFixtures.OptionalPageOperation())
                .bindAllByName()
                .build();
        GrpcBindingsCompiler postProcessor = new GrpcBindingsCompiler(compiler(Compilers.compiler()), operations(),
                cursors(), paging(), declarations());

        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> postProcessor.postProcessAfterInitialization(bindings, "catalog"))
                .withMessageContaining("a PageResult must be the operation's direct output");
        assertThatIllegalStateException()
                .isThrownBy(bindings::bindService)
                .withMessage("GrpcBindings for " + SERVICE
                        + " was not compiled: declare it as a @Bean so synapse-api-grpc can bind it");
    }

    @Test
    @SuppressWarnings("unchecked")
    void operationsAreLookedUpOnlyForBindAllByName() {
        GrpcBindings bindings = GrpcBindings
                .service(CatalogServiceGrpc.getServiceDescriptor())
                .unary(CatalogServiceGrpc.getGetItemMethod(), new BindingFixtures.GetItemOperation())
                .unary(CatalogServiceGrpc.getCreateItemMethod(), new BindingFixtures.CreateItemOperation())
                .context(BindingFixtures.CreateItem::channel, context -> "grpc")
                .unary(CatalogServiceGrpc.getDeleteItemMethod(), new BindingFixtures.DeleteItemOperation())
                .unary(CatalogServiceGrpc.getPingMethod(), new BindingFixtures.PingOperation())
                .unary(CatalogServiceGrpc.getListTagsMethod(), new BindingFixtures.ListTagsOperation())
                .unary(CatalogServiceGrpc.getFailMethod(), new BindingFixtures.FailOperation())
                .build();
        ObjectProvider<ServiceOperation<?, ?>> operations = mock(ObjectProvider.class);
        ObjectProvider<PageCursors> cursors = mock(ObjectProvider.class);

        new GrpcBindingsCompiler(compiler(Compilers.compiler()), operations, cursors, paging(), declarations())
                .postProcessAfterInitialization(bindings, "catalog");

        assertThat(bindings.bindService().getMethods()).hasSize(6);
        // With a compiler bean, core's cursors are not looked up either.
        verifyNoInteractions(operations, cursors);
    }

    @Test
    void otherBeansPassThrough() {
        GrpcBindingsCompiler postProcessor = new GrpcBindingsCompiler(compiler(Compilers.compiler()), operations(),
                cursors(), paging(), declarations());
        Object bean = new Object();

        assertThat(postProcessor.postProcessAfterInitialization(bean, "other")).isSameAs(bean);
    }

    @Test
    void eachCompiledMethodIsRegisteredWithItsParityDeclarations() {
        BindingDeclarations registry = new BindingDeclarations();
        GrpcBindings catalog = GrpcBindings
                .service(CatalogServiceGrpc.getServiceDescriptor())
                .unary(CatalogServiceGrpc.getGetItemMethod(), new BindingFixtures.GetItemOperation())
                .notFound(BindingFixtures.ItemErrors.ITEM_NOT_FOUND)
                .unary(CatalogServiceGrpc.getCreateItemMethod(), new BindingFixtures.CreateItemOperation())
                .context(BindingFixtures.CreateItem::channel, context -> "grpc")
                .bindAllByName()
                .build();

        new GrpcBindingsCompiler(compiler(Compilers.compiler()), remainingOperations(), cursors(), paging(),
                declarations(registry))
                .postProcessAfterInitialization(catalog, "catalog");

        assertThat(registry.forOperation("get-item"))
                .containsExactly(new BindingDeclaration("gRPC", "get-item", SERVICE + "/GetItem", "ITEM_NOT_FOUND",
                        null, null, 1));
        // A method .bindAllByName() binds declares nothing: NOT_FOUND, no etag, no sort keys.
        assertThat(registry.forOperation("ping"))
                .containsExactly(
                        new BindingDeclaration("gRPC", "ping", SERVICE + "/Ping", "NOT_FOUND", null, null, 1));
        assertThat(registry.operationNames()).hasSize(6);
    }

    @Test
    void theEtagComponentAndTheSortKeysAreRegistered() {
        BindingDeclarations registry = new BindingDeclarations();
        GrpcBindingsCompiler postProcessor = new GrpcBindingsCompiler(compiler(Compilers.compiler()), operations(),
                cursors(), paging(), declarations(registry));
        GrpcBindings docs = GrpcBindings
                .service(DocServiceGrpc.getServiceDescriptor())
                .unary(DocServiceGrpc.getGetDocMethod(), new BindingFixtures.GetDocOperation())
                .etag(BindingFixtures.DocView::version)
                .unary(DocServiceGrpc.getCreateDocMethod(), new BindingFixtures.CreateDocOperation())
                .unary(DocServiceGrpc.getUpdateDocMethod(), new BindingFixtures.UpdateDocOperation())
                .unary(DocServiceGrpc.getDeleteDocMethod(), new BindingFixtures.DeleteDocOperation())
                .build();
        GrpcBindings books = GrpcBindings
                .service(PagedBookServiceGrpc.getServiceDescriptor())
                .unary(PagedBookServiceGrpc.getListBooksMethod(), new BindingFixtures.PagedBooksOperation())
                .sortable(BindingFixtures.BookSort.class)
                .unary(PagedBookServiceGrpc.getListTitlesMethod(), new BindingFixtures.PagedBooksOperation())
                .unary(PagedBookServiceGrpc.getListAllMethod(), new BindingFixtures.ListAllBooksOperation())
                .build();

        postProcessor.postProcessAfterInitialization(docs, "docs");
        postProcessor.postProcessAfterInitialization(books, "books");

        assertThat(registry.forOperation("get-doc"))
                .singleElement()
                .satisfies(declaration -> assertThat(declaration.etagComponent()).isEqualTo("version"));
        assertThat(registry.forOperation("paged-books"))
                .extracting(BindingDeclaration::binding, BindingDeclaration::sortKeys)
                .containsExactly(tuple("synapse.fixture.v1.PagedBookService/ListBooks", Set
                        .of("TITLE",
                                "PUBLISHED_YEAR")),
                        tuple("synapse.fixture.v1.PagedBookService/ListTitles", null));
    }

    @Test
    void packageVersionIsTheTrailingVSegment() {
        assertThat(GrpcBindingsCompiler.packageVersion("bookstore.v1.Bookstore")).isEqualTo(1);
        assertThat(GrpcBindingsCompiler.packageVersion("a.v2beta1.S")).isEqualTo(2);
        assertThat(GrpcBindingsCompiler.packageVersion("a.v1p1beta1.S")).isEqualTo(1);
        assertThat(GrpcBindingsCompiler.packageVersion("a.v10.S")).isEqualTo(10);
        assertThat(GrpcBindingsCompiler.packageVersion("a.b.S")).isNull();
        assertThat(GrpcBindingsCompiler.packageVersion("S")).isNull();
        assertThat(GrpcBindingsCompiler.packageVersion("a.v1x.S")).isNull();
        // A one-segment package is its own last segment; a version too large for an int reads as unversioned.
        assertThat(GrpcBindingsCompiler.packageVersion("v3.S")).isEqualTo(3);
        assertThat(GrpcBindingsCompiler.packageVersion("a.v99999999999.S")).isNull();
    }

    @Test
    void bindingsThatFailToCompileRegisterNothing() {
        BindingDeclarations registry = new BindingDeclarations();
        GrpcBindings bindings = GrpcBindings
                .service(CatalogServiceGrpc.getServiceDescriptor())
                .unary(CatalogServiceGrpc.getGetItemMethod(), new BindingFixtures.GetItemOperation())
                .bindAllByName()
                .build();

        // CreateItem has no operation to bind by name.
        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> new GrpcBindingsCompiler(compiler(Compilers.compiler()), operations(), cursors(),
                        paging(), declarations(registry)).postProcessAfterInitialization(bindings, "catalog"));
        assertThat(registry.operationNames()).isEmpty();
    }

    @Test
    void compilesWithoutARegistry() {
        GrpcBindings bindings = byName();

        new GrpcBindingsCompiler(compiler(Compilers.compiler()), remainingOperations(), cursors(), paging(),
                new StaticListableBeanFactory().getBeanProvider(BindingDeclarations.class))
                .postProcessAfterInitialization(bindings, "catalog");

        assertThat(bindings.bindService().getMethods()).hasSize(6);
    }

    private static ObjectProvider<BindingDeclarations> declarations() {
        return declarations(new BindingDeclarations());
    }

    private static ObjectProvider<BindingDeclarations> declarations(BindingDeclarations registry) {
        return new StaticListableBeanFactory(Map.of("declarations", registry))
                .getBeanProvider(BindingDeclarations.class);
    }
}
