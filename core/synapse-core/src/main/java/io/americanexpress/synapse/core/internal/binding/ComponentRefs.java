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

import java.lang.invoke.SerializedLambda;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.binding.ComponentRef;

/**
 * Resolves {@link ComponentRef}s to the record components they name.
 *
 * <p>
 * The resolver calls {@code writeReplace} on the lambda class of each method reference. Under a native image that
 * call needs a lambda or serialization hint, which the plugin's AOT processor registers for the declaring
 * configuration class (for REST, {@code RestBindingsAotProcessor}); the {@code SerializedLambda} hint in the core
 * covers only reading the result.
 */
public final class ComponentRefs {

    private static final String ACTION = "Name the component with a method reference to its record accessor, "
            + "for example GetBookRequest::id.";

    private ComponentRefs() {
    }

    /**
     * Resolves a method reference to the record component whose accessor it names.
     *
     * @param ref the reference
     * @param <T> the record type
     * @param <P> the component type
     * @return the component
     * @throws SynapseConfigurationException if the reference is not a method reference to a record component
     */
    public static <T, P extends @Nullable Object> ResolvedComponent resolve(ComponentRef<T, P> ref) {
        SerializedLambda lambda = serialized(ref);
        if (lambda.getCapturedArgCount() > 0) {
            throw notAComponent("it is a bound method reference; use an unbound reference such as "
                    + lambda.getImplClass().replace('/', '.') + "::" + lambda.getImplMethodName());
        }
        String implClass = lambda.getImplClass().replace('/', '.');
        String method = lambda.getImplMethodName();
        Class<?> type;
        try {
            type = Class.forName(implClass, false, ref.getClass().getClassLoader());
        } catch (ClassNotFoundException | LinkageError ex) {
            throw notAComponent("it refers to " + implClass + "." + method + ", which cannot be loaded ("
                    + ex.getClass().getSimpleName() + ")");
        }
        if (!type.isRecord()) {
            throw notAComponent("it refers to " + implClass + "." + method + ", and " + implClass
                    + " is not a record");
        }
        for (RecordComponent component : type.getRecordComponents()) {
            Method accessor = component.getAccessor();
            if (accessor.getName().equals(method) && accessor.getParameterCount() == 0) {
                return new ResolvedComponent(type, component);
            }
        }
        throw notAComponent("it refers to " + implClass + "." + method + ", which is not a record component accessor");
    }

    /**
     * Lists the components of a record in declaration order.
     *
     * @param recordType the record type
     * @return the components
     * @throws IllegalArgumentException if the type is not a record
     */
    public static List<ResolvedComponent> components(Class<?> recordType) {
        if (!recordType.isRecord()) {
            throw new IllegalArgumentException(recordType.getName() + " is not a record");
        }
        return Arrays
                .stream(recordType.getRecordComponents())
                .map(component -> new ResolvedComponent(recordType, component))
                .toList();
    }

    private static SerializedLambda serialized(ComponentRef<?, ?> ref) {
        try {
            Method writeReplace = ref.getClass().getDeclaredMethod("writeReplace");
            writeReplace.setAccessible(true);
            if (writeReplace.invoke(ref) instanceof SerializedLambda lambda) {
                return lambda;
            }
        } catch (InaccessibleObjectException ex) {
            throw new SynapseConfigurationException("A component reference could not be read, because the package of "
                    + ref.getClass().getName() + " is not open to Synapse.",
                    "Open that package to the io.americanexpress.synapse.core module, or to all unnamed modules.");
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            throw notAComponent("reading it failed (" + cause.getClass().getSimpleName() + ")");
        } catch (ReflectiveOperationException | RuntimeException ex) {
            throw notAComponent("it is not a lambda or method reference (" + ex.getClass().getSimpleName() + ")");
        }
        throw notAComponent("it is not a lambda or method reference");
    }

    private static SynapseConfigurationException notAComponent(String reason) {
        return new SynapseConfigurationException(
                "A component reference must be a method reference to a record component, but " + reason + ".",
                ACTION);
    }
}
