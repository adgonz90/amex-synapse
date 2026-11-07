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

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.api.grpc.internal.mapping.FieldConverters.RecordConverter;

/**
 * Writes an operation's output into the method's response message. Created and checked by
 * {@link MessageMapping#writer}.
 */
public final class RecordWriter {

    private final Descriptor message;

    private final @Nullable RecordConverter record;

    private final @Nullable FieldDescriptor listField;

    private final @Nullable FieldConverter list;

    private final Map<String, String> fieldPaths;

    private RecordWriter(Descriptor message, @Nullable RecordConverter record, @Nullable FieldDescriptor listField,
            @Nullable FieldConverter list, Map<String, String> fieldPaths) {
        this.message = message;
        this.record = record;
        this.listField = listField;
        this.list = list;
        this.fieldPaths = fieldPaths;
    }

    static RecordWriter empty(Descriptor message) {
        return new RecordWriter(message, null, null, null, Map.of());
    }

    static RecordWriter record(Descriptor message, RecordConverter record, Map<String, String> fieldPaths) {
        return new RecordWriter(message, record, null, null, fieldPaths);
    }

    static RecordWriter list(Descriptor message, FieldDescriptor field, FieldConverter list) {
        return new RecordWriter(message, null, field, list, Map.of());
    }

    /**
     * Writes the output. {@code null} (a {@code Void} output, or no value) is the empty message; an
     * {@code Optional} output is written from its value, which the caller unwraps.
     *
     * @param output the record, the list, or {@code null}
     * @return the response message
     * @throws IllegalStateException when a value can't be written to its field (out of range, or a null element)
     */
    public DynamicMessage write(@Nullable Object output) {
        if (output == null) {
            return DynamicMessage.getDefaultInstance(message);
        }
        RecordConverter recordConverter = record;
        if (recordConverter != null) {
            return recordConverter.writeRecord(output);
        }
        FieldDescriptor field = listField;
        FieldConverter elements = list;
        if (field != null && elements != null) {
            Object values = elements.write(output);
            DynamicMessage.Builder builder = DynamicMessage.newBuilder(message);
            if (values != null) {
                builder.setField(field, values);
            }
            return builder.build();
        }
        return DynamicMessage.getDefaultInstance(message);
    }

    /**
     * Returns the proto field path of each mapped component, by component path.
     *
     * @return the field paths; empty for a {@code List} or {@code Void} output
     */
    public Map<String, String> fieldPaths() {
        return fieldPaths;
    }
}
