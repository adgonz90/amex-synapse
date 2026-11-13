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

import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.ConversionException;

import io.americanexpress.synapse.core.error.Violation;

/**
 * Converts the value of one field between its protobuf form (as {@code DynamicMessage.getField} returns it and
 * {@code setField} takes it) and a component's Java form. Converters are built once per field, when a mapping is
 * created.
 */
interface FieldConverter {

    /** Stands for a value that could not be read; its violation is already recorded. */
    Object FAILED = new Object();

    /**
     * Tells whether a value is {@link #FAILED}.
     *
     * @param value a converted value
     * @return true for the sentinel
     */
    @SuppressWarnings("ReferenceEquality") // The sentinel is compared by identity on purpose.
    static boolean failed(@Nullable Object value) {
        return value == FAILED;
    }

    /**
     * Converts a protobuf value to the component's type.
     *
     * @param value the protobuf value
     * @param reading collects the violations of the message being read
     * @param path the proto field path of the value, for violations
     * @return the Java value, {@code null} for no value, or {@link #FAILED}
     * @throws IllegalArgumentException when the value is not valid for the component (or another conversion failure
     *     that {@link Reading#convert} records)
     */
    @Nullable
    Object read(Object value, Reading reading, String path);

    /**
     * Converts a component's value to its protobuf form.
     *
     * @param value the Java value, never {@code null}
     * @return the protobuf value, or {@code null} to leave the field unset
     * @throws IllegalStateException when the value can't be written to the field
     */
    @Nullable
    Object write(Object value);

    /**
     * Returns the converter of each element of a {@code repeated} field, or of each value of a {@code map} field.
     *
     * @return the element converter, or {@code null} when the field is neither
     */
    default @Nullable FieldConverter elements() {
        return null;
    }

    /**
     * Tells whether the {@link #elements()} are a {@code map} field's values, found by key, rather than a
     * {@code repeated} field's elements, found by index.
     *
     * @return true for a map field
     */
    default boolean keyed() {
        return false;
    }

    /**
     * The violations found while one message is read. Reading stops at {@link #MAX_VIOLATIONS}, so a request with
     * thousands of invalid elements costs no more than one with fifty, and the error stays small.
     */
    final class Reading {

        static final String INVALID = "is not a valid value";

        static final String MISSING = "must not be null";

        /** How many violations one request reports at most. */
        static final int MAX_VIOLATIONS = 50;

        private static final Logger LOG = LoggerFactory.getLogger("io.americanexpress.synapse.api.grpc");

        private final List<Violation> violations = new ArrayList<>();

        /** Whether a write's If-Match {@code etag} is a weak tag, which never matches under strong comparison. */
        private boolean weakTag;

        /** The required token fields the request doesn't send, each with its advice, in component order. */
        private final List<Violation> missingPreconditions = new ArrayList<>(2);

        /**
         * Converts a value, recording a value the converter rejects as {@code {path, "is not a valid value"}}. Only
         * conversion failures are recorded ({@link ConversionException}, {@link IllegalArgumentException},
         * {@link DateTimeException}, {@link ArithmeticException}); anything else is a bug and reaches the caller, as
         * does a {@code SynapseException} thrown by a record's constructor.
         *
         * @param converter the converter
         * @param value the protobuf value
         * @param path the proto field path
         * @return the Java value, {@code null}, or {@link #FAILED}
         * @throws Full when the violation limit is reached
         */
        @Nullable
        Object convert(FieldConverter converter, Object value, String path) {
            try {
                return converter.read(value, this, path);
            } catch (ConversionException | IllegalArgumentException | DateTimeException | ArithmeticException e) {
                // The conversion's message may repeat the value; it stays in the server's debug log.
                LOG.debug("The value of {} could not be read", path, e);
                invalid(path);
                return FAILED;
            }
        }

        void invalid(String path) {
            add(new Violation(path, INVALID));
        }

        void missing(String path) {
            add(new Violation(path, MISSING));
        }

        void violation(String path, String message) {
            add(new Violation(path, message));
        }

        /** Records that a write's If-Match {@code etag} is weak: once the input is valid, the precondition fails. */
        void weakTag() {
            weakTag = true;
        }

        boolean hasWeakTag() {
            return weakTag;
        }

        /**
         * Records that a required token field is missing: not a violation, but {@code PRECONDITION_REQUIRED} once the
         * input is otherwise valid, as REST answers 428 after its 400s, with one violation per missing field.
         *
         * @param path the field's path ({@code etag} or {@code if_none_match})
         * @param advice how to resubmit, as REST's 428 violation says it for its header
         */
        void preconditionMissing(String path, String advice) {
            missingPreconditions.add(new Violation(path, advice));
        }

        List<Violation> missingPreconditions() {
            return missingPreconditions;
        }

        List<Violation> violations() {
            return violations;
        }

        private void add(Violation violation) {
            violations.add(violation);
            if (violations.size() >= MAX_VIOLATIONS) {
                throw new Full();
            }
        }
    }

    /** Stops reading once {@link Reading#MAX_VIOLATIONS} violations are recorded. */
    final class Full extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Full() {
            super(null, null, false, false);
        }
    }
}
