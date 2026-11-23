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

import java.lang.reflect.Method;
import java.util.Map;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import org.jspecify.annotations.Nullable;
import org.springframework.util.ReflectionUtils;

import io.americanexpress.synapse.api.grpc.internal.mapping.FieldConverters.RecordConverter;
import io.americanexpress.synapse.core.internal.binding.ResolvedComponent;
import io.americanexpress.synapse.core.internal.conditional.EntityTags;
import io.americanexpress.synapse.core.internal.conditional.VersionTokens;
import io.americanexpress.synapse.core.paging.PageResult;

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

    private final @Nullable ETagWriter etag;

    private final @Nullable PageWriter page;

    private RecordWriter(Descriptor message, @Nullable RecordConverter record, @Nullable FieldDescriptor listField,
            @Nullable FieldConverter list, Map<String, String> fieldPaths, @Nullable ETagWriter etag,
            @Nullable PageWriter page) {
        this.message = message;
        this.record = record;
        this.listField = listField;
        this.list = list;
        this.fieldPaths = fieldPaths;
        this.etag = etag;
        this.page = page;
    }

    static RecordWriter empty(Descriptor message) {
        return new RecordWriter(message, null, null, null, Map.of(), null, null);
    }

    static RecordWriter record(Descriptor message, RecordConverter record, Map<String, String> fieldPaths) {
        return new RecordWriter(message, record, null, null, fieldPaths, null, null);
    }

    static RecordWriter record(Descriptor message, RecordConverter record, Map<String, String> fieldPaths,
            ETagWriter etag) {
        return new RecordWriter(message, record, null, null, fieldPaths, etag, null);
    }

    static RecordWriter list(Descriptor message, FieldDescriptor field, FieldConverter list) {
        return new RecordWriter(message, null, field, list, Map.of(), null, null);
    }

    static RecordWriter page(Descriptor message, PageWriter page) {
        return new RecordWriter(message, null, null, null, Map.of(), null, page);
    }

    /**
     * Writes a {@code PageResult} to an AIP-158 response: its items to the response's one repeated message field, the
     * token of the next page to {@code next_page_token}, whether another page follows to {@code has_next}, and its
     * total to {@code total_size} when the response has that field.
     *
     * @param items the repeated field of the items
     * @param elements writes the item list
     * @param nextPageToken the response's {@code string next_page_token}
     * @param hasNext the response's {@code bool has_next}
     * @param totalSize the response's {@code optional int32} or {@code optional int64 total_size}, or {@code null}
     *        when it has none
     */
    record PageWriter(FieldDescriptor items, FieldConverter elements, FieldDescriptor nextPageToken,
            FieldDescriptor hasNext, @Nullable FieldDescriptor totalSize) {
    }

    /**
     * Tells whether this writer writes a {@code PageResult}, with {@link #writePage}.
     *
     * @return whether the output is a page
     */
    public boolean pages() {
        return page != null;
    }

    /**
     * Writes a page. The page's own {@code nextCursor}, the operation's raw cursor, is never written: the caller
     * passes the token of the next page. {@code has_next} is the page's own {@code hasNext}, as REST writes it, even
     * when no token reaches the next page (a page read by cursor that returns no cursor). A total that an
     * {@code int32 total_size} (AIP-158's type) can't hold is left unset, as an unknown total is, without a log line;
     * an {@code int64} one holds any. A {@code null} page is the empty message, as a {@code null} output is.
     *
     * @param result the operation's page, or {@code null}
     * @param nextPageToken the token of the next page, or {@code null} to leave {@code next_page_token} empty
     * @return the response message
     * @throws IllegalStateException when this writer doesn't write pages
     */
    public DynamicMessage writePage(@Nullable PageResult<?> result, @Nullable String nextPageToken) {
        PageWriter writer = page;
        if (writer == null) {
            throw new IllegalStateException("This writer doesn't write pages");
        }
        if (result == null) {
            return DynamicMessage.getDefaultInstance(message);
        }
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(message);
        Object items = writer.elements().write(result.items());
        if (items != null) {
            builder.setField(writer.items(), items);
        }
        if (nextPageToken != null) {
            builder.setField(writer.nextPageToken(), nextPageToken);
        }
        builder.setField(writer.hasNext(), result.hasNext());
        Long total = result.total();
        FieldDescriptor totalSize = writer.totalSize();
        if (total != null && totalSize != null) {
            if (totalSize.getType() == FieldDescriptor.Type.INT64) {
                builder.setField(totalSize, total);
            } else if (total >= Integer.MIN_VALUE && total <= Integer.MAX_VALUE) {
                builder.setField(totalSize, total.intValue());
            }
        }
        return builder.build();
    }

    /**
     * Writes a {@code .etag} component's strong entity tag to the response's {@code string etag} field: its version
     * token from core's {@link VersionTokens} in quotes ({@link EntityTags#strong}), the same token REST sends as the
     * {@code ETag} header, whatever the locale.
     *
     * @param component the output component
     * @param field the response's {@code etag} field
     */
    record ETagWriter(ResolvedComponent component, FieldDescriptor field) {

        /**
         * Returns the tag of an output.
         *
         * @param output the output record
         * @return the quoted tag, or {@code null} when the component is {@code null}, as REST sends no {@code ETag}
         * @throws IllegalStateException when the value is not a valid version token (an empty string, or one with a
         *         character an entity tag can't carry), an operation bug; the message never repeats the value
         */
        @Nullable
        String tag(Object output) {
            Method accessor = component.component().getAccessor();
            ReflectionUtils.makeAccessible(accessor);
            Object value = ReflectionUtils.invokeMethod(accessor, output);
            if (value == null) {
                return null;
            }
            try {
                return EntityTags.strong(VersionTokens.format(value));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "The etag value computed from the operation's output is not a valid entity tag: "
                                + e.getMessage(),
                        e);
            }
        }
    }

    /**
     * Writes the output. {@code null} (a {@code Void} output, or no value) is the empty message; an
     * {@code Optional} output is written from its value, which the caller unwraps.
     *
     * @param output the record, the list, or {@code null}
     * @return the response message
     * @throws IllegalStateException when a value can't be written to its field (out of range, or a null element), or
     *         the writer writes pages, which {@link #writePage} writes with their token
     */
    public DynamicMessage write(@Nullable Object output) {
        if (output == null) {
            return DynamicMessage.getDefaultInstance(message);
        }
        if (page != null) {
            // Never written as a record: its nextCursor is the operation's raw cursor.
            throw new IllegalStateException("A page is written with writePage, which takes the wrapped cursor");
        }
        RecordConverter recordConverter = record;
        if (recordConverter != null) {
            DynamicMessage written = recordConverter.writeRecord(output);
            ETagWriter etagWriter = etag;
            String tag = etagWriter == null ? null : etagWriter.tag(output);
            if (etagWriter == null || tag == null) {
                return written;
            }
            return written.toBuilder().setField(etagWriter.field(), tag).build();
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
