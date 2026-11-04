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
package io.americanexpress.synapse.core.internal.pipeline;

import org.jspecify.annotations.Nullable;
import org.springframework.util.ClassUtils;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.operation.OperationKind;
import io.americanexpress.synapse.core.operation.OperationNames;

/**
 * What the pipeline needs to know about an operation or business class, resolved once per class. Resolving it also
 * checks that the class can run the pipeline, so a lazy bean whose class was unknown at startup (a lambda or an
 * anonymous class behind an interface-typed {@code @Bean} method) fails on its first call.
 *
 * @param name the operation or business name, such as {@code get-book}
 * @param kind the operation kind, or {@code null} for a business operation
 */
public record OperationDescriptor(String name, @Nullable OperationKind kind) {

    /**
     * Describes a {@code ServiceOperation} class.
     *
     * @param type the operation class (a proxy class is resolved to the user class)
     * @return the descriptor
     * @throws io.americanexpress.synapse.core.SynapseConfigurationException if the class cannot be proxied by class (a
     *     lambda or anonymous class, for example), or the name or kind is invalid
     */
    public static OperationDescriptor ofOperation(Class<?> type) {
        OperationBeanDefinitionChecker.checkClass(ClassUtils.getUserClass(type));
        return new OperationDescriptor(validName(type), OperationKind.of(type));
    }

    /**
     * Describes a {@code BusinessOperation} class.
     *
     * @param type the business class (a proxy class is resolved to the user class)
     * @return the descriptor
     * @throws io.americanexpress.synapse.core.SynapseConfigurationException if the class cannot be proxied by class, or
     *     the name is invalid
     */
    public static OperationDescriptor ofBusiness(Class<?> type) {
        OperationBeanDefinitionChecker.checkClass(ClassUtils.getUserClass(type));
        return new OperationDescriptor(validName(type), null);
    }

    /** Returns the name of the class, failing for a derived name that is not valid (an annotated one fails already). */
    private static String validName(Class<?> type) {
        String name = OperationNames.nameOf(type);
        if (!OperationNames.isValidName(name)) {
            throw new SynapseConfigurationException(
                    ClassUtils.getUserClass(type).getName() + " has the invalid name '" + name
                            + "', derived from its class name.",
                    "Name it with @OperationName, using lower-case words joined by single hyphens, such as 'get-book'.");
        }
        return name;
    }
}
