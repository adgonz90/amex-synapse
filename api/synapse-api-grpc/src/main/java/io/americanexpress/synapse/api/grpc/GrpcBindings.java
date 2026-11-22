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

import java.util.ArrayList;
import java.util.List;

import io.grpc.BindableService;
import io.grpc.MethodDescriptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServiceDescriptor;
import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.api.grpc.internal.binding.MethodDeclaration;
import io.americanexpress.synapse.api.grpc.internal.binding.ServiceDeclaration;
import io.americanexpress.synapse.api.grpc.internal.service.GrpcBindingsAccess;
import io.americanexpress.synapse.core.operation.ServiceOperation;

/**
 * The gRPC methods of one service bound to operations, declared as a bean:
 *
 * <pre class="code">
 * &#64;Bean
 * GrpcBindings catalog(GetItemOperation getItem) {
 *     return GrpcBindings.service(CatalogServiceGrpc.getServiceDescriptor())
 *         .unary(CatalogServiceGrpc.getGetItemMethod(), getItem).notFound(ItemErrors.ITEM_NOT_FOUND)
 *         .bindAllByName()
 *         .build();
 * }
 * </pre>
 *
 * One bean binds one service. Building the bindings records the declarations and resolves their component
 * references; Synapse checks them and compiles the service when the bean is created, and a binding problem fails
 * startup. The bean is a {@link BindableService}, so Spring Boot's gRPC server serves it like a hand-written one.
 */
public final class GrpcBindings implements BindableService {

    static {
        // Synapse reads the declaration and attaches the compiled service through an internal class, so that neither
        // is public API.
        GrpcBindingsAccess.register(new GrpcBindingsAccess.Access() {

            @Override
            public ServiceDeclaration declaration(GrpcBindings bindings) {
                return bindings.declaration;
            }

            @Override
            public void compiled(GrpcBindings bindings, ServerServiceDefinition compiled) {
                bindings.compiled(compiled);
            }
        });
    }

    private final ServiceDeclaration declaration;

    private @Nullable ServerServiceDefinition definition;

    private GrpcBindings(ServiceDeclaration declaration) {
        this.declaration = declaration;
    }

    /**
     * Starts declaring the bindings of a service.
     *
     * @param service the generated service descriptor, for example {@code CatalogServiceGrpc.getServiceDescriptor()}
     * @return a builder
     */
    public static Builder service(ServiceDescriptor service) {
        return new Builder(service);
    }

    /**
     * Returns the service that Synapse compiled from these bindings.
     *
     * @return the service definition
     * @throws IllegalStateException when Synapse hasn't compiled the bindings, because they weren't declared as a bean
     */
    @Override
    public synchronized ServerServiceDefinition bindService() {
        ServerServiceDefinition compiled = definition;
        if (compiled == null) {
            throw new IllegalStateException("GrpcBindings for " + serviceName()
                    + " was not compiled: declare it as a @Bean so synapse-api-grpc can bind it");
        }
        return compiled;
    }

    /**
     * Attaches the service that Synapse compiled from these bindings, once, when the bean is created.
     *
     * @param compiled the service definition
     * @throws IllegalStateException when the bindings were already compiled
     */
    private synchronized void compiled(ServerServiceDefinition compiled) {
        if (definition != null) {
            throw new IllegalStateException("GrpcBindings for " + serviceName() + " was already compiled");
        }
        definition = compiled;
    }

    private String serviceName() {
        return declaration.service().getName();
    }

    /**
     * Declares the methods of one service, each bound to an operation.
     */
    public static final class Builder {

        private final ServiceDescriptor service;

        private final List<GrpcMethodSpec<?, ?>> specs = new ArrayList<>();

        private boolean bindAllByName;

        private Builder(ServiceDescriptor service) {
            this.service = service;
        }

        /**
         * Binds a unary method to an operation.
         *
         * @param method the generated method descriptor, for example {@code CatalogServiceGrpc.getGetItemMethod()}
         * @param operation the operation
         * @param <I> the input type
         * @param <O> the output type
         * @return the method, to add options or bind the next method
         */
        public <I, O> GrpcMethodSpec<I, O> unary(MethodDescriptor<?, ?> method, ServiceOperation<I, O> operation) {
            GrpcMethodSpec<I, O> spec = new GrpcMethodSpec<>(this, method, operation);
            specs.add(spec);
            return spec;
        }

        /**
         * Binds each method of the service that no {@link #unary} call binds to the operation named after it:
         * {@code GetItem} to the operation named {@code get-item}. A method without such an operation, or with two,
         * fails startup.
         *
         * @return this builder
         */
        public Builder bindAllByName() {
            this.bindAllByName = true;
            return this;
        }

        /**
         * Builds the bindings, resolving the component references of every method.
         *
         * @return the bindings
         * @throws io.americanexpress.synapse.core.SynapseConfigurationException when a component reference is not a
         *         method reference to a record accessor
         */
        public GrpcBindings build() {
            List<MethodDeclaration> methods = specs.stream().map(GrpcMethodSpec::declaration).toList();
            return new GrpcBindings(new ServiceDeclaration(service, methods, bindAllByName));
        }
    }
}
