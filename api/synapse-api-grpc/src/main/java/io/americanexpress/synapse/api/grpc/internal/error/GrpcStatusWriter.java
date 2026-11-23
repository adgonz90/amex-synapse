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
package io.americanexpress.synapse.api.grpc.internal.error;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import com.google.protobuf.Any;
import com.google.protobuf.CodedOutputStream;
import com.google.rpc.BadRequest;
import com.google.rpc.ErrorInfo;
import com.google.rpc.RetryInfo;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.protobuf.StatusProto;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.americanexpress.synapse.api.grpc.internal.edge.GrpcCallState;
import io.americanexpress.synapse.api.grpc.internal.mapping.MessageMapping;
import io.americanexpress.synapse.core.autoconfigure.SynapseErrorProperties;
import io.americanexpress.synapse.core.error.ErrorMessages;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;

/**
 * Writes a {@link SynapseException} as a gRPC status with {@code google.rpc} error details (AIP-193). The status
 * carries, in this order:
 * <ul>
 * <li>{@code ErrorInfo}: the error code as {@code reason}, the service's domain, and the correlation id in
 * {@code metadata};</li>
 * <li>{@code BadRequest}, when the error has violations: each names the proto field path the binding maps the record
 * component to (in a list element too, keeping its index: {@code authors[0].country_code}; in a map value without
 * its key, as the reader names it: {@code authors_by_rank.country_code}), or the component path when it maps none.
 * The detail keeps the first violations that fit in about 3 KiB, whole ones only, so that its base64 form in the
 * trailer stays within 4 KiB and a client's default 8 KiB metadata limit always receives the status;</li>
 * <li>{@code RetryInfo}, when a retry delay is given.</li>
 * </ul>
 * The description is the detail message, in the caller's locale, only when
 * {@link SynapseErrorProperties#includeDetail()} is {@code true}; otherwise it is empty. A
 * {@link NotFoundWithoutArgumentsException} is described with {@code NOT_FOUND}'s message. Stack traces, exception
 * class names and internal messages are never written.
 * <p>
 * {@link #writeForeign(Status, Metadata, GrpcCallState)} rewrites a status that Synapse didn't write, keeping its
 * code.
 */
public final class GrpcStatusWriter {

    /**
     * The {@code ErrorInfo.metadata} key that carries the correlation id. It is part of the wire contract clients
     * read, so it is fixed here and doesn't follow any internal name.
     */
    public static final String CORRELATION_ID_METADATA = "correlationId";

    /**
     * The most bytes of field violations a {@code BadRequest} detail carries: with the {@code Any} wrapping (under
     * 64 bytes), the packed detail stays within 3 KiB, which is 4 KiB in base64.
     */
    static final int MAX_BAD_REQUEST_BYTES = 3 * 1024 - 64;

    private static final Logger LOG = LoggerFactory.getLogger("io.americanexpress.synapse.api.grpc");

    /** The trailer that carries a status's {@code google.rpc.Status}. */
    private static final Metadata.Key<byte[]> STATUS_DETAILS = Metadata.Key
            .of("grpc-status-details-bin", Metadata.BINARY_BYTE_MARSHALLER);

    private final GrpcErrorStatuses statuses;

    private final ErrorMessages messages;

    private final SynapseErrorProperties properties;

    private final String domain;

    /**
     * Creates the writer.
     *
     * @param statuses decides the gRPC status of an error
     * @param messages resolves the detail message
     * @param properties the error settings
     * @param domain the {@code ErrorInfo} domain: the service's name
     */
    public GrpcStatusWriter(GrpcErrorStatuses statuses, ErrorMessages messages, SynapseErrorProperties properties,
            String domain) {
        this.statuses = statuses;
        this.messages = messages;
        this.properties = properties;
        this.domain = domain;
    }

    /**
     * Writes the error.
     *
     * @param error the error
     * @param state the call's state, or {@code null} outside a call the edge handles
     * @return the status to close the call with
     */
    public StatusException write(SynapseException error, @Nullable GrpcCallState state) {
        return build(error, state, null);
    }

    /**
     * Writes the error with a {@code RetryInfo} detail.
     *
     * @param error the error
     * @param state the call's state, or {@code null} outside a call the edge handles
     * @param retryDelay how long the caller should wait before retrying; zero or positive
     * @return the status to close the call with
     * @throws IllegalArgumentException when the delay is negative, which {@code google.protobuf.Duration} in
     *     {@code RetryInfo} can't express as a delay
     */
    public StatusException write(SynapseException error, @Nullable GrpcCallState state, Duration retryDelay) {
        if (retryDelay.isNegative()) {
            throw new IllegalArgumentException("retryDelay must not be negative but was " + retryDelay);
        }
        return build(error, state, retryDelay);
    }

