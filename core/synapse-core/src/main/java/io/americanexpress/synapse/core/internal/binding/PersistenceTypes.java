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

import java.lang.annotation.Annotation;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.util.ClassUtils;

/**
 * Tells whether a type is a persistence entity, which edges refuse to bind: it is, or extends a class, annotated
 * {@code @jakarta.persistence.Entity}, {@code @org.springframework.data.mongodb.core.mapping.Document} or
 * {@code @org.springframework.data.relational.core.mapping.Table}, or it extends a class or implements an interface
 * in a Synapse data package ({@code io.americanexpress.synapse.data} and its subpackages). Annotations are matched by
 * name, so none of the persistence libraries has to be on the class path.
 */
public final class PersistenceTypes {

    private static final List<String> ENTITY_ANNOTATIONS = List
            .of("jakarta.persistence.Entity", "org.springframework.data.mongodb.core.mapping.Document",
                    "org.springframework.data.relational.core.mapping.Table");

    private static final String DATA_PACKAGE = "io.americanexpress.synapse.data";

    private PersistenceTypes() {
    }

    /**
     * Tells whether a type is a persistence entity.
     *
     * @param type the type
     * @return true if {@link #persistenceReason(Class)} gives a reason
     */
    public static boolean isPersistenceType(Class<?> type) {
        return persistenceReason(type) != null;
    }

    /**
     * Returns why a type is a persistence entity: {@code annotated @<annotation>}, {@code extends <class>} or
     * {@code implements <interface>}.
     *
     * @param type the type
     * @return the reason, or {@code null} when the type is not a persistence entity
     */
    public static @Nullable String persistenceReason(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Annotation annotation : current.getDeclaredAnnotations()) {
                String name = annotation.annotationType().getName();
                if (ENTITY_ANNOTATIONS.contains(name)) {
                    return "annotated @" + name;
                }
            }
            if (current != type && isDataType(current)) {
                return "extends " + current.getName();
            }
        }
        for (Class<?> implemented : ClassUtils.getAllInterfacesForClassAsSet(type)) {
            if (isDataType(implemented)) {
                return "implements " + implemented.getName();
            }
        }
        return null;
    }

    private static boolean isDataType(Class<?> type) {
        String name = type.getPackageName();
        return name.equals(DATA_PACKAGE) || name.startsWith(DATA_PACKAGE + ".");
    }
}
