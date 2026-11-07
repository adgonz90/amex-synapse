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
package io.americanexpress.synapse.api.grpc.internal.mapping;

import java.util.Locale;

import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import org.jspecify.annotations.Nullable;

/**
 * Matches protobuf enum values with Java enum constants by name. A value matches the constant of the same name, or the
 * constant named by the rest of the value after the enum's prefix, as the protobuf style guide names values:
 * {@code GENRE_FICTION} in {@code enum Genre} matches {@code FICTION}. A same-name match wins over a prefixed one. A
 * zero value named {@code *_UNSPECIFIED} or {@code *_UNKNOWN} stands for no value and maps to {@code null}.
 */
public final class EnumNames {

    private static final String UNSPECIFIED = "_UNSPECIFIED";

    private static final String UNKNOWN = "_UNKNOWN";

    private EnumNames() {
    }

    /**
     * Returns the Java constant for a protobuf value.
     *
     * @param value the protobuf value
     * @param type the Java enum
     * @return the constant, or {@code null} for an unspecified zero value
     * @throws IllegalArgumentException when no constant matches, for example for a number the descriptor doesn't know
     */
    public static @Nullable Enum<?> toJava(EnumValueDescriptor value, Class<? extends Enum<?>> type) {
        if (isUnspecified(value)) {
            return null;
        }
        String name = value.getName();
        String prefixed = prefix(value.getType().getName()) + "_";
        Enum<?> match = null;
        for (Enum<?> constant : type.getEnumConstants()) {
            if (constant.name().equals(name)) {
                return constant;
            }
            if (name.equals(prefixed + constant.name())) {
                match = constant;
            }
        }
        if (match == null) {
            throw new IllegalArgumentException("No constant of " + type.getName() + " matches the value");
        }
        return match;
    }

    /**
     * Returns the protobuf value for a Java constant.
     *
     * @param constant the constant
     * @param type the protobuf enum
     * @return the value
     * @throws IllegalArgumentException when the protobuf enum has no value for the constant
     */
    public static EnumValueDescriptor toProto(Enum<?> constant, EnumDescriptor type) {
        EnumValueDescriptor value = find(constant, type);
        if (value == null) {
            throw new IllegalArgumentException(type.getFullName() + " has no value for "
                    + constant.getDeclaringClass().getName() + "." + constant.name());
        }
        return value;
    }

    /**
     * Returns the first Java constant the protobuf enum has no value for. Every constant must have one, so that
     * every value an operation returns can be written.
     *
     * @param type the Java enum
     * @param descriptor the protobuf enum
     * @return the first constant without a value, or {@code null} when every constant has one
     */
    public static @Nullable Enum<?> firstWithoutValue(Class<? extends Enum<?>> type, EnumDescriptor descriptor) {
        for (Enum<?> constant : type.getEnumConstants()) {
            if (find(constant, descriptor) == null) {
                return constant;
            }
        }
        return null;
    }

    /**
     * Returns the first Java constant that matches the enum's unspecified zero value ({@code UNKNOWN} for
     * {@code COLOR_UNKNOWN = 0}). That value reads as {@code null}, so the constant would be written but never read
     * back.
     *
     * @param type the Java enum
     * @param descriptor the protobuf enum
     * @return the first such constant, or {@code null} when there is none
     */
    public static @Nullable Enum<?> firstMatchingUnspecified(Class<? extends Enum<?>> type,
            EnumDescriptor descriptor) {
        for (Enum<?> constant : type.getEnumConstants()) {
            EnumValueDescriptor value = find(constant, descriptor);
            if (value != null && isUnspecified(value)) {
                return constant;
            }
        }
        return null;
    }

    /**
     * Tells whether the enum's zero value stands for no value ({@code *_UNSPECIFIED} or {@code *_UNKNOWN}), so that a
     * field of this enum can carry "absent" without presence.
     *
     * @param type the protobuf enum
     * @return true when the zero value is unspecified
     */
    public static boolean hasUnspecifiedZero(EnumDescriptor type) {
        EnumValueDescriptor zero = type.findValueByNumber(0);
        return zero != null && isUnspecified(zero);
    }

    /**
     * Returns the value-name prefix of an enum: its name in upper snake case ({@code BookStatus} gives
     * {@code BOOK_STATUS}, {@code HTTPStatus} gives {@code HTTP_STATUS}).
     *
     * @param enumName the protobuf enum's simple name
     * @return the prefix, without the trailing underscore
     */
    static String prefix(String enumName) {
        StringBuilder snake = new StringBuilder(enumName.length() + 4);
        for (int i = 0; i < enumName.length(); i++) {
            char c = enumName.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) {
                boolean previousIsUpper = Character.isUpperCase(enumName.charAt(i - 1));
                boolean nextIsLower = i + 1 < enumName.length() && Character.isLowerCase(enumName.charAt(i + 1));
                if (!previousIsUpper || nextIsLower) {
                    snake.append('_');
                }
            }
            snake.append(c);
        }
        return snake.toString().toUpperCase(Locale.ROOT);
    }

    private static boolean isUnspecified(EnumValueDescriptor value) {
        String name = value.getName();
        return value.getNumber() == 0 && (name.endsWith(UNSPECIFIED) || name.endsWith(UNKNOWN));
    }

    private static @Nullable EnumValueDescriptor find(Enum<?> constant, EnumDescriptor type) {
        EnumValueDescriptor exact = type.findValueByName(constant.name());
        if (exact != null) {
            return exact;
        }
        return type.findValueByName(prefix(type.getName()) + "_" + constant.name());
    }
}