    /**
     * Writes a status that Synapse didn't write: a hand-written service's own, typically a downstream call's failure
     * it forwards (decision 24). Its code is kept. Its description is kept only when
     * {@link SynapseErrorProperties#includeDetail()} is {@code true}, and is empty otherwise. Its
     * {@code grpc-status-details-bin} is always replaced by Synapse's {@code ErrorInfo}: the code the status maps back
     * to ({@link GrpcErrorStatuses#codeFor(Status.Code)}) as {@code reason}, the service's domain and the correlation
     * id. Its other trailers are kept only when {@code includeDetail()} is {@code true}. The status's cause is never
     * sent. One DEBUG line on {@code io.americanexpress.synapse.api.grpc} records the code and the original
     * description.
     *
     * @param status the status, not OK
     * @param trailers the status's trailers; not changed
     * @param state the call's state, or {@code null} outside a call the edge handles
     * @return the status to close the call with
     * @throws IllegalArgumentException when the status is OK
     */
    public StatusException writeForeign(Status status, Metadata trailers, @Nullable GrpcCallState state) {
        if (status.isOk()) {
            throw new IllegalArgumentException("status must not be OK");
        }
        Status.Code code = status.getCode();
        String original = status.getDescription();
        LOG.debug("Rewrote a {} status that Synapse didn't write; its description was: {}", code, original);
        boolean includeDetail = properties.includeDetail();
        com.google.rpc.Status written = com.google.rpc.Status
                .newBuilder()
                .setCode(code.value())
                .setMessage(includeDetail && original != null ? original : "")
                .addDetails(errorInfo(GrpcErrorStatuses.codeFor(code).code(), state))
                .build();
        return StatusProto.toStatusException(written, foreignTrailers(trailers));
    }

    /**
     * Returns the trailers of a status that Synapse didn't write that may reach the caller, as
     * {@link #writeForeign} keeps them: a copy without {@code grpc-status-details-bin} when
     * {@link SynapseErrorProperties#includeDetail()} is {@code true}, and none otherwise.
     *
     * @param trailers the status's trailers; not changed
     * @return the trailers to send, a new {@link Metadata}
     */
    public Metadata foreignTrailers(Metadata trailers) {
        Metadata kept = new Metadata();
        if (properties.includeDetail()) {
            kept.merge(trailers);
            kept.discardAll(STATUS_DETAILS);
        }
        return kept;
    }

    private StatusException build(SynapseException error, @Nullable GrpcCallState state,
            @Nullable Duration retryDelay) {
        Status.Code code = statuses.statusFor(error, state == null ? null : state.operationName());
        Locale locale = state == null ? null : state.locale();
        String message = properties.includeDetail() ? messages.detail(describedAs(error), locale) : "";
        com.google.rpc.Status.Builder status = com.google.rpc.Status
                .newBuilder()
                .setCode(code.value())
                .setMessage(message)
                .addDetails(errorInfo(error.code(), state));
        List<Violation> violations = error.violations();
        if (!violations.isEmpty()) {
            Map<String, String> fieldPaths = state == null ? Map.of() : state.fieldPaths();
            BadRequest.Builder badRequest = BadRequest.newBuilder();
            int size = 0;
            for (Violation violation : violations) {
                BadRequest.FieldViolation fieldViolation = BadRequest.FieldViolation
                        .newBuilder()
                        .setField(protoPath(violation.field(), fieldPaths))
                        .setDescription(violation.message())
                        .build();
                size += CodedOutputStream
                        .computeMessageSize(BadRequest.FIELD_VIOLATIONS_FIELD_NUMBER,
                                fieldViolation);
                if (size > MAX_BAD_REQUEST_BYTES) {
                    // The rest are dropped whole, never cut, so the trailer always fits.
                    break;
                }
                badRequest.addFieldViolations(fieldViolation);
            }
            status.addDetails(Any.pack(badRequest.build()));
        }
        if (retryDelay != null) {
            status
                    .addDetails(Any
                            .pack(RetryInfo
                                    .newBuilder()
                                    .setRetryDelay(com.google.protobuf.Duration
                                            .newBuilder()
                                            .setSeconds(retryDelay.getSeconds())
                                            .setNanos(retryDelay.getNano()))
                                    .build()));
        }
        return StatusProto.toStatusException(status.build(), new Metadata());
    }

