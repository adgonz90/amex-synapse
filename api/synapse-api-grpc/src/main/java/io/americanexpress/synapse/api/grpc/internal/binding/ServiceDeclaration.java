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

import io.grpc.ServiceDescriptor;

/**
 * One {@code GrpcBindings} bean as declared: the service it binds and its {@code .unary} declarations, in declaration
 * order. Two declarations of one method are kept, so {@link BindingChecks} can reject them.
 *
 * @param service the generated service descriptor the bindings were created for
 * @param methods the {@code .unary} declarations, in declaration order
 * @param bindAllByName whether {@code .bindAllByName()} was declared
 */
public record ServiceDeclaration(ServiceDescriptor service, List<MethodDeclaration> methods, boolean bindAllByName) {

    /**
     * Creates the declaration.
     *
     * @param service the service descriptor
     * @param methods the method declarations
     * @param bindAllByName whether the remaining methods bind by name
     */
    public ServiceDeclaration {
        methods = List.copyOf(methods);
    }
}
