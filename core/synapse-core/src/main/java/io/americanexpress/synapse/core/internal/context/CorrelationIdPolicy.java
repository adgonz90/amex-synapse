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
package io.americanexpress.synapse.core.internal.context;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accepts a caller's correlation id when it is 1 to {@code maxLength} characters of {@code [A-Za-z0-9._:-]} and
 * otherwise generates a UUID. A replaced id is logged sanitized and truncated, at WARN at most once a minute.
 */
public final class CorrelationIdPolicy {

    private static final Logger LOGGER = LoggerFactory.getLogger(CorrelationIdPolicy.class);

    private final int maxLength;
    private final RateLimitedWarn replaced;

    /**
     * Creates a policy that uses the system clock for its log rate limit.
     *
     * @param maxLength the longest id accepted
     */
    public CorrelationIdPolicy(int maxLength) {
        this(maxLength, Clock.systemUTC());
    }

    /**
     * Creates a policy.
     *
     * @param maxLength the longest id accepted
     * @param clock the clock that drives the log rate limit
     */
    public CorrelationIdPolicy(int maxLength, Clock clock) {
        this.maxLength = maxLength;
        this.replaced = new RateLimitedWarn(LOGGER, clock, Duration.ofMinutes(1));
    }

    /**
     * Returns the candidate when valid, or a new UUID.
     *
     * @param candidate the id the caller sent, if any
     * @return a valid correlation id
     */
    public String acceptOrGenerate(@Nullable String candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return UUID.randomUUID().toString();
        }
        if (isValid(candidate)) {
            return candidate;
        }
        replaced.log("Replaced an invalid correlation id with a generated one: '{}'", sanitize(candidate));
        return UUID.randomUUID().toString();
    }

    private boolean isValid(String candidate) {
        if (candidate.length() > maxLength) {
            return false;
        }
        for (int i = 0; i < candidate.length(); i++) {
            if (!isAllowed(candidate.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private String sanitize(String candidate) {
        String truncated = candidate.length() > maxLength ? candidate.substring(0, maxLength) : candidate;
        StringBuilder out = new StringBuilder(truncated.length());
        for (int i = 0; i < truncated.length(); i++) {
            char c = truncated.charAt(i);
            out.append(isAllowed(c) ? c : '_');
        }
        return out.toString();
    }

    private static boolean isAllowed(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '_'
                || c == ':' || c == '-';
    }
}
