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
package io.americanexpress.synapse.api.grpc.internal.edge;

import java.util.Locale;
import java.util.Map;

import io.grpc.Context;
import org.jspecify.annotations.Nullable;

/**
 * What the gRPC edge knows about one call, shared by the edge, the context interceptor, the operation handler and the
 * exception handler through the call's gRPC {@link Context}. The edge creates it with the caller's correlation and
 * baggage candidates; the others record what they resolve: the correlation id, the operation name, the caller's
 * locale and the binding's field paths. One instance per call, mutable; a call's callbacks run one at a time, and the
 * fields are volatile because they may run on different threads.
 */
public final class GrpcCallState {

    /** The key of the call's state in its gRPC context. */
    public static final Context.Key<GrpcCallState> KEY = Context.key("synapse-grpc-call-state");

    private final @Nullable String correlationCandidate;

    private final Map<String, String> baggageCandidates;

    private volatile @Nullable String correlationId;

    private volatile @Nullable String operationName;

    private volatile @Nullable Locale locale;

    private volatile Map<String, String> fieldPaths = Map.of();

    /**
     * Creates the state of a call.
     *
     * @param correlationCandidate the correlation id the caller sent: the correlation metadata, else the Synapse
     *     baggage field; not yet validated, or {@code null} when it sent none
     * @param baggageCandidates the values of the Synapse attribute baggage fields the caller sent, keyed by attribute
     *     name, not yet validated
     */
    public GrpcCallState(@Nullable String correlationCandidate, Map<String, String> baggageCandidates) {
        this.correlationCandidate = correlationCandidate;
        this.baggageCandidates = Map.copyOf(baggageCandidates);
    }

    /**
     * Returns the state of the call whose context is current.
     *
     * @return the state, or {@code null} outside a call the edge handles
     */
    public static @Nullable GrpcCallState current() {
        return KEY.get();
    }

    /**
     * Returns the correlation id the caller sent: the correlation metadata, else the Synapse baggage field.
     *
     * @return the candidate, not validated, or {@code null}
     */
    public @Nullable String correlationCandidate() {
        return correlationCandidate;
    }

    /**
     * Returns the Synapse attribute baggage values the caller sent.
     *
     * @return the candidates by attribute name, unmodifiable
     */
    public Map<String, String> baggageCandidates() {
        return baggageCandidates;
    }

    /**
     * Returns the call's resolved correlation id.
     *
     * @return the id, or {@code null} before it is resolved
     */
    public @Nullable String correlationId() {
        return correlationId;
    }

    /**
     * Records the call's resolved correlation id.
     *
     * @param correlationId the id
     */
    public void correlationId(String correlationId) {
        this.correlationId = correlationId;
    }

    /**
     * Returns the name of the operation the call is bound to.
     *
     * @return the operation name, or {@code null} before the call reaches an operation
     */
    public @Nullable String operationName() {
        return operationName;
    }

    /**
     * Records the name of the operation the call is bound to.
     *
     * @param operationName the operation name
     */
    public void operationName(String operationName) {
        this.operationName = operationName;
    }

    /**
     * Returns the caller's locale.
     *
     * @return the locale from {@code accept-language}, or {@code null} when the caller sent none
     */
    public @Nullable Locale locale() {
        return locale;
    }

    /**
     * Records the caller's locale.
     *
     * @param locale the locale
     */
    public void locale(Locale locale) {
        this.locale = locale;
    }

    /**
     * Returns the binding's field paths, which name violations in {@code BadRequest} details.
     *
     * @return the proto field path by record component path, unmodifiable; empty before the call reaches a binding
     */
    public Map<String, String> fieldPaths() {
        return fieldPaths;
    }

    /**
     * Records the binding's field paths. A binding's map is immutable and shared by its calls, so it is kept as it is;
     * any other map is copied.
     *
     * @param fieldPaths the proto field path (for example {@code published_on}) by record component path (for example
     *     {@code publishedOn})
     */
    public void fieldPaths(Map<String, String> fieldPaths) {
        this.fieldPaths = Map.copyOf(fieldPaths);
    }
}
