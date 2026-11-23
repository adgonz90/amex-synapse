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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import io.grpc.MethodDescriptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServiceDescriptor;
import io.grpc.stub.ServerCalls;
import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.api.grpc.internal.binding.BindingChecks;
import io.americanexpress.synapse.api.grpc.internal.binding.MethodDeclaration;
import io.americanexpress.synapse.api.grpc.internal.binding.ServiceDeclaration;
import io.americanexpress.synapse.api.grpc.internal.mapping.MappingException;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapsePagingProperties;
import io.americanexpress.synapse.core.internal.binding.OperationTypes;
import io.americanexpress.synapse.core.internal.paging.PageCursors;
import io.americanexpress.synapse.core.operation.OperationNames;
import io.americanexpress.synapse.core.operation.ServiceOperation;

/**
 * Compiles the bindings of one service into the service gRPC serves. In order, it:
 * <ol>
 * <li>runs {@link BindingChecks} on the {@code .unary} declarations;</li>
 * <li>binds each other method of the service, in the service's order: with {@code .bindAllByName()}, to the one
 * operation named after it ({@code GetItem} to {@code get-item}), which then gets the same checks; a method with no
 * such operation, or with two, or without {@code .bindAllByName()}, fails;</li>
 * <li>maps each method's messages to its operation's records; a {@link MappingException} becomes the method's binding
 * problem, except that a method whose only problem is required scalars without presence
 * ({@link MappingException#presenceOnly()}) lets the other methods compile, and every such method is then reported in
 * one failure, one line per component;</li>
 * <li>rebuilds the service descriptor with each method's copy that reads raw request bytes
 * ({@link RawBytesMarshaller}) and writes {@code DynamicMessage} responses ({@link DynamicMessageMarshaller}), keeping
 * the names and the generated schema descriptors that server reflection reads;</li>
 * <li>binds one {@link UnaryOperationHandler} per method;</li>
 * <li>when a method returns {@code PageResult}, reports a cursor key generated at startup
 * ({@link PageCursors#reportGeneratedKey}, at most once per application context, whichever plugins page);</li>
 * <li>hands each method's binding to the caller's consumer, if any, which the {@link GrpcBindingsCompiler} registers
 * for core's parity check between protocols.</li>
 * </ol>
 * Every problem is a {@link SynapseConfigurationException} whose description names the method and the operation.
 */
public final class ServiceCompiler {

    private static final String UNBOUND = "no operation is bound";

    private final PageCursors cursors;

    private final SynapsePagingProperties paging;

    private final String unboundHint;

    /**
     * Creates the compiler.
     *
     * @param cursors core's page cursors, which the paged methods' handlers wrap and unwrap page tokens with
     * @param paging core's {@code synapse.paging} properties: the page sizes and the offset cap
     */
    public ServiceCompiler(PageCursors cursors, SynapsePagingProperties paging) {
        this(cursors, paging, "");
    }

    /**
     * Creates a compiler that appends a hint to the action of an unbound method's failure, such as how to bind it in a
     * test slice.
     *
     * @param cursors core's page cursors
     * @param paging core's {@code synapse.paging} properties
     * @param unboundHint the hint, or empty for none
     */
    public ServiceCompiler(PageCursors cursors, SynapsePagingProperties paging, String unboundHint) {
        this.cursors = cursors;
        this.paging = paging;
        this.unboundHint = unboundHint;
    }

    /**
     * Returns the name of the operation each method of a service is bound to, by full method name in the service's
     * order: the {@code .unary} declaration's operation, or the name {@code .bindAllByName()} looks for. A method
     * neither binds is left out.
     *
     * @param declaration the bindings as declared
     * @return the operation names
     */
    public static Map<String, String> operationNames(ServiceDeclaration declaration) {
        Map<String, MethodDeclaration> declared = declared(declaration);
        Map<String, String> names = new LinkedHashMap<>();
        for (MethodDescriptor<?, ?> method : declaration.service().getMethods()) {
            MethodDeclaration unary = declared.get(method.getFullMethodName());
            if (unary != null) {
                names.put(method.getFullMethodName(), unary.operationName());
            } else if (declaration.bindAllByName()) {
                names.put(method.getFullMethodName(), nameFor(method));
            }
        }
        return names;
    }

