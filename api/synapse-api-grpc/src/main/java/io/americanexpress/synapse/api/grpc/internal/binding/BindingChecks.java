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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.grpc.MethodDescriptor;
import io.grpc.ServiceDescriptor;
import org.jspecify.annotations.Nullable;
import org.springframework.core.ResolvableType;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.internal.binding.PersistenceTypes;
import io.americanexpress.synapse.core.internal.binding.ResolvedComponent;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;

/**
 * The startup checks of a {@code GrpcBindings} bean that need only its declarations, not the messages' fields. Each
 * failure is a {@link SynapseConfigurationException} whose description starts with
 * {@code gRPC method <service>/<method> (<operation>): }. For every {@code .unary} declaration, in declaration
 * order, it fails when:
 * <ul>
 * <li>the method belongs to another service than the one the bindings were created for;</li>
 * <li>the method is not unary;</li>
 * <li>the method was declared before;</li>
 * <li>the input or the output is nested more than 32 levels deep, or holds more than 10,000 types;</li>
 * <li>the input or the output holds a {@code PageQuery} or a {@code PageResult} (gRPC page tokens come later);</li>
 * <li>the input or the output holds a persistence entity;</li>
 * <li>the input is neither a record nor {@code Void};</li>
 * <li>a {@code .map} component is in neither the input type nor the output type (the record itself or a record
 * nested in it), or a {@code .context} component is not in the input type;</li>
 * <li>a component has two sources: {@code .map} and {@code .context}, or two of either;</li>
 * <li>{@code .notFound} is declared but the output is not an {@code Optional}.</li>
 * </ul>
 * Field-level checks are made when the messages are mapped; unbound methods and {@code .bindAllByName} are resolved
 * when the service is compiled.
 */
public final class BindingChecks {

    private static final String PAGING_ACTION = "Expose this operation over REST until then.";

    /** How deep a type walk follows nested records and containers. */
    private static final int MAX_TYPE_DEPTH = 32;

    /** How many types a type walk visits at most, so a generic record that grows can't hang startup. */
    private static final int MAX_TYPE_VISITS = 10_000;

    private BindingChecks() {
    }

    /**
     * Checks a service's declarations.
     *
     * @param declaration the bindings as declared
     * @throws SynapseConfigurationException on the first problem, with the method named
     */
    public static void check(ServiceDeclaration declaration) {
        Set<String> bound = new HashSet<>();
        for (MethodDeclaration method : declaration.methods()) {
            checkService(declaration.service(), method);
            checkUnary(method);
            if (!bound.add(method.method().getFullMethodName())) {
                throw failure(method, "bound more than once", "Bind each method once.");
            }
            TypeTree input = walk(method, "input", method.inputType(), method.inputGenericType());
            TypeTree output = walk(method, "output", method.outputValueType(), method.outputGenericType());
            checkPaging(method, input, output);
            checkEntities(method, input, output);
            if (!method.inputType().isRecord() && method.inputType() != Void.class) {
                throw failure(method, "the input type " + method.inputType().getName() + " is not a record or Void",
                        "Declare the operation's input as a record, or as Void if it takes none.");
            }
            checkSources(method, input, output);
            if (method.notFound() != null && method.outputType() != Optional.class) {
                throw failure(method,
                        ".notFound(...) needs an Optional output, but the output type is "
                                + method.outputType().getSimpleName(),
                        "Return an Optional from the operation, or remove .notFound(...).");
            }
        }
    }

    /**
     * Returns a binding failure with the method and operation named.
     *
     * @param method the method declaration
     * @param problem what is wrong
     * @param action what to do about it
     * @return the failure, to throw
     */
    public static SynapseConfigurationException failure(MethodDeclaration method, String problem, String action) {
        return failure(method.method(), method.operationName(), problem, action);
    }

    /**
     * Returns a binding failure with the method and operation named, for a method whose declaration doesn't exist yet.
     *
     * @param method the method descriptor
     * @param operationName the operation name
     * @param problem what is wrong
     * @param action what to do about it
     * @return the failure, to throw
     */
    public static SynapseConfigurationException failure(MethodDescriptor<?, ?> method, String operationName,
            String problem, String action) {
        return new SynapseConfigurationException(
                "gRPC method " + method.getFullMethodName() + " (" + operationName + "): " + problem, action);
    }

    private static void checkService(ServiceDescriptor service, MethodDeclaration method) {
        String owner = method.method().getServiceName();
        if (!service.getName().equals(owner)) {
            throw failure(method, "belongs to " + owner + ", not to " + service.getName(),
                    "Use the method descriptors of the service the bindings were created for.");
        }
    }

    private static void checkUnary(MethodDeclaration method) {
        MethodDescriptor.MethodType type = method.method().getType();
        if (type != MethodDescriptor.MethodType.UNARY) {
            throw failure(method, "is " + type + "; only unary methods can be bound in Synapse 2.0",
                    "Implement it in a hand-written *ImplBase service.");
        }
    }

