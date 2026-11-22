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

import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;

import org.springframework.core.Nullness;

/**
 * A record component named by a {@link io.americanexpress.synapse.core.binding.ComponentRef}.
 *
 * @param recordType the record that declares the component
 * @param component the component
 */
public record ResolvedComponent(Class<?> recordType, RecordComponent component) {

    /**
     * Returns the component name.
     *
     * @return the name
     */
    public String name() {
        return component.getName();
    }

    /**
     * Returns the raw component type.
     *
     * @return the type
     */
    public Class<?> type() {
        return component.getType();
    }

    /**
     * Returns the generic component type.
     *
     * @return the generic type
     */
    public Type genericType() {
        return component.getGenericType();
    }

    /**
     * Tells whether the component may be null. Only a component that is non-null under JSpecify
     * {@code @NullMarked} is not nullable, so everything in an unmarked package counts as nullable. A primitive
     * component is never null.
     *
     * @return true unless the component is declared non-null
     */
    public boolean nullable() {
        if (type().isPrimitive()) {
            return false;
        }
        return Nullness.forMethodReturnType(component.getAccessor()) != Nullness.NON_NULL;
    }
}
