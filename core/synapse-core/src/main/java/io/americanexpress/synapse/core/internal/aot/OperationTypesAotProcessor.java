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
package io.americanexpress.synapse.core.internal.aot;

import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.beans.factory.aot.BeanRegistrationAotContribution;
import org.springframework.beans.factory.aot.BeanRegistrationAotProcessor;
import org.springframework.beans.factory.support.RegisteredBean;
import org.springframework.core.ResolvableType;

import io.americanexpress.synapse.core.operation.BusinessOperation;
import io.americanexpress.synapse.core.operation.ServiceOperation;

/**
 * Registers binding reflection hints for the input and output types of every {@code ServiceOperation} and
 * {@code BusinessOperation} bean, so that API plugins can read and write them with Jackson in a native image.
 *
 * <p>
 * The types are resolved from the bean's declared type (its class, or the generic return type of its {@code @Bean}
 * method), never from a proxy. Container types are unwrapped to the types they carry: {@code Optional<Book>},
 * {@code List<Book>} and {@code PageResult<Book>} register {@code Book}. JDK types themselves ({@code Optional},
 * {@code List}) are not registered; Synapse types such as {@code PageResult} are. Listed in
 * {@code META-INF/spring/aot.factories}.
 */
public final class OperationTypesAotProcessor implements BeanRegistrationAotProcessor {

    @Override
    public @Nullable BeanRegistrationAotContribution processAheadOfTime(RegisteredBean registeredBean) {
        ResolvableType beanType = registeredBean.getBeanType();
        Set<Class<?>> types = new LinkedHashSet<>();
        collectOperationTypes(beanType.as(ServiceOperation.class), types);
        collectOperationTypes(beanType.as(BusinessOperation.class), types);
        if (types.isEmpty()) {
            return null;
        }
        Class<?>[] bindingTypes = types.toArray(Class<?>[]::new);
        return (generationContext, beanRegistrationCode) -> new BindingReflectionHintsRegistrar()
                .registerReflectionHints(generationContext.getRuntimeHints().reflection(), bindingTypes);
    }

    /**
     * Collects the input and output types of an operation contract. The contract is {@code NONE}, and adds nothing,
     * when the bean does not implement it.
     */
    private static void collectOperationTypes(ResolvableType contract, Set<Class<?>> types) {
        Set<Type> visited = new HashSet<>();
        collect(contract.getGeneric(0), types, visited);
        collect(contract.getGeneric(1), types, visited);
    }

    /**
     * Collects the type and, recursively, its array component and type arguments. JDK types are unwrapped but not
     * collected. Each declared type is visited once, so a self-referencing variable ({@code T extends Comparable<T>})
     * ends the walk.
     */
    private static void collect(ResolvableType type, Set<Class<?>> types, Set<Type> visited) {
        if (!visited.add(type.getType())) {
            return;
        }
        if (type.isArray()) {
            collect(type.getComponentType(), types, visited);
            return;
        }
        Class<?> resolved = type.resolve();
        if (resolved != null && !resolved.isPrimitive() && !resolved.getName().startsWith("java.")) {
            types.add(resolved);
        }
        for (ResolvableType generic : type.getGenerics()) {
            collect(generic, types, visited);
        }
    }
}
