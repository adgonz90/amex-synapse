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
 * {@link #context} sets an input component from the request context instead of a field. A {@code VersionCondition}
 * input component always reads the request's {@code string etag} field, which carries REST's entity tag token
 * ({@code "5"} with its double quotes, or {@code *}), and {@link #etag} writes that token to the response's. A
 * {@code PageQuery} input component reads the request's AIP-158 fields ({@code page_size}, {@code page_token},
 * {@code skip} and {@code order_by}), and a {@code PageResult} output writes the response's items,
 * {@code next_page_token} and {@code total_size}; {@link #sortable} lists the sort keys.
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

    private final List<ComponentRef<?, ?>> etags = new ArrayList<>();

    private final List<Class<? extends Enum<?>>> sortables = new ArrayList<>();

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
     * Sends an output component as the response's strong entity tag, the same token REST sends as the {@code ETag}
     * header: the value formatted as a string ({@code 7} becomes {@code "7"}, with the double quotes) in the response
     * message's {@code string etag} field. A client sends it back in the {@code etag} field of a write, over gRPC or as
     * {@code If-Match} over REST. The component keeps its own field too. A {@code null} value leaves {@code etag}
     * empty.
     *
     * <p>
     * As over REST, {@code .etag} is declared once, on a method bound to a {@code QueryOperation} (a read) or to a
     * {@code CommandOperation} whose input has no {@code VersionCondition} (a create): an update or delete response
     * carries no entity tag. The output must be a record or an {@code Optional} of one, the component a string, an
     * integral number or a {@code java.time} value of that record (formatted by core's version token codec, as REST
     * formats its {@code ETag}: plain decimal or ISO-8601, whatever the locale), and the response message must have a
     * singular {@code string etag} field, outside a {@code oneof}, that no other component writes. Anything else fails
     * startup.
     *
     * @param component a component of the output record
     * @param <T> the record type
     * @return this method
     */
    public <T> GrpcMethodSpec<I, O> etag(ComponentRef<T, ?> component) {
        etags.add(component);
        return this;
    }

    /**
     * Lets callers sort this method's pages by the constants of an enum, as REST's {@code .sortable(keys)} does. A
     * constant's name in the request's {@code order_by} field (AIP-132) is its {@link Enum#name() name} in lower case
     * ({@code PUBLISHED_YEAR} is {@code published_year}); {@code @JsonProperty} is not read. Keys are separated by
     * commas, and a key followed by {@code desc} sorts descending: {@code "title, published_year desc"}. The operation
     * receives the constant's name as {@code SortOrder.property()}, as over REST, so a cursor issued over one protocol
     * pages over the other. Without {@code .sortable}, a non-empty {@code order_by} is rejected.
     *
     * <p>
     * The method's input must bind {@code PageQuery}, its request message must have a singular {@code string order_by}
     * field, and {@code .sortable} is declared once, with constants whose lower-case names are made of letters, digits
     * and {@code _} and are distinct. Anything else fails startup.
     *
     * @param keys the enum of sort keys
     * @param <E> the enum type
     * @return this method
     */
    public <E extends Enum<E>> GrpcMethodSpec<I, O> sortable(Class<E> keys) {
        sortables.add(keys);
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
        List<ResolvedComponent> etagComponents = etags
                .stream()
                .map(component -> resolve(operationName, component))
                .toList();
        return MethodDeclaration
                .declared(method, operation, overrides, sources, etagComponents, sortables, notFound);
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
