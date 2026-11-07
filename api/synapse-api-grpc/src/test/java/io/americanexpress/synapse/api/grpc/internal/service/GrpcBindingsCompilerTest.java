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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import io.americanexpress.synapse.api.grpc.GrpcBindings;
import io.americanexpress.synapse.api.grpc.fixture.proto.CatalogServiceGrpc;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.operation.ServiceOperation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class GrpcBindingsCompilerTest {

    private static final String SERVICE = "synapse.fixture.v1.CatalogService";

    private static ObjectProvider<ServiceCompiler> compiler(ServiceCompiler compiler) {
        return new StaticListableBeanFactory(Map.of("compiler", compiler)).getBeanProvider(ServiceCompiler.class);
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
        GrpcBindingsCompiler postProcessor = new GrpcBindingsCompiler(compiler(new ServiceCompiler()),
                operations(new BindingFixtures.DeleteItemOperation(), new BindingFixtures.PingOperation(),
                        new BindingFixtures.ListTagsOperation(), new BindingFixtures.FailOperation()));

        assertThat(postProcessor.postProcessAfterInitialization(bindings, "catalog")).isSameAs(bindings);

        assertThat(bindings.bindService().getServiceDescriptor().getName()).isEqualTo(SERVICE);
        assertThat(bindings.bindService().getMethods()).hasSize(6);
    }

    @Test
    void usesADefaultCompilerWhenThereIsNone() {
        GrpcBindings bindings = byName();
        GrpcBindingsCompiler postProcessor = new GrpcBindingsCompiler(
                new StaticListableBeanFactory().getBeanProvider(ServiceCompiler.class),
                operations(new BindingFixtures.DeleteItemOperation(), new BindingFixtures.PingOperation(),
                        new BindingFixtures.ListTagsOperation(), new BindingFixtures.FailOperation()));

        postProcessor.postProcessAfterInitialization(bindings, "catalog");

        assertThat(bindings.bindService().getMethods()).hasSize(6);
    }

    @Test
    void bindingsThatFailTheChecksAreNeverCompiled() {
        // Checked before anything is attached: the bean is left uncompiled, so it can't be served.
        GrpcBindings bindings = GrpcBindings
                .service(CatalogServiceGrpc.getServiceDescriptor())
                .unary(CatalogServiceGrpc.getGetItemMethod(), new BindingFixtures.PageItemsOperation())
                .bindAllByName()
                .build();
        GrpcBindingsCompiler postProcessor = new GrpcBindingsCompiler(compiler(new ServiceCompiler()),
                operations());

        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> postProcessor.postProcessAfterInitialization(bindings, "catalog"))
                .withMessageContaining("PageResult is not supported over gRPC yet");
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

        new GrpcBindingsCompiler(compiler(new ServiceCompiler()), operations)
                .postProcessAfterInitialization(bindings, "catalog");

        assertThat(bindings.bindService().getMethods()).hasSize(6);
        verifyNoInteractions(operations);
    }

    @Test
    void otherBeansPassThrough() {
        ObjectProvider<ServiceCompiler> compiler = compiler(new ServiceCompiler());
        GrpcBindingsCompiler postProcessor = new GrpcBindingsCompiler(compiler, operations());
        Object bean = new Object();

        assertThat(postProcessor.postProcessAfterInitialization(bean, "other")).isSameAs(bean);
    }
}
