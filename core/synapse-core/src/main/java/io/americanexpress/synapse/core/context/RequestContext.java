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
package io.americanexpress.synapse.core.context;

import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import io.americanexpress.synapse.core.internal.context.ContextValue;

/**
 * Read-only view of the request that the current thread is serving.
 *
 * <p>
 * Inbound edges (API plugins, subscribers) set the context; operations and business code only read it. The context
 * follows the request across threads through Micrometer's context propagation.
 */
public interface RequestContext {

    /**
     * Returns the context of the current thread.
     *
     * @return the active context
     * @throws IllegalStateException when no Synapse request context is active
     */
    static RequestContext current() {
        return find().orElseThrow(() -> new IllegalStateException("No Synapse request context is active"));
    }

    /**
     * Returns the context of the current thread, if there is one.
     *
     * @return the active context, or empty
     */
    static Optional<RequestContext> find() {
        return Optional.ofNullable(ContextValue.currentOrNull());
    }

    /**
     * Returns the correlation id of the request.
     *
     * @return the correlation id, never empty
     */
    String correlationId();

    /**
     * Returns the verified caller, if there is one.
     *
     * @return the principal, or empty
     */
    Optional<Principal> principal();

    /**
     * Returns the locale of the request, if known.
     *
     * @return the locale, or empty
     */
    Optional<Locale> locale();

    /**
     * Returns the instant by which the request must finish, if it has a deadline.
     *
     * @return the deadline, or empty
     */
    Optional<Instant> deadline();

    /**
     * Returns the time left until the deadline.
     *
     * @param clock the clock to read the current time from
     * @return the remaining time, never negative ({@link Duration#ZERO} once the deadline has passed), or empty when
     *         there is no deadline
     */
    default Optional<Duration> remaining(Clock clock) {
        return deadline().map(deadline -> {
            Duration left = Duration.between(clock.instant(), deadline);
            return left.isNegative() ? Duration.ZERO : left;
        });
    }

    /**
     * Returns the allow-listed attributes of the request (for example tenant or channel), in insertion order. They
     * are inbound data and must never drive security decisions.
     *
     * @return an unmodifiable map
     */
    Map<String, String> attributes();

    /**
     * Returns one attribute.
     *
     * @param name the attribute name
     * @return the value, or empty
     */
    default Optional<String> attribute(String name) {
        return Optional.ofNullable(attributes().get(name));
    }
}
