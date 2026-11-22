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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import io.grpc.Deadline;
import org.jspecify.annotations.Nullable;

/**
 * Turns a call's gRPC deadline into the deadline of its Synapse request context: now plus the time the gRPC deadline
 * has left, capped to the maximum; without a gRPC deadline, now plus the default timeout. An expired gRPC deadline
 * gives a deadline in the past, which the operation pipeline rejects before the operation runs. The cap applies only
 * to the request context: the call's gRPC deadline itself is left as the caller set it.
 */
public final class GrpcDeadlinePolicy {

    private final Duration defaultTimeout;

    private final Duration max;

    private final Clock clock;

    /**
     * Creates the policy.
     *
     * @param defaultTimeout the timeout used when the caller sets no gRPC deadline
     * @param max the longest timeout honored; a longer gRPC deadline is capped to it
     * @param clock the clock the deadline is computed from
     */
    public GrpcDeadlinePolicy(Duration defaultTimeout, Duration max, Clock clock) {
        this.defaultTimeout = defaultTimeout;
        this.max = max;
        this.clock = clock;
    }

    /**
     * Returns the deadline of a call received now.
     *
     * @param grpcDeadline the call's gRPC deadline ({@code Context.current().getDeadline()}), or {@code null} when it
     *     has none
     * @return now plus the remaining time capped to the maximum (in the past when the gRPC deadline expired), or now
     *     plus the default timeout without a gRPC deadline
     */
    public Instant deadline(@Nullable Deadline grpcDeadline) {
        Instant now = clock.instant();
        if (grpcDeadline == null) {
            return now.plus(defaultTimeout);
        }
        Duration remaining = Duration.ofNanos(grpcDeadline.timeRemaining(TimeUnit.NANOSECONDS));
        return now.plus(remaining.compareTo(max) > 0 ? max : remaining);
    }
}
