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

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Optional;

import io.grpc.MethodDescriptor;
import org.jspecify.annotations.Nullable;
import org.springframework.core.ResolvableType;

import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.internal.binding.OperationTypes;
import io.americanexpress.synapse.core.internal.binding.ResolvedComponent;
import io.americanexpress.synapse.core.operation.OperationNames;
import io.americanexpress.synapse.core.operation.ServiceOperation;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;

/**
 * One {@code .unary(method, operation)} declaration with its options, its component references resolved.
 *
 * @param method the generated method descriptor
 * @param operation the operation bean (possibly a proxy)
 * @param operationName the operation name
 * @param inputType the operation's input type {@code I}
 * @param outputType the operation's raw output type {@code O} (for example {@code Optional})
 * @param overrides the {@code .map} declarations, in declaration order
 * @param contexts the {@code .context} declarations, in declaration order
 * @param etags the output components of the {@code .etag} declarations, in declaration order (the checks admit one)
 * @param sortables the enums of the {@code .sortable} declarations, in declaration order (the checks admit one)
 * @param notFound the {@code .notFound} error, or {@code null} when none was declared
 */
public record MethodDeclaration(MethodDescriptor<?, ?> method, ServiceOperation<?, ?> operation, String operationName,
        Class<?> inputType, Class<?> outputType, List<FieldOverride> overrides, List<ContextSource> contexts,
        List<ResolvedComponent> etags, List<Class<? extends Enum<?>>> sortables, @Nullable ErrorDetails notFound) {

    /**
     * Creates the declaration.
     *
     * @param method the method descriptor
     * @param operation the operation
     * @param operationName the operation name
     * @param inputType the input type
     * @param outputType the raw output type
     * @param overrides the field overrides
     * @param contexts the context sources
     * @param etags the {@code .etag} components
     * @param sortables the {@code .sortable} enums
     * @param notFound the not-found error, or {@code null}
     */
    public MethodDeclaration {
        overrides = List.copyOf(overrides);
        contexts = List.copyOf(contexts);
        etags = List.copyOf(etags);
        sortables = List.copyOf(sortables);
    }

    /**
     * Creates a declaration without {@code .sortable}, as
     * {@link #declared(MethodDescriptor, ServiceOperation, List, List, List, List, ErrorDetails)} does.
     *
     * @param method the method descriptor
     * @param operation the operation
     * @param overrides the field overrides, in declaration order
     * @param contexts the context sources, in declaration order
     * @param etags the {@code .etag} components, in declaration order
     * @param notFound the not-found error, or {@code null}
     * @return the declaration
     */
    public static MethodDeclaration declared(MethodDescriptor<?, ?> method, ServiceOperation<?, ?> operation,
            List<FieldOverride> overrides, List<ContextSource> contexts, List<ResolvedComponent> etags,
            @Nullable ErrorDetails notFound) {
        return declared(method, operation, overrides, contexts, etags, List.of(), notFound);
    }

    /**
     * Creates a declaration, resolving the operation's name and its input and output types from the operation class
     * (through a CGLIB or Spring AOP proxy), as the REST edge does.
     *
     * @param method the method descriptor
     * @param operation the operation
     * @param overrides the field overrides, in declaration order
     * @param contexts the context sources, in declaration order
     * @param etags the {@code .etag} components, in declaration order
     * @param sortables the {@code .sortable} enums, in declaration order
     * @param notFound the not-found error, or {@code null}
     * @return the declaration
     */
    public static MethodDeclaration declared(MethodDescriptor<?, ?> method, ServiceOperation<?, ?> operation,
            List<FieldOverride> overrides, List<ContextSource> contexts, List<ResolvedComponent> etags,
            List<Class<? extends Enum<?>>> sortables, @Nullable ErrorDetails notFound) {
        Class<?> operationClass = OperationTypes.userClassOf(operation);
        return new MethodDeclaration(method, operation, OperationNames.nameOf(operationClass),
                contract(operationClass, 0).resolve(Object.class), contract(operationClass, 1).resolve(Object.class),
                overrides, contexts, etags, sortables, notFound);
    }

    /**
     * Returns the output component the response's {@code etag} field is written from.
     *
     * @return the first {@code .etag} component, or {@code null} when none was declared
     */
    public @Nullable ResolvedComponent etag() {
        return etags.isEmpty() ? null : etags.getFirst();
    }

    /**
     * Returns the method's sort allow-list, from its {@code .sortable} enum.
     *
     * @return the keys, or {@code null} when {@code .sortable} was not declared
     * @throws io.americanexpress.synapse.core.SynapseConfigurationException when the enum has no valid keys, which
     *         the binding checks reject first
     */
    public @Nullable SortKeys sortKeys() {
        return sortables.isEmpty() ? null : SortKeys.of(sortables.getFirst());
    }

    /**
     * Tells whether the method reads a {@code PageQuery} from the request's page fields: the input is a
     * {@code PageQuery}, or has a {@code PageQuery} component.
     *
     * @return whether the input binds a {@code PageQuery}
     */
    public boolean pagedInput() {
        if (inputType == PageQuery.class) {
            return true;
        }
        if (!inputType.isRecord()) {
            return false;
        }
        for (RecordComponent component : inputType.getRecordComponents()) {
            if (component.getType() == PageQuery.class) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether the operation returns a {@code PageResult}, which is written as a page.
     *
     * @return whether the raw output type is {@code PageResult}
     */
    public boolean pagedOutput() {
        return outputType == PageResult.class;
    }

    /**
     * Returns the operation's generic input type.
     *
     * @return the input type with its type arguments
     */
    public ResolvableType inputGenericType() {
        return contract(OperationTypes.userClassOf(operation), 0);
    }

    /**
     * Returns the operation's generic output type, for example {@code Optional<ItemRecord>}.
     *
     * @return the output type with its type arguments
     */
    public ResolvableType outputGenericType() {
        return contract(OperationTypes.userClassOf(operation), 1);
    }

    /**
     * Returns the output type unwrapped from {@code Optional}, {@code List} or {@code PageResult}: for a list, the
     * item type.
     *
     * @return the unwrapped output type
     */
    public Class<?> outputValueType() {
        ResolvableType output = outputGenericType();
        Class<?> raw = output.resolve(Object.class);
        if (raw == Optional.class || raw == List.class || raw == PageResult.class) {
            return output.getGeneric(0).resolve(Object.class);
        }
        return raw;
    }

    private static ResolvableType contract(Class<?> operationClass, int index) {
        return ResolvableType.forClass(operationClass).as(ServiceOperation.class).getGeneric(index);
    }
}
