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
import java.util.function.Function;

import io.grpc.MethodDescriptor;
import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.api.grpc.internal.binding.BindingChecks;
import io.americanexpress.synapse.api.grpc.internal.binding.ContextSource;
import io.americanexpress.synapse.api.grpc.internal.binding.FieldOverride;
import io.americanexpress.synapse.api.grpc.internal.binding.MethodDeclaration;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.binding.ComponentRef;
import io.americanexpress.synapse.core.context.RequestContext;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.internal.binding.ComponentRefs;
import io.americanexpress.synapse.core.internal.binding.OperationTypes;
import io.americanexpress.synapse.core.internal.binding.ResolvedComponent;
import io.americanexpress.synapse.core.operation.OperationNames;
import io.americanexpress.synapse.core.operation.ServiceOperation;

/**
 * One method being bound on {@link GrpcBindings}: its field mappings and response options. Each call only records the
 * declaration; the component references are resolved when the bindings are built, and the declarations are checked
 * when the service is compiled, so a mistake fails startup with the method named.
 *
 * <p>
 * By default each record component reads from, or writes to, the message field whose JSON name equals the component
 * name ({@code countryCode} and {@code country_code}). {@link #map} binds a component to another field, and
 * {@link #context} sets an input component from the request context instead of a field.
 *
 * @param <I> the operation's input type
 * @param <O> the operation's output type
 */
public final class GrpcMethodSpec<I, O> {

    private final GrpcBindings.Builder bindings;

    private final MethodDescriptor<?, ?> method;

    private final ServiceOperation<I, O> operation;

    private final List<Mapped> maps = new ArrayList<>();

    private final List<Contextual> contexts = new ArrayList<>();

    private @Nullable ErrorDetails notFound;

    GrpcMethodSpec(GrpcBindings.Builder bindings, MethodDescriptor<?, ?> method, ServiceOperation<I, O> operation) {
        this.bindings = bindings;
        this.method = method;
        this.operation = operation;
    }

    /**
     * Binds the next unary method.
     *
     * @param method the generated method descriptor
     * @param operation the operation
     * @param <I2> the input type
     * @param <O2> the output type
     * @return the next method
     */
    public <I2, O2> GrpcMethodSpec<I2, O2> unary(MethodDescriptor<?, ?> method, ServiceOperation<I2, O2> operation) {
        return bindings.unary(method, operation);
    }

    /**
     * Binds each method of the service that no {@code .unary} call binds to the operation named after it.
     *
     * @return the bindings builder
     * @see GrpcBindings.Builder#bindAllByName()
     */
    public GrpcBindings.Builder bindAllByName() {
        return bindings.bindAllByName();
    }

    /**
     * Builds the bindings.
     *
     * @return the bindings
     * @see GrpcBindings.Builder#build()
     */
    public GrpcBindings build() {
        return bindings.build();
    }

    /**
     * Binds a component to a message field other than the one named after it. The component belongs to the input or
     * the output record, or to a record nested in one; which record declares it decides whether the field is read or
     * written.
     *
     * @param fieldPath the proto field name in the message of the component's record, dotted to reach a field of a
     *        nested message ({@code author.name})
     * @param component the component
     * @param <T> the record type
     * @return this method
     */
    public <T> GrpcMethodSpec<I, O> map(String fieldPath, ComponentRef<T, ?> component) {
        maps.add(new Mapped(fieldPath, component));
        return this;
    }

    /**
     * Sets an input component from the Synapse request context instead of a request field.
     *
     * @param component the input component
     * @param value reads the value from the context
     * @param <P> the component type
     * @return this method
     */
    public <P extends @Nullable Object> GrpcMethodSpec<I, O> context(ComponentRef<I, P> component,
            Function<RequestContext, ? extends @Nullable P> value) {
        contexts.add(new Contextual(component, value));
        return this;
    }

    /**
     * Sets the error for an empty {@code Optional} output (by default {@code NOT_FOUND}). The operation's output must
     * be an {@code Optional}; otherwise startup fails.
     *
     * @param code the error
     * @return this method
     */
    public GrpcMethodSpec<I, O> notFound(ErrorDetails code) {
        this.notFound = code;
        return this;
    }

    /**
     * Returns this method's declaration, with its component references resolved.
     *
     * @return the declaration
     * @throws SynapseConfigurationException when a component reference is not a method reference to a record accessor
     */
    MethodDeclaration declaration() {
        String operationName = OperationNames.nameOf(OperationTypes.userClassOf(operation));
        List<FieldOverride> overrides = maps
                .stream()
                .map(mapped -> new FieldOverride(mapped.fieldPath(), resolve(operationName, mapped.component())))
                .toList();
        List<ContextSource> sources = contexts
                .stream()
                .map(context -> new ContextSource(resolve(operationName, context.component()), context.value()))
                .toList();
        return MethodDeclaration.declared(method, operation, overrides, sources, notFound);
    }

    private ResolvedComponent resolve(String operationName, ComponentRef<?, ?> component) {
        try {
            return ComponentRefs.resolve(component);
        } catch (SynapseConfigurationException ex) {
            SynapseConfigurationException failure = BindingChecks
                    .failure(method, operationName, ex.description(), ex.action());
            failure.initCause(ex);
            throw failure;
        }
    }

    /** A {@code .map} call as declared. */
    private record Mapped(String fieldPath, ComponentRef<?, ?> component) {
    }

    /** A {@code .context} call as declared. */
    private record Contextual(ComponentRef<?, ?> component,
            Function<RequestContext, ? extends @Nullable Object> value) {
    }
}
