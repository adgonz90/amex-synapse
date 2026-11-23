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

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

/**
 * What one binding of an operation declares, as an API plugin compiled it: the declarations that are part of the
 * operation's contract, and so must be the same over every protocol (see {@link BindingParityCheck}).
 *
 * @param protocol the protocol, as the failure names it ({@code REST}, {@code gRPC})
 * @param operationName the operation's name
 * @param binding the binding, as the failure names it: {@code GET /books/{id}} or
 *        {@code bookstore.v1.Bookstore/GetBook}
 * @param notFoundCode the code of the error an empty {@code Optional} output is: the {@code .notFound} code, or
 *        {@code NOT_FOUND} without one
 * @param etagComponent the name of the {@code .etag} output component, or {@code null} without {@code .etag}
 * @param sortKeys the sort keys of {@code .sortable}, as the operation receives them in {@code SortOrder.property()}
 *        (an enum's constant names), or {@code null} without {@code .sortable}; kept sorted
 * @param majorVersion the binding's major version, which pairs it with the bindings of the same major version over
 *        the other protocols: the major of a REST route's {@code .version(...)}, or the {@code v<N>} of the last
 *        segment of a gRPC method's proto package ({@code bookstore.v1} is 1, {@code bookstore.v2beta1} is 2);
 *        {@code null} for an unversioned binding, which pairs with every binding over the other protocols
 */
public record BindingDeclaration(String protocol, String operationName, String binding, String notFoundCode,
        @Nullable String etagComponent, @Nullable Set<String> sortKeys, @Nullable Integer majorVersion) {

    /**
     * Creates the declaration.
     *
     * @param protocol the protocol
     * @param operationName the operation name
     * @param binding the binding
     * @param notFoundCode the effective not-found code
     * @param etagComponent the {@code .etag} component name, or {@code null}
     * @param sortKeys the sort keys, or {@code null}
     * @param majorVersion the major version, or {@code null} for an unversioned binding
     */
    public BindingDeclaration {
        sortKeys = sortKeys == null ? null : Collections.unmodifiableSortedSet(new TreeSet<>(sortKeys));
    }
}
