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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The bindings every API plugin compiled, filled while the plugins' beans are created and read by the
 * {@link BindingParityCheck} once every singleton exists. A binding registered twice is kept once.
 */
public final class BindingDeclarations {

    private final Set<BindingDeclaration> declarations = new LinkedHashSet<>();

    /**
     * Registers a compiled binding.
     *
     * @param declaration what the binding declares
     */
    public synchronized void add(BindingDeclaration declaration) {
        declarations.add(declaration);
    }

    /**
     * Returns the bindings of an operation, over every protocol, in registration order.
     *
     * @param operationName the operation name
     * @return the bindings; empty when none is registered
     */
    public synchronized List<BindingDeclaration> forOperation(String operationName) {
        return declarations.stream().filter(each -> each.operationName().equals(operationName)).toList();
    }

    /**
     * Returns the names of the operations that have a registered binding.
     *
     * @return the names, sorted
     */
    public synchronized SortedSet<String> operationNames() {
        SortedSet<String> names = new TreeSet<>();
        declarations.forEach(each -> names.add(each.operationName()));
        return names;
    }
}
