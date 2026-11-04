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
package io.americanexpress.synapse.api.rest.internal.edge;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * Turns the caller's request timeout into the request's deadline. A positive whole number of milliseconds is honored up
 * to the maximum; anything else (absent, empty, zero, negative, a fraction, a sign, a unit or spaces) is ignored and
 * the default timeout applies.
 */
public final class DeadlinePolicy {

    private final Duration defaultTimeout;

    private final Duration max;

    private final Clock clock;

    /**
     * Creates the policy.
     *
     * @param defaultTimeout the timeout used when the caller sends none, or a malformed one
     * @param max the longest timeout honored; larger requested values are capped to it
     * @param clock the clock the deadline is computed from
     */
    public DeadlinePolicy(Duration defaultTimeout, Duration max, Clock clock) {
        this.defaultTimeout = defaultTimeout;
        this.max = max;
        this.clock = clock;
    }

    /**
     * Returns the deadline of a request received now.
     *
     * @param requestTimeoutHeader the value of the request timeout header in milliseconds, or {@code null} when absent
     * @return now plus the requested timeout capped to the maximum, or now plus the default timeout when the value is
     *     absent or malformed
     */
    public Instant deadline(@Nullable String requestTimeoutHeader) {
        Instant now = clock.instant();
        Duration requested = requested(requestTimeoutHeader);
        if (requested == null) {
            return now.plus(defaultTimeout);
        }
        return now.plus(requested.compareTo(max) > 0 ? max : requested);
    }

    /** The requested timeout, capped to the maximum when too large for a {@code long}; {@code null} when malformed. */
    private @Nullable Duration requested(@Nullable String header) {
        if (header == null || header.isEmpty()) {
            return null;
        }
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        long millis;
        try {
            millis = Long.parseLong(header);
        } catch (NumberFormatException tooLarge) {
            return max;
        }
        return millis > 0 ? Duration.ofMillis(millis) : null;
    }
}
