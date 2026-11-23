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

import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;
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

    /**
     * Returns the component's type with its type-use annotations, such as {@code List<@Nullable String>}.
     *
     * @return the annotated type
     */
    public AnnotatedType annotatedType() {
        return component.getAnnotatedType();
    }

    /**
     * Tells whether the component is declared in null-marked code: JSpecify {@code @NullMarked} on its package, its
     * record or its accessor, and no {@code @NullUnmarked} closer to it, as {@link #nullable()} reads it.
     *
     * @return whether a type without a nullness annotation in the component's type is non-null
     */
    public boolean nullMarked() {
        Method accessor = component.getAccessor();
        Class<?> record = component.getDeclaringRecord();
        for (AnnotatedElement scope : new AnnotatedElement[]{accessor, record}) {
            if (scope.isAnnotationPresent(NullMarked.class)) {
                return true;
            }
            if (scope.isAnnotationPresent(NullUnmarked.class)) {
                return false;
            }
        }
        return record.getPackage().isAnnotationPresent(NullMarked.class);
    }

    /**
     * Tells whether the component's items may be null: a collection's elements, an array's components, a map's values
     * or an {@code Optional}'s value, as {@link #items(AnnotatedType)} finds them and {@link #nullable(AnnotatedType,
     * boolean)} reads them.
     *
     * @return whether an item may be null
     */
    public boolean nullableItems() {
        return nullable(items(annotatedType()), nullMarked());
    }

    /**
     * Returns the annotated type of a container's items: an array's component type, or the last type argument of a
     * parameterized type (a collection's elements, a map's values, an {@code Optional}'s value). JSpecify writes a
     * nullable item as {@code List<@Nullable T>}, {@code Map<K, @Nullable V>} or {@code @Nullable T[]}.
     *
     * @param type the container's annotated type
     * @return the items' annotated type, or {@code null} when the type declares none (it is not parameterized)
     */
    public static @Nullable AnnotatedType items(AnnotatedType type) {
        if (type instanceof AnnotatedArrayType array) {
            return array.getAnnotatedGenericComponentType();
        }
        if (type instanceof AnnotatedParameterizedType parameterized) {
            AnnotatedType[] arguments = parameterized.getAnnotatedActualTypeArguments();
            return arguments.length == 0 ? null : arguments[arguments.length - 1];
        }
        return null;
    }

    /**
     * Tells whether a value of an annotated type may be null: never for a primitive; when it is JSpecify
     * {@code @Nullable} or {@code @NonNull}, as that says; otherwise unless the code is null-marked. A type variable
     * is read by its own annotations, not its argument's, and a wildcard by its own, not its bound's: in null-marked
     * code {@code List<? extends @Nullable T>} reads as non-null items, the strict side.
     *
     * @param type the annotated type, or {@code null} when it is not known
     * @param nullMarked whether the code that declares it is null-marked ({@link #nullMarked()})
     * @return whether the value may be null
     */
    public static boolean nullable(@Nullable AnnotatedType type, boolean nullMarked) {
        if (type != null) {
            if (type.getType() instanceof Class<?> raw && raw.isPrimitive()) {
                return false;
            }
            if (type.isAnnotationPresent(Nullable.class)) {
                return true;
            }
            if (type.isAnnotationPresent(NonNull.class)) {
                return false;
            }
        }
        return !nullMarked;
    }
}
