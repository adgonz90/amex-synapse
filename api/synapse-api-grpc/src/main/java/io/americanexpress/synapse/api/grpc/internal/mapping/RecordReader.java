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

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.api.grpc.internal.mapping.FieldConverters.RecordConverter;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;

/**
 * Reads a method's request message into the operation's input record. Created and checked by
 * {@link MessageMapping#reader}.
 */
public final class RecordReader {

    private final @Nullable RecordConverter root;

    /** The page fields of a {@code PageQuery} input, read instead of a record. */
    private final @Nullable PageFields page;

    private final Map<String, String> fieldPaths;

    private final @Nullable FieldDescriptor ifMatch;

    private final List<FieldDescriptor> unbound;

    RecordReader(@Nullable RecordConverter root, Map<String, String> fieldPaths, @Nullable FieldDescriptor ifMatch,
            List<FieldDescriptor> unbound) {
        this(root, null, fieldPaths, ifMatch, unbound);
    }

    RecordReader(@Nullable RecordConverter root, @Nullable PageFields page, Map<String, String> fieldPaths,
            @Nullable FieldDescriptor ifMatch, List<FieldDescriptor> unbound) {
        this.root = root;
        this.page = page;
        this.fieldPaths = fieldPaths;
        this.ifMatch = ifMatch;
        this.unbound = List.copyOf(unbound);
    }

    /**
     * Reads the input. Every value that can't be converted is a violation {@code {field, "is not a valid value"}}
     * and every non-null component without a value is {@code {field, "must not be null"}}, where the field is the
     * proto field path from the request ({@code author.country_code}, {@code tags[2]}); a component set by
     * {@code .context} is named by its component path. The violations are collected in component order and thrown
     * together, at most 50 of them: reading stops at the fiftieth. {@code .context} values are read from the current
     * request context.
     *
     * <p>
     * The token field of a {@code VersionCondition} ({@code etag} or {@code if_none_match}) adds
     * {@code {field, "must be an entity tag"}} and {@code {field, "must be a single entity tag"}} to those violations.
     * Once the input is otherwise valid, in REST's order:
     * <ol>
     * <li>{@code PRECONDITION_FAILED}: a write's If-Match {@code etag} is a weak tag (which never matches under strong
     * comparison), or a write's request sends a non-empty {@code etag} or {@code if_none_match} that no
     * {@code VersionCondition} binds (it can't be verified, and ignoring it would make the write unconditional);</li>
     * <li>{@code PRECONDITION_REQUIRED}: a required condition has no value, with one violation per missing field, each
     * saying how to resubmit, as REST's 428 does for its two headers.</li>
     * </ol>
     *
     * <p>
     * A {@code PageQuery} (the input, or a component of it) reads the request's page fields (see {@link PageFields});
     * its cursor is the caller's {@code page_token}, as sent.
     *
     * @param message the request message
     * @return the input record, or {@code null} for a {@code Void} input
     * @throws SynapseException {@code VALIDATION_FAILED} with the violations, {@code PRECONDITION_FAILED},
     *         {@code PRECONDITION_REQUIRED}, or the record's own exception
     */
    public @Nullable Object read(DynamicMessage message) {
        return read(message, input -> input);
    }

    /**
     * Reads the input as {@link #read(DynamicMessage)} does, and passes it to {@code unwrap} after the violations and
     * before the preconditions it can still check, in REST's order: binding (400), then a required precondition the
     * input can't be built without ({@code PRECONDITION_FAILED} or {@code PRECONDITION_REQUIRED}), then the cursor the
     * edge unwraps (400), then an unverifiable precondition ({@code PRECONDITION_FAILED}).
     *
     * @param message the request message
     * @param unwrap replaces the caller's page token in the input with the operation's raw cursor; it may throw
     * @return the input {@code unwrap} returned, or {@code null} for a {@code Void} input
     * @throws SynapseException as {@link #read(DynamicMessage)} does, or what {@code unwrap} throws
     */
    public @Nullable Object read(DynamicMessage message, UnaryOperator<@Nullable Object> unwrap) {
        RecordConverter record = root;
        PageFields whole = page;
        FieldConverter.Reading reading = new FieldConverter.Reading();
        @Nullable
        Object read;
        try {
            if (record != null) {
                read = record.readRecord(message, reading, "");
            } else if (whole != null) {
                read = whole.read(message, reading);
            } else {
                return null;
            }
        } catch (FieldConverter.Full full) {
            // The limit was reached; the violations so far are the answer.
            throw SynapseException.ofViolations(SynapseErrors.VALIDATION_FAILED, reading.violations());
        }
        if (!reading.violations().isEmpty()) {
            throw SynapseException.ofViolations(SynapseErrors.VALIDATION_FAILED, reading.violations());
        }
        boolean unverifiable = reading.hasWeakTag() || unbound.stream().anyMatch(field -> sent(message, field));
        List<Violation> missing = reading.missingPreconditions();
        if (!missing.isEmpty()) {
            // The input can't be built without the condition, so REST answers this while binding.
            throw unverifiable
                    ? new SynapseException(SynapseErrors.PRECONDITION_FAILED)
                    : SynapseException.ofViolations(SynapseErrors.PRECONDITION_REQUIRED, missing);
        }
        Object input = unwrap.apply(read);
        if (unverifiable) {
            throw new SynapseException(SynapseErrors.PRECONDITION_FAILED);
        }
        return input;
    }

    /**
     * Tells whether the request sent an If-Match condition: the input binds a {@code VersionCondition} from the
     * {@code etag} field and the field is not empty. A write that sent one and then finds the version stale failed its
     * precondition; one that sent none lost a race. As over REST, an {@code if_none_match} doesn't count.
     *
     * @param message the request message
     * @return whether the {@code etag} field bound to a {@code VersionCondition} is set and not empty
     */
    public boolean ifMatchSent(DynamicMessage message) {
        FieldDescriptor field = ifMatch;
        return field != null && sent(message, field);
    }

    private static boolean sent(DynamicMessage message, FieldDescriptor field) {
        return !((String) message.getField(field)).isEmpty();
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
