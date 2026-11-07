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

import java.util.Set;
import java.util.concurrent.Semaphore;

/**
 * Caps the gRPC calls handled at the same time ({@code synapse.api.grpc.max-concurrent-calls}). The permits are a
 * non-fair {@link Semaphore}: a call either gets one at once or is rejected, and never waits. Calls to the gRPC health
 * and reflection services are never counted ({@link #isUncounted(String)}), so health checks are answered under full
 * load, as REST never counts Actuator.
 */
public final class GrpcConcurrencyLimit {

    private static final Set<String> UNCOUNTED = Set
            .of("grpc.health.v1.Health", "grpc.reflection.v1.ServerReflection",
                    "grpc.reflection.v1alpha.ServerReflection");

    private final Semaphore permits;

    /**
     * Creates the limit.
     *
     * @param maxConcurrentCalls the most calls handled at the same time; at least 1
     * @throws IllegalArgumentException when the limit is below 1
     */
    public GrpcConcurrencyLimit(int maxConcurrentCalls) {
        if (maxConcurrentCalls < 1) {
            throw new IllegalArgumentException("maxConcurrentCalls must be at least 1 but was " + maxConcurrentCalls);
        }
        this.permits = new Semaphore(maxConcurrentCalls);
    }

    /**
     * Takes a permit if one is free, without waiting.
     *
     * @return {@code true} when the call may proceed; the caller must then {@link #release()} it exactly once
     */
    public boolean tryAcquire() {
        return permits.tryAcquire();
    }

    /**
     * Returns a permit taken with {@link #tryAcquire()}.
     */
    public void release() {
        permits.release();
    }

    /**
     * Returns whether calls to a service are never counted: the gRPC health service and both reflection services.
     *
     * @param serviceName the full service name, for example {@code grpc.health.v1.Health}
     * @return {@code true} for an uncounted service
     */
    public static boolean isUncounted(String serviceName) {
        return UNCOUNTED.contains(serviceName);
    }
}
