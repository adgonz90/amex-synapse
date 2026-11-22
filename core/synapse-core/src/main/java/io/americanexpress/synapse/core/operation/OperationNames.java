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
package io.americanexpress.synapse.core.operation;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.util.ClassUtils;

import io.americanexpress.synapse.core.SynapseConfigurationException;

/**
 * Resolves operation names and groups.
 */
public final class OperationNames {

    private static final Pattern VALID_NAME = Pattern.compile("^[a-z][a-z0-9]*(-[a-z0-9]+)*$");

    private static final Pattern WORD_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

    private static final String SUFFIX = "Operation";

    private OperationNames() {
    }

    /**
     * Returns the name of an operation: the {@link OperationName} value, or the kebab-case form of the class's simple
     * name without a trailing {@code Operation}.
     *
     * @param type the operation class, or a proxy subclass of it
     * @return the name
     * @throws SynapseConfigurationException if an annotated name is invalid
     */
    public static String nameOf(Class<?> type) {
        Class<?> userClass = ClassUtils.getUserClass(type);
        OperationName annotation = userClass.getAnnotation(OperationName.class);
        if (annotation != null) {
            if (!isValidName(annotation.value())) {
                throw new SynapseConfigurationException(
                        "The operation name '" + annotation.value() + "' on " + userClass.getName() + " is invalid.",
                        "Use lower-case words joined by single hyphens, such as 'get-book'.");
            }
            return annotation.value();
        }
        String simpleName = userClass.getSimpleName();
        if (simpleName.length() > SUFFIX.length() && simpleName.endsWith(SUFFIX)) {
            simpleName = simpleName.substring(0, simpleName.length() - SUFFIX.length());
        }
        return kebabCase(simpleName);
    }

    /**
     * Returns the operation name for a method name, such as a gRPC method's: its kebab-case form, split at the same
     * word boundaries as {@link #nameOf(Class)}, so {@code GetBook} is {@code get-book} and {@code GetHTTPStatus} is
     * {@code get-http-status}. A trailing {@code Operation} is kept.
     *
     * @param methodName the method name
     * @return the operation name
     */
    public static String ofMethodName(String methodName) {
        return kebabCase(methodName);
    }

    private static String kebabCase(String name) {
        return WORD_BOUNDARY.matcher(name).replaceAll("-").toLowerCase(Locale.ROOT);
    }

    /**
     * Returns the group of an operation, if it has an {@link OperationGroup}.
     *
     * @param type the operation class, or a proxy subclass of it
     * @return the group
     */
    public static Optional<String> groupOf(Class<?> type) {
        OperationGroup annotation = ClassUtils.getUserClass(type).getAnnotation(OperationGroup.class);
        return Optional.ofNullable(annotation).map(OperationGroup::value);
    }

    /**
     * Tells whether a name is valid: lower-case letters and digits in words joined by single hyphens, starting with a
     * letter.
     *
     * @param name the name
     * @return true if valid
     */
    public static boolean isValidName(String name) {
        return VALID_NAME.matcher(name).matches();
    }
}
