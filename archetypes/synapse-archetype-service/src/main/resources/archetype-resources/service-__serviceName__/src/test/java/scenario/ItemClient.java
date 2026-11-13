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
package ${package}.scenario;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The item operations over one protocol, with every answer as an {@link Outcome}. Each implementation also checks its
 * own protocol's status for every answer (REST 201 with a {@code Location}, 400 or 404; gRPC {@code NOT_FOUND} or
 * {@code ALREADY_EXISTS}, for example), so a wrong status fails that protocol's run even when the outcome is right.
 * There is no conditional read: REST's 304 has no gRPC form, so it is tested in {@code ItemRestTest} only.
 */
public interface ItemClient extends AutoCloseable {

    /**
     * Adds an item.
     *
     * @param name the name
     * @param description the description, or {@code null} to send none
     * @return {@code CREATED} with the item and its etag, or the failure
     */
    Outcome create(String name, @Nullable String description);

    /**
     * Reads an item.
     *
     * @param id the id
     * @return {@code OK} with the item and its etag, or the failure
     */
    Outcome get(UUID id);

    /**
     * Reads an item with a correlation id, as {@link #get(UUID)} does, and returns the answer with the correlation id
     * it carries: REST's {@code X-Correlation-Id} header, the gRPC {@code x-correlation-id} headers or trailers.
     *
     * @param id the id of the item; an unknown one gets a failure, which carries the id too
     * @param correlationId the correlation id to send
     * @return the read's outcome and the answer's correlation id
     */
    Echo echoedCorrelationId(UUID id, String correlationId);

    /** Releases the client's connections. */
    @Override
    void close();

    /**
     * A read and the correlation id its answer carried.
     *
     * @param outcome the read's outcome
     * @param correlationId the correlation id of the answer, or {@code null} when it has none
     */
    record Echo(Outcome outcome, @Nullable String correlationId) {
    }
}
