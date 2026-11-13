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

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

import io.americanexpress.synapse.api.grpc.GrpcBindings;
import io.americanexpress.synapse.api.grpc.internal.binding.ServiceDeclaration;
import io.americanexpress.synapse.core.autoconfigure.SynapsePagingProperties;
import io.americanexpress.synapse.core.internal.paging.PageCursors;
import io.americanexpress.synapse.core.operation.ServiceOperation;

/**
 * Compiles each {@link GrpcBindings} bean when it is created, before Spring Boot's gRPC server discovers it: the
 * {@link ServiceCompiler} checks the bindings and builds the service, and only then is the service attached with
 * {@link GrpcBindingsAccess#compiled}, so bindings that fail a check are never served. A binding problem fails startup
 * with the method named. The operation beans are looked up only for bindings that declare {@code .bindAllByName()}.
 */
public final class GrpcBindingsCompiler implements BeanPostProcessor {

    private final ObjectProvider<ServiceCompiler> compiler;

    private final ObjectProvider<ServiceOperation<?, ?>> operations;

    private final ObjectProvider<PageCursors> cursors;

    private final ObjectProvider<SynapsePagingProperties> paging;

    /**
     * Creates the post-processor. Its dependencies are resolved when the first bindings are compiled, so that
     * declaring it doesn't create beans early.
     *
     * @param compiler the service compiler; a default one, on core's page cursors and paging properties, when there is
     *     none
     * @param operations the operation beans, for {@code .bindAllByName()}
     * @param cursors core's page cursors, for the default compiler
     * @param paging core's {@code synapse.paging} properties, for the default compiler
     */
    public GrpcBindingsCompiler(ObjectProvider<ServiceCompiler> compiler,
            ObjectProvider<ServiceOperation<?, ?>> operations, ObjectProvider<PageCursors> cursors,
            ObjectProvider<SynapsePagingProperties> paging) {
        this.compiler = compiler;
        this.operations = operations;
        this.cursors = cursors;
        this.paging = paging;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof GrpcBindings bindings) {
            ServiceDeclaration declaration = GrpcBindingsAccess.declaration(bindings);
            List<ServiceOperation<?, ?>> candidates = declaration.bindAllByName()
                    ? operations.orderedStream().toList()
                    : List.of();
            ServiceCompiler serviceCompiler = compiler
                    .getIfAvailable(() -> new ServiceCompiler(cursors.getObject(), paging.getObject()));
            GrpcBindingsAccess.compiled(bindings, serviceCompiler.compile(declaration, candidates));
        }
        return bean;
    }
}
