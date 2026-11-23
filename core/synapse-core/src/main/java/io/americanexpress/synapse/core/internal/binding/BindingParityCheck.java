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
package io.americanexpress.synapse.core.internal.binding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

import io.americanexpress.synapse.core.SynapseConfigurationException;

/**
 * Fails startup when the bindings of one operation over two protocols declare it differently: protocols must not
 * differ in contract. Once every singleton exists, and so every API plugin has compiled its bindings, it compares the
 * effective {@code .notFound} code, the {@code .etag} component and the {@code .sortable} keys of every binding with
 * those of every binding of the same operation and major version over another protocol: as AIP-185 has it, a gRPC
 * method's proto package version ({@code bookstore.v1}) and a REST route's version ({@code .version("1")},
 * {@code "1.2"}) are one major version, so a REST v2 route may differ from a gRPC v1 method. A binding without a
 * version is compared with every binding of the operation over another protocol. Bindings over one protocol are not
 * compared with each other. Every mismatch is listed in one failure, ordered by operation name, then declaration.
 * <p>
 * A binding of an operation bound over two protocols that has no binding of its major version over another protocol
 * (a gRPC v1 method with only REST v2 routes) is not compared: every such binding is listed in one WARN.
 */
public final class BindingParityCheck implements SmartInitializingSingleton {

    private static final Logger LOG = LoggerFactory.getLogger("io.americanexpress.synapse.core");

    private static final String NONE = "none";

    /** The compared declarations, in the order the failure lists them. */
    private static final List<Declaration> DECLARATIONS = List
            .of(new Declaration("etag", each -> valueOf(each.etagComponent())),
                    new Declaration("notFound", BindingDeclaration::notFoundCode),
                    new Declaration("sortable", each -> valueOf(each.sortKeys())));

    private final BindingDeclarations declarations;

    /**
     * Creates the check.
     *
     * @param declarations the bindings the API plugins compiled
     */
    public BindingParityCheck(BindingDeclarations declarations) {
        this.declarations = declarations;
    }

    /**
     * Compares the bindings.
     *
     * @throws SynapseConfigurationException when an operation is declared differently over two protocols, naming every
     *         mismatch
     */
    @Override
    public void afterSingletonsInstantiated() {
        List<Mismatch> mismatches = new ArrayList<>();
        List<BindingDeclaration> unpaired = new ArrayList<>();
        for (String operation : declarations.operationNames()) {
            List<BindingDeclaration> bindings = declarations.forOperation(operation);
            for (BindingDeclaration first : bindings) {
                for (BindingDeclaration second : bindings) {
                    // Each pair over two protocols once, the protocol that sorts first named first.
                    if (first.protocol().compareTo(second.protocol()) < 0 && paired(first, second)) {
                        compare(first, second, mismatches);
                    }
                }
                if (isUnpaired(first, bindings)) {
                    unpaired.add(first);
                }
            }
        }
        if (!unpaired.isEmpty()) {
            List<String> listed = unpaired
                    .stream()
                    .sorted(Comparator
                            .comparing(BindingDeclaration::operationName)
                            .thenComparing(BindingDeclaration::binding))
                    .map(each -> each.operationName() + ": " + each.binding())
                    .toList();
            LOG
                    .warn("These bindings have no binding of the same major version over the other protocol, so "
                            + "their contract is not compared: {}. Bind the same version over both protocols, or "
                            + "ignore this if the version is meant for one protocol only.", listed);
        }
        if (mismatches.isEmpty()) {
            return;
        }
        mismatches
                .sort(Comparator
                        .comparing(Mismatch::operation)
                        .thenComparingInt(mismatch -> DECLARATIONS.indexOf(mismatch.declaration()))
                        .thenComparing(mismatch -> mismatch.first().binding())
                        .thenComparing(mismatch -> mismatch.second().binding()));
        String description = mismatches.stream().map(Mismatch::describe).collect(Collectors.joining(" "));
        List<String> declared = mismatches
                .stream()
                .map(mismatch -> "." + mismatch.declaration().name() + "(...)")
                .distinct()
                .sorted()
                .toList();
        throw new SynapseConfigurationException(description, "Declare " + joined(declared)
                + " the same way on both bindings: protocols must not differ in contract.");
    }

    /** Tells whether two bindings are compared: either is unversioned, or both have the same major version. */
    private static boolean paired(BindingDeclaration first, BindingDeclaration second) {
        Integer firstVersion = first.majorVersion();
        Integer secondVersion = second.majorVersion();
        return firstVersion == null || secondVersion == null || firstVersion.equals(secondVersion);
    }

    /** Tells whether the binding's operation is bound over another protocol, but by no binding that pairs with it. */
    private static boolean isUnpaired(BindingDeclaration binding, List<BindingDeclaration> bindings) {
        List<BindingDeclaration> others = bindings
                .stream()
                .filter(each -> !each.protocol().equals(binding.protocol()))
                .toList();
        return !others.isEmpty() && others.stream().noneMatch(other -> paired(binding, other));
    }

    private static void compare(BindingDeclaration first, BindingDeclaration second, List<Mismatch> mismatches) {
        for (Declaration declaration : DECLARATIONS) {
            String firstValue = declaration.value().apply(first);
            String secondValue = declaration.value().apply(second);
            if (!Objects.equals(firstValue, secondValue)) {
                mismatches
                        .add(new Mismatch(first.operationName(), declaration, first, second, firstValue,
                                secondValue));
            }
        }
    }

    private static String valueOf(@Nullable Object value) {
        return value == null ? NONE : value.toString();
    }

    /** Joins {@code a}, {@code a and b}, {@code a, b and c}. */
    private static String joined(List<String> items) {
        if (items.size() == 1) {
            return items.getFirst();
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.getLast();
    }

    /** A compared declaration: its name, as {@code .<name>(...)} declares it, and its value in a binding. */
    private record Declaration(String name, Function<BindingDeclaration, String> value) {
    }

    /** One declaration that differs between two bindings of an operation. */
    private record Mismatch(String operation, Declaration declaration, BindingDeclaration first,
            BindingDeclaration second, String firstValue, String secondValue) {

        String describe() {
            return "Operation '" + operation + "' is bound differently over " + first.protocol() + " ("
                    + first.binding() + ") and " + second.protocol() + " (" + second.binding() + "): "
                    + declaration.name() + " is " + firstValue + " over " + first.protocol() + " and " + secondValue
                    + " over " + second.protocol() + ".";
        }
    }
}
