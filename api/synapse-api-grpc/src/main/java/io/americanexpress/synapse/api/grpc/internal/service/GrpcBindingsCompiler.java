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
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

import io.americanexpress.synapse.api.grpc.GrpcBindings;
import io.americanexpress.synapse.api.grpc.internal.binding.MethodDeclaration;
import io.americanexpress.synapse.api.grpc.internal.binding.ServiceDeclaration;
import io.americanexpress.synapse.api.grpc.internal.binding.SortKeys;
import io.americanexpress.synapse.core.autoconfigure.SynapsePagingProperties;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.internal.binding.BindingDeclaration;
import io.americanexpress.synapse.core.internal.binding.BindingDeclarations;
import io.americanexpress.synapse.core.internal.binding.ResolvedComponent;
import io.americanexpress.synapse.core.internal.paging.PageCursors;
import io.americanexpress.synapse.core.operation.ServiceOperation;

/**
 * Compiles each {@link GrpcBindings} bean when it is created, before Spring Boot's gRPC server discovers it: the
 * {@link ServiceCompiler} checks the bindings and builds the service, and only then is the service attached with
 * {@link GrpcBindingsAccess#compiled}, so bindings that fail a check are never served. A binding problem fails startup
 * with the method named. The operation beans are looked up only for bindings that declare {@code .bindAllByName()}.
 * Each compiled method's {@code .notFound}, {@code .etag} and {@code .sortable} declarations are registered with core's
 * {@link BindingDeclarations}, with the major version of its proto package, which compares them with the other
 * protocols' bindings of that version once every singleton exists.
 */
public final class GrpcBindingsCompiler implements BeanPostProcessor {

    /** The protocol a method's binding is registered under, as the parity failure names it. */
    private static final String PROTOCOL = "gRPC";

    /** A proto package's version segment (AIP-185, AIP-191); the group is the major version. */
    private static final Pattern PACKAGE_VERSION = Pattern.compile("^v(\\d+)(p\\d+)?((alpha|beta)\\d*)?$");

    private final ObjectProvider<ServiceCompiler> compiler;

    private final ObjectProvider<ServiceOperation<?, ?>> operations;

    private final ObjectProvider<PageCursors> cursors;

    private final ObjectProvider<SynapsePagingProperties> paging;

    private final ObjectProvider<BindingDeclarations> declarations;

    /**
     * Creates the post-processor. Its dependencies are resolved when the first bindings are compiled, so that
     * declaring it doesn't create beans early.
     *
     * @param compiler the service compiler; a default one, on core's page cursors and paging properties, when there is
     *     none
     * @param operations the operation beans, for {@code .bindAllByName()}
     * @param cursors core's page cursors, for the default compiler
     * @param paging core's {@code synapse.paging} properties, for the default compiler
     * @param declarations core's registry of compiled bindings, which each compiled method is added to; nothing is
     *     registered when there is none
     */
    public GrpcBindingsCompiler(ObjectProvider<ServiceCompiler> compiler,
            ObjectProvider<ServiceOperation<?, ?>> operations, ObjectProvider<PageCursors> cursors,
            ObjectProvider<SynapsePagingProperties> paging, ObjectProvider<BindingDeclarations> declarations) {
        this.compiler = compiler;
        this.operations = operations;
        this.cursors = cursors;
        this.paging = paging;
        this.declarations = declarations;
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
            BindingDeclarations registry = declarations.getIfAvailable();
            GrpcBindingsAccess.compiled(bindings, serviceCompiler.compile(declaration, candidates, method -> {
                if (registry != null) {
                    registry.add(declarationOf(method));
                }
            }));
        }
        return bean;
    }

    /** What a compiled method declares that must be the same over every protocol. */
    private static BindingDeclaration declarationOf(MethodDeclaration method) {
        ErrorDetails notFound = method.notFound();
        ResolvedComponent etag = method.etag();
        SortKeys sortKeys = method.sortKeys();
        // A compiled method belongs to its bindings' service (BindingChecks), so it has a service name.
        String service = Objects.requireNonNull(method.method().getServiceName());
        return new BindingDeclaration(PROTOCOL, method.operationName(), method.method().getFullMethodName(),
                (notFound != null ? notFound : SynapseErrors.NOT_FOUND).code(), etag == null ? null : etag.name(),
                sortKeys == null ? null : Set.copyOf(sortKeys.wireToProperty().values()), packageVersion(service));
    }

    /**
     * Reads the major version of a service's proto package, as AIP-185 and AIP-191 name it: the last segment of the
     * package, when it is {@code v<N>} with an optional {@code p<N>} and {@code alpha} or {@code beta} suffix
     * ({@code v1}, {@code v2beta1}, {@code v1p1beta1}).
     *
     * @param fullServiceName the service's full name, such as {@code bookstore.v1.Bookstore}
     * @return the major version, or {@code null} when the package has no version segment (or none at all), or its
     *     number doesn't fit an {@code int}
     */
    static @Nullable Integer packageVersion(String fullServiceName) {
        int service = fullServiceName.lastIndexOf('.');
        if (service < 0) {
            return null;
        }
        String protoPackage = fullServiceName.substring(0, service);
        Matcher version = PACKAGE_VERSION.matcher(protoPackage.substring(protoPackage.lastIndexOf('.') + 1));
        if (!version.matches()) {
            return null;
        }
        try {
            return Integer.valueOf(version.group(1));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
