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
package io.americanexpress.synapse.api.grpc;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;

import io.grpc.ServerServiceDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;

import io.americanexpress.synapse.api.grpc.fixture.proto.CatalogServiceGrpc;
import io.americanexpress.synapse.api.grpc.internal.binding.MethodDeclaration;
import io.americanexpress.synapse.api.grpc.internal.binding.ServiceDeclaration;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.AuthorRecord;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.CreateItem;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.CreateItemOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.GetItem;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.GetItemOperation;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.ItemErrors;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures.ItemRecord;
import io.americanexpress.synapse.api.grpc.internal.service.GrpcBindingsAccess;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.context.RequestContext;
import io.americanexpress.synapse.core.operation.ServiceOperation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class GrpcBindingsTest {

    private static final String SERVICE = "synapse.fixture.v1.CatalogService";

    @Test
    void builderKeepsMethodsAndOptions() {
        GrpcBindings bindings = GrpcBindings
                .service(CatalogServiceGrpc.getServiceDescriptor())
                .unary(CatalogServiceGrpc.getGetItemMethod(), new GetItemOperation())
                .map("key", GetItem::id)
                .notFound(ItemErrors.ITEM_NOT_FOUND)
                .unary(CatalogServiceGrpc.getCreateItemMethod(), new CreateItemOperation())
                .map("item.author.name", AuthorRecord::name)
                .map("item.title", ItemRecord::name)
                .context(CreateItem::channel, RequestContext::correlationId)
                .build();

        ServiceDeclaration declaration = GrpcBindingsAccess.declaration(bindings);
        assertThat(declaration.service()).isSameAs(CatalogServiceGrpc.getServiceDescriptor());
        assertThat(declaration.bindAllByName()).isFalse();
        assertThat(declaration.methods()).hasSize(2);

        MethodDeclaration get = declaration.methods().get(0);
        assertThat(get.method()).isSameAs(CatalogServiceGrpc.getGetItemMethod());
        assertThat(get.operationName()).isEqualTo("get-item");
        assertThat(get.inputType()).isEqualTo(GetItem.class);
        assertThat(get.outputType()).isEqualTo(Optional.class);
        assertThat(get.outputValueType()).isEqualTo(ItemRecord.class);
        assertThat(get.overrides()).singleElement().satisfies(override -> {
            assertThat(override.fieldPath()).isEqualTo("key");
            assertThat(override.component().recordType()).isEqualTo(GetItem.class);
            assertThat(override.component().name()).isEqualTo("id");
        });
        assertThat(get.contexts()).isEmpty();
        assertThat(get.notFound()).isEqualTo(ItemErrors.ITEM_NOT_FOUND);

        MethodDeclaration create = declaration.methods().get(1);
        assertThat(create.method()).isSameAs(CatalogServiceGrpc.getCreateItemMethod());
        assertThat(create.operationName()).isEqualTo("create-item");
        assertThat(create.inputType()).isEqualTo(CreateItem.class);
        assertThat(create.outputType()).isEqualTo(ItemRecord.class);
        assertThat(create.outputValueType()).isEqualTo(ItemRecord.class);
        assertThat(create.overrides())
                .extracting(override -> override.fieldPath() + "=" + override.component().recordType().getSimpleName()
                        + "." + override.component().name())
                .containsExactly("item.author.name=AuthorRecord.name", "item.title=ItemRecord.name");
        assertThat(create.contexts()).singleElement().satisfies(context -> {
            assertThat(context.component().recordType()).isEqualTo(CreateItem.class);
            assertThat(context.component().name()).isEqualTo("channel");
        });
        assertThat(create.notFound()).isNull();
    }

    @Test
    void bindAllByNameIsRecordedFromTheBuilderOrAMethod() {
        assertThat(GrpcBindingsAccess
                .declaration(GrpcBindings
                        .service(CatalogServiceGrpc.getServiceDescriptor())
                        .bindAllByName()
                        .build())
                .bindAllByName()).isTrue();
        ServiceDeclaration declaration = GrpcBindingsAccess
                .declaration(GrpcBindings
                        .service(CatalogServiceGrpc.getServiceDescriptor())
                        .unary(CatalogServiceGrpc.getGetItemMethod(), new GetItemOperation())
                        .bindAllByName()
                        .unary(CatalogServiceGrpc.getCreateItemMethod(), new CreateItemOperation())
                        .build());
        assertThat(declaration.bindAllByName()).isTrue();
        assertThat(declaration.methods())
                .extracting(MethodDeclaration::operationName)
                .containsExactly("get-item", "create-item");
    }

    @Test
    void typesAndNameAreReadThroughAProxy() {
        ProxyFactory factory = new ProxyFactory(new GetItemOperation());
        factory.setProxyTargetClass(true);
        ServiceOperation<?, ?> proxy = (ServiceOperation<?, ?>) factory.getProxy();
        assertThat(proxy.getClass()).isNotEqualTo(GetItemOperation.class);

        MethodDeclaration method = GrpcBindingsAccess
                .declaration(GrpcBindings
                        .service(CatalogServiceGrpc.getServiceDescriptor())
                        .unary(CatalogServiceGrpc.getGetItemMethod(), proxy)
                        .build())
                .methods()
                .getFirst();

        assertThat(method.operation()).isSameAs(proxy);
        assertThat(method.operationName()).isEqualTo("get-item");
        assertThat(method.inputType()).isEqualTo(GetItem.class);
        assertThat(method.outputValueType()).isEqualTo(ItemRecord.class);
    }

    @Test
    void aComponentThatIsNotARecordAccessorFailsWhenBuilt() {
        GrpcMethodSpec<GetItem, Optional<ItemRecord>> spec = GrpcBindings
                .service(CatalogServiceGrpc.getServiceDescriptor())
                .unary(CatalogServiceGrpc.getGetItemMethod(), new GetItemOperation())
                .map("id", (GetItem item) -> item.id());

        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(spec::build)
                .satisfies(e -> {
                    assertThat(e.description())
                            .startsWith("gRPC method " + SERVICE + "/GetItem (get-item): A component reference must "
                                    + "be a method reference to a record component, but ");
                    assertThat(e.action())
                            .isEqualTo("Name the component with a method reference to its record accessor, for "
                                    + "example GetBookRequest::id.");
                    assertThat(e.getCause()).isInstanceOf(SynapseConfigurationException.class);
                });
    }

    @Test
    void bindServiceBeforeCompileFails() {
        GrpcBindings bindings = GrpcBindings.service(CatalogServiceGrpc.getServiceDescriptor()).build();

        assertThatIllegalStateException()
                .isThrownBy(bindings::bindService)
                .withMessage("GrpcBindings for " + SERVICE + " was not compiled: declare it as a @Bean so "
                        + "synapse-api-grpc can bind it");
    }

    @Test
    void compiledTwiceFails() {
        GrpcBindings bindings = GrpcBindings.service(CatalogServiceGrpc.getServiceDescriptor()).build();
        ServerServiceDefinition definition = ServerServiceDefinition.builder(SERVICE).build();
        GrpcBindingsAccess.compiled(bindings, definition);

        assertThat(bindings.bindService()).isSameAs(definition);
        assertThatIllegalStateException()
                .isThrownBy(
                        () -> GrpcBindingsAccess.compiled(bindings, ServerServiceDefinition.builder(SERVICE).build()))
                .withMessage("GrpcBindings for " + SERVICE + " was already compiled");
        assertThat(bindings.bindService()).isSameAs(definition);
    }

    @Test
    void publicApiHasNoMutatorAndNoInternalType() {
        Stream<Method> api = Arrays
                .stream(GrpcBindings.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()));

        assertThat(api).allSatisfy(method -> {
            assertThat(method.getName()).as(method.toString()).isNotEqualTo("compiled");
            assertThat(Stream.concat(Stream.of(method.getReturnType()), Arrays.stream(method.getParameterTypes())))
                    .as(method.toString())
                    .noneMatch(type -> type.getName().contains(".internal."));
        });
    }

    @Test
    void theInternalAccessIsRegisteredOnlyOnce() {
        GrpcBindings bindings = GrpcBindings.service(CatalogServiceGrpc.getServiceDescriptor()).build();
        GrpcBindingsAccess.Access other = new GrpcBindingsAccess.Access() {

            @Override
            public ServiceDeclaration declaration(GrpcBindings ignored) {
                throw new AssertionError("not the registered access");
            }

            @Override
            public void compiled(GrpcBindings ignored, ServerServiceDefinition compiled) {
                throw new AssertionError("not the registered access");
            }
        };

        assertThatIllegalStateException()
                .isThrownBy(() -> GrpcBindingsAccess.register(other))
                .withMessage("The GrpcBindings access is already registered");
        assertThat(GrpcBindingsAccess.declaration(bindings).service())
                .isSameAs(CatalogServiceGrpc.getServiceDescriptor());
    }
}
