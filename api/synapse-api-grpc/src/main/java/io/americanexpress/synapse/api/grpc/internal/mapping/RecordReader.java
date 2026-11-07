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

import java.util.Map;

import com.google.protobuf.DynamicMessage;
import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.api.grpc.internal.mapping.FieldConverters.RecordConverter;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;

/**
 * Reads a method's request message into the operation's input record. Created and checked by
 * {@link MessageMapping#reader}.
 */
public final class RecordReader {

    private final @Nullable RecordConverter root;

    private final Map<String, String> fieldPaths;

    RecordReader(@Nullable RecordConverter root, Map<String, String> fieldPaths) {
        this.root = root;
        this.fieldPaths = fieldPaths;
    }

    /**
     * Reads the input. Every value that can't be converted is a violation {@code {field, "is not a valid value"}}
     * and every non-null component without a value is {@code {field, "must not be null"}}, where the field is the
     * proto field path from the request ({@code author.country_code}, {@code tags[2]}); a component set by
     * {@code .context} is named by its component path. The violations are collected in component order and thrown
     * together, at most 50 of them: reading stops at the fiftieth. {@code .context} values are read from the current
     * request context.
     *
     * @param message the request message
     * @return the input record, or {@code null} for a {@code Void} input
     * @throws SynapseException {@code VALIDATION_FAILED} with the violations, or the record's own exception
     */
    public @Nullable Object read(DynamicMessage message) {
        RecordConverter record = root;
        if (record == null) {
            return null;
        }
        FieldConverter.Reading reading = new FieldConverter.Reading();
        @Nullable
        Object read;
        try {
            read = record.readRecord(message, reading, "");
        } catch (FieldConverter.Full full) {
            // The limit was reached; the violations so far are the answer.
            throw SynapseException.ofViolations(SynapseErrors.VALIDATION_FAILED, reading.violations());
        }
        if (!reading.violations().isEmpty()) {
            throw SynapseException.ofViolations(SynapseErrors.VALIDATION_FAILED, reading.violations());
        }
        return read;
    }

    /**
     * Returns the proto field path of each mapped component, by component path ({@code author.countryCode} →
     * {@code author.country_code}).
     *
     * @return the field paths, immutable
     */
    public Map<String, String> fieldPaths() {
        return fieldPaths;
    }
}
