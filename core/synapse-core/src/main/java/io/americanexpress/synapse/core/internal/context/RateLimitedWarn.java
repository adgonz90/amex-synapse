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
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;

/**
 * Logs at WARN at most once per window and at DEBUG otherwise, so that bad input cannot flood the log.
 */
final class RateLimitedWarn {

    private static final long NEVER = Long.MIN_VALUE;

    private final Logger logger;
    private final Clock clock;
    private final long windowMillis;
    private final AtomicLong lastWarn = new AtomicLong(NEVER);

    RateLimitedWarn(Logger logger, Clock clock, Duration window) {
        this.logger = logger;
        this.clock = clock;
        this.windowMillis = window.toMillis();
    }

    void log(String message, Object... arguments) {
        long now = clock.millis();
        long last = lastWarn.get();
        if ((last == NEVER || now - last >= windowMillis) && lastWarn.compareAndSet(last, now)) {
            logger.warn(message, arguments);
        } else {
            logger.debug(message, arguments);
        }
    }
}