    /**
     * Compiles the bindings.
     *
     * @param declaration the bindings as declared
     * @param operations the operations {@code .bindAllByName()} chooses from; ignored without it
     * @return the service
     * @throws SynapseConfigurationException on the first problem, with the method named, or once for every required
     *         scalar without presence
     */
    public ServerServiceDefinition compile(ServiceDeclaration declaration, List<ServiceOperation<?, ?>> operations) {
        return compile(declaration, operations, method -> {
        });
    }

    /**
     * Compiles the bindings, and hands each method's binding (its {@code .unary} declaration, or the one
     * {@code .bindAllByName()} made) to {@code compiled} once the whole service compiled, in the service's order.
     *
     * @param declaration the bindings as declared
     * @param operations the operations {@code .bindAllByName()} chooses from; ignored without it
     * @param compiled receives each method's binding; called for none when the service fails to compile
     * @return the service
     * @throws SynapseConfigurationException on the first problem, with the method named, or once for every required
     *         scalar without presence
     */
    public ServerServiceDefinition compile(ServiceDeclaration declaration, List<ServiceOperation<?, ?>> operations,
            Consumer<MethodDeclaration> compiled) {
        BindingChecks.check(declaration);
        List<MethodDeclaration> methods = bind(declaration, operations);
        ServiceDescriptor generated = declaration.service();
        ServiceDescriptor.Builder service = ServiceDescriptor
                .newBuilder(generated.getName())
                .setSchemaDescriptor(generated.getSchemaDescriptor());
        List<Compiled> handlers = new ArrayList<>();
        List<PresenceFailure> presence = new ArrayList<>();
        for (MethodDeclaration method : methods) {
            MethodDescriptor<?, ?> original = method.method();
            Descriptor input = messageType(method, original.getRequestMarshaller(), "request");
            Descriptor output = messageType(method, original.getResponseMarshaller(), "response");
            UnaryOperationHandler handler;
            try {
                handler = UnaryOperationHandler.create(method, input, output, cursors, paging);
            } catch (MappingException e) {
                if (e.presenceOnly()) {
                    // The other methods are still compiled, so that one failure lists every such component.
                    presence.add(new PresenceFailure(method, e));
                    continue;
                }
                SynapseConfigurationException failure = BindingChecks.failure(method, e.problem(), e.action());
                failure.initCause(e);
                throw failure;
            }
            MethodDescriptor<byte[], DynamicMessage> copy = original
                    .toBuilder(RawBytesMarshaller.INSTANCE, new DynamicMessageMarshaller(output))
                    .build();
            service.addMethod(copy);
            handlers.add(new Compiled(copy, handler));
        }
        if (!presence.isEmpty()) {
            throw presenceFailure(presence);
        }
        ServerServiceDefinition.Builder definition = ServerServiceDefinition.builder(service.build());
        for (Compiled method : handlers) {
            definition.addMethod(method.descriptor(), ServerCalls.asyncUnaryCall(method.handler()));
        }
        ServerServiceDefinition built = definition.build();
        // Once every method compiled, as REST's RouteCompiler reports it: a failed startup reports nothing.
        if (methods.stream().anyMatch(MethodDeclaration::pagedOutput)) {
            cursors.reportGeneratedKey();
        }
        methods.forEach(compiled);
        return built;
    }