    /**
     * Returns the proto field path of a violation's component path, which core writes as dots plus {@code [index]}
     * and never with a map key ({@code ViolationPaths}). The binding lists list and map elements with {@code []}
     * ({@code authors[].countryCode}); the path is read against those entries, segment by segment. A list element
     * keeps its index, which must be a number ({@code authors[0].countryCode} is {@code authors[0].country_code}); a
     * {@code .} after a map continues into its value, as the reader names it ({@code authorsByRank.countryCode} is
     * {@code authors_by_rank.country_code}). The result is built only from the binding's proto paths and list indexes,
     * so no part of the caller's data is ever echoed. A path that the binding doesn't map is returned unchanged when
     * it has no brackets; one with brackets that can't be read against the binding, such as a bracket after a map or
     * the binding's own {@code []} ({@code authorsByRank[].countryCode}), is cut before its first bracket, so the
     * binding's element markers never reach the caller.
     * <p>
     * Without field paths every path is returned unchanged. That is the case for the request reader's own violations,
     * which are already proto paths with list indexes only (the operation handler records the field paths once the
     * request is read), and for calls that reach no binding.
     */
    static String protoPath(String field, Map<String, String> fieldPaths) {
        // Only a field without brackets is a binding key as it is: a key with [] maps to the binding's markers ([] and
        // {}), so a field that has brackets is read against the binding instead.
        String mapped = field.indexOf('[') < 0 ? fieldPaths.get(field) : null;
        if (mapped != null) {
            return mapped;
        }
        if (fieldPaths.isEmpty()) {
            return field;
        }
        String resolved = new PathReader(field, fieldPaths).name(0, "", List.of());
        if (resolved != null) {
            return resolved;
        }
        int bracket = field.indexOf('[');
        if (bracket < 0) {
            return field;
        }
        // Fail closed: what follows the first bracket can't be read against the binding.
        String component = field.substring(0, bracket);
        return fieldPaths.getOrDefault(component, component);
    }

    /** Reads a violation's component path against a binding's field paths, left to right, in one pass. */
    private static final class PathReader {

        private final String field;

        private final Map<String, String> fieldPaths;

        PathReader(String field, Map<String, String> fieldPaths) {
            this.field = field;
            this.fieldPaths = fieldPaths;
        }

        /** Reads a component name at {@code from}; {@code key} is the binding key read so far. */
        @Nullable
        String name(int from, String key, List<String> indexes) {
            int end = from;
            while (end < field.length() && field.charAt(end) != '.' && field.charAt(end) != '[') {
                end++;
            }
            String named = key + field.substring(from, end);
            if (end == from || !fieldPaths.containsKey(named)) {
                return null;
            }
            return next(end, named, indexes);
        }

        /** Reads what follows a complete segment at {@code at}: the end, {@code .name} or {@code [index]}. */
        @Nullable
        String next(int at, String key, List<String> indexes) {
            if (at == field.length()) {
                return fill(Objects.requireNonNull(fieldPaths.get(key)), indexes);
            }
            if (field.charAt(at) == '.') {
                return name(at + 1, mapValues(key) + ".", indexes);
            }
            if (field.charAt(at) != '[') {
                return null;
            }
            String element = mapValues(key) + "[]";
            String proto = fieldPaths.get(element);
            // A bracket holds a list index; one for a map would hold its key, which core never writes.
            if (proto == null || !proto.endsWith(MessageMapping.LIST_ELEMENT)) {
                return null;
            }
            int close = field.indexOf(']', at);
            if (close < 0 || !isIndex(field.substring(at + 1, close))) {
                return null;
            }
            List<String> more = new ArrayList<>(indexes);
            more.add(field.substring(at + 1, close));
            return next(close + 1, element, more);
        }

        /**
         * Returns the binding key of the values of the map at {@code key}, since a map value adds nothing to core's
         * path; {@code key} itself when it isn't a map. A proto map's value is never a map or a list, so there is at
         * most one.
         */
        private String mapValues(String key) {
            String values = key + "[]";
            String proto = fieldPaths.get(values);
            return proto != null && proto.endsWith(MessageMapping.MAP_VALUE) ? values : key;
        }

        private static boolean isIndex(String text) {
            return !text.isEmpty() && text.chars().allMatch(c -> c >= '0' && c <= '9');
        }

        /** Puts the list indexes in the proto path's list markers, in order, and drops its map markers. */
        private static String fill(String proto, List<String> indexes) {
            String path = proto.replace(MessageMapping.MAP_VALUE, "");
            for (String index : indexes) {
                path = path.replaceFirst(Pattern.quote(MessageMapping.LIST_ELEMENT), "[" + index + "]");
            }
            return path;
        }
    }

    /** The {@code ErrorInfo} detail of a written status. */
    private Any errorInfo(String reason, @Nullable GrpcCallState state) {
        return Any
                .pack(ErrorInfo
                        .newBuilder()
                        .setReason(reason)
                        .setDomain(domain)
                        .putMetadata(CORRELATION_ID_METADATA, correlationId(state))
                        .build());
    }

    /**
     * Returns the error whose message describes this one: {@code NOT_FOUND} for a not-found error the edge raised
     * without the arguments its code's template needs, so neither that template nor the code's {@code MessageSource}
     * entry is shown unformatted; otherwise the error itself.
     */
    private static SynapseException describedAs(SynapseException error) {
        return error instanceof NotFoundWithoutArgumentsException
                ? new SynapseException(SynapseErrors.NOT_FOUND)
                : error;
    }

    /**
     * Returns the call's correlation id; when the call has none yet (it failed before the edge resolved one), a new
     * one, recorded on the call so the correlation trailer carries the same id.
     */
    private static String correlationId(@Nullable GrpcCallState state) {
        String resolved = state == null ? null : state.correlationId();
        if (resolved != null) {
            return resolved;
        }
        String generated = UUID.randomUUID().toString();
        if (state != null) {
            state.correlationId(generated);
        }
        return generated;
    }
}