    private static void checkPaging(MethodDeclaration method, TypeTree input, TypeTree output) {
        for (TypeTree tree : List.of(input, output)) {
            for (Class<?> paging : List.of(PageQuery.class, PageResult.class)) {
                if (tree.types.contains(paging)) {
                    throw failure(method,
                            paging.getSimpleName()
                                    + " is not supported over gRPC yet; gRPC page tokens come in plan 3b",
                            PAGING_ACTION);
                }
            }
        }
    }

    private static void checkEntities(MethodDeclaration method, TypeTree input, TypeTree output) {
        for (TypeTree tree : List.of(input, output)) {
            for (Class<?> type : tree.types) {
                if (PersistenceTypes.isPersistenceType(type)) {
                    throw failure(method, type.getName() + " is a persistence entity",
                            "Bind a record from the service's model and map the entity in the operation.");
                }
            }
        }
    }

    private static void checkSources(MethodDeclaration method, TypeTree input, TypeTree output) {
        Map<String, String> sources = new HashMap<>();
        for (FieldOverride override : method.overrides()) {
            ResolvedComponent component = override.component();
            String source = ".map(\"" + override.fieldPath() + "\", ...)";
            if (!input.records.contains(component.recordType()) && !output.records.contains(component.recordType())) {
                throw failure(method,
                        source + " names " + describe(component) + ", which is not in the input type "
                                + method.inputType().getSimpleName() + " or the output type "
                                + method.outputValueType().getSimpleName(),
                        "Name a component of the operation's input or output record, or of a record nested in one.");
            }
            claim(method, sources, component, source);
        }
        for (ContextSource context : method.contexts()) {
            ResolvedComponent component = context.component();
            String source = ".context(...)";
            if (!input.records.contains(component.recordType())) {
                throw failure(method,
                        source + " names " + describe(component) + ", which is not in the input type "
                                + method.inputType().getSimpleName(),
                        "Name a component of the operation's input record, or of a record nested in it.");
            }
            claim(method, sources, component, source);
        }
    }

    private static void claim(MethodDeclaration method, Map<String, String> sources, ResolvedComponent component,
            String source) {
        String previous = sources.putIfAbsent(component.recordType().getName() + "#" + component.name(), source);
        if (previous != null) {
            throw failure(method, describe(component) + " has more than one source: " + previous + " and " + source,
                    "Declare one source for each component.");
        }
    }

    private static String describe(ResolvedComponent component) {
        return component.recordType().getSimpleName() + "." + component.name();
    }

    private static TypeTree walk(MethodDeclaration method, String side, Class<?> named, ResolvableType root) {
        TypeTree tree = TypeTree.of(root);
        String overflow = tree.overflow;
        if (overflow != null) {
            throw failure(method,
                    "the " + side + " type " + named.getSimpleName() + " is too deeply nested: " + overflow,
                    "Flatten the operation's input and output types.");
        }
        return tree;
    }

    /**
     * The types reachable from an input or output type: the type itself, the components of records, the type
     * arguments of {@code Optional}, collections and maps, and array elements. {@code PageQuery} and
     * {@code PageResult} are listed but not walked into. The walk is bounded in depth and in visits, so a generic
     * record that grows with each level can't hang startup; a type that exceeds either bound fails the check.
     */
    private static final class TypeTree {

        /** Every type reached, records included. */
        private final Set<Class<?>> types = new LinkedHashSet<>();

        /** The records reached. */
        private final Set<Class<?>> records = new LinkedHashSet<>();

        private final Set<String> visited = new HashSet<>();

        private int remaining = MAX_TYPE_VISITS;

        /** Which bound the walk exceeded, or {@code null} when the whole type was walked. */
        private @Nullable String overflow;

        static TypeTree of(ResolvableType root) {
            TypeTree tree = new TypeTree();
            tree.walk(root, 0);
            return tree;
        }

        private void walk(ResolvableType type, int depth) {
            Class<?> raw = type.resolve();
            if (overflow != null || raw == null) {
                return;
            }
            if (depth > MAX_TYPE_DEPTH) {
                overflow = "more than " + MAX_TYPE_DEPTH + " levels of records and containers";
                return;
            }
            if (!visited.add(type.toString())) {
                return;
            }
            if (remaining <= 0) {
                overflow = "more than " + MAX_TYPE_VISITS + " nested types";
                return;
            }
            remaining--;
            types.add(raw);
            if (raw == PageQuery.class || raw == PageResult.class) {
                return;
            }
            if (raw.isRecord()) {
                records.add(raw);
                for (RecordComponent component : raw.getRecordComponents()) {
                    walk(ResolvableType.forType(component.getGenericType(), type), depth + 1);
                }
            } else if (raw.isArray()) {
                walk(type.getComponentType(), depth + 1);
            } else if (raw == Optional.class || Collection.class.isAssignableFrom(raw)
                    || Map.class.isAssignableFrom(raw)) {
                for (ResolvableType argument : type.getGenerics()) {
                    walk(argument, depth + 1);
                }
            }
        }
    }
}