    /**
     * Returns the binding of every method of the service, in the service's order: the {@code .unary} declaration, or
     * the operation {@code .bindAllByName()} finds, checked like a declaration.
     */
    private List<MethodDeclaration> bind(ServiceDeclaration declaration, List<ServiceOperation<?, ?>> operations) {
        Map<String, MethodDeclaration> declared = declared(declaration);
        Map<String, List<ServiceOperation<?, ?>>> byName = declaration.bindAllByName()
                ? operations
                        .stream()
                        .collect(Collectors
                                .groupingBy(operation -> OperationNames.nameOf(OperationTypes.userClassOf(operation)),
                                        LinkedHashMap::new, Collectors.toList()))
                : Map.of();
        List<MethodDeclaration> bound = new ArrayList<>();
        List<MethodDeclaration> boundByName = new ArrayList<>();
        for (MethodDescriptor<?, ?> method : declaration.service().getMethods()) {
            MethodDeclaration unary = declared.get(method.getFullMethodName());
            if (unary != null) {
                bound.add(unary);
                continue;
            }
            String name = nameFor(method);
            List<ServiceOperation<?, ?>> candidates = byName.getOrDefault(name, List.of());
            if (candidates.isEmpty()) {
                throw BindingChecks.failure(method, name, UNBOUND, unboundAction(name));
            }
            if (candidates.size() > 1) {
                throw BindingChecks
                        .failure(method, name,
                                "more than one operation is named " + name + " (" + candidates
                                        .stream()
                                        .map(operation -> OperationTypes.userClassOf(operation).getName())
                                        .collect(Collectors.joining(", ")) + ")",
                                "Give the operations different names with @OperationName, or bind the method with"
                                        + " .unary(...).");
            }
            MethodDeclaration byNameDeclaration = MethodDeclaration
                    .declared(method, candidates.get(0), List.of(), List.of(), List.of(), null);
            bound.add(byNameDeclaration);
            boundByName.add(byNameDeclaration);
        }
        BindingChecks.check(new ServiceDeclaration(declaration.service(), boundByName, true));
        return bound;
    }

    private String unboundAction(String operationName) {
        String action = "Bind it with .unary(...), or add .bindAllByName() and an operation named " + operationName
                + ".";
        return unboundHint.isEmpty() ? action : action + " " + unboundHint;
    }

    private static Map<String, MethodDeclaration> declared(ServiceDeclaration declaration) {
        Map<String, MethodDeclaration> declared = new LinkedHashMap<>();
        for (MethodDeclaration method : declaration.methods()) {
            declared.putIfAbsent(method.method().getFullMethodName(), method);
        }
        return declared;
    }

    private static String nameFor(MethodDescriptor<?, ?> method) {
        String bare = method.getBareMethodName();
        return OperationNames.ofMethodName(bare == null ? method.getFullMethodName() : bare);
    }

    /** Returns the protobuf message type a generated method's marshaller reads or writes. */
    private static Descriptor messageType(MethodDeclaration method, MethodDescriptor.Marshaller<?> marshaller,
            String side) {
        @Nullable
        Object prototype = marshaller instanceof MethodDescriptor.PrototypeMarshaller<?> messages
                ? messages.getMessagePrototype()
                : null;
        if (prototype instanceof Message message) {
            return message.getDescriptorForType();
        }
        throw BindingChecks
                .failure(method, "the " + side + " marshaller is not a protobuf message marshaller",
                        "Bind the methods of a service generated by protoc with grpc-java's plugin.");
    }

    /**
     * Returns one failure for every method whose only problem is required scalars without presence: one line per
     * component, each naming its method, in the service's order. The first method's problem is the cause; the others
     * are suppressed in it.
     */
    private static SynapseConfigurationException presenceFailure(List<PresenceFailure> failures) {
        List<String> lines = new ArrayList<>();
        for (PresenceFailure failure : failures) {
            for (String line : failure.problem().lines()) {
                lines.add(BindingChecks.failure(failure.method(), line, failure.problem().action()).description());
            }
        }
        MappingException first = failures.getFirst().problem();
        SynapseConfigurationException failure = new SynapseConfigurationException(String.join("\n", lines),
                first.action(), first);
        for (PresenceFailure other : failures.subList(1, failures.size())) {
            failure.addSuppressed(other.problem());
        }
        return failure;
    }

    /** A method whose only mapping problem is required scalars without presence. */
    private record PresenceFailure(MethodDeclaration method, MappingException problem) {
    }

    /** A method's copy in the rebuilt service descriptor, and its handler. */
    private record Compiled(MethodDescriptor<byte[], DynamicMessage> descriptor, UnaryOperationHandler handler) {
    }
}
