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
package io.americanexpress.synapse.core.lifecycle;

import org.springframework.context.SmartLifecycle;

/**
 * Phases of Synapse's {@link SmartLifecycle} beans. Spring starts lower phases first and stops higher phases first, so
 * on shutdown the order is inbound edges, then the drain of in-flight operations, then publishers, then clients, then
 * data stores. On startup it is the reverse: data stores come up first and inbound edges last.
 *
 * <p>
 * Each phase is spaced so that beans of other libraries can sit between them. Spring Boot bounds the wait of each
 * stopping phase with {@code spring.lifecycle.timeout-per-shutdown-phase} (30 seconds by default).
 */
public final class SynapseLifecyclePhases {

    /**
     * Inbound edges (REST, gRPC, GraphQL, cloud function adapters). Stopping in this phase makes the edge stop accepting
     * requests, so nothing new enters while the drain waits. It is the phase of Spring Boot's web server graceful
     * shutdown.
     */
    public static final int INBOUND = SmartLifecycle.DEFAULT_PHASE - 2048;

    /**
     * The drain of in-flight operations. Stopping in this phase rejects operation calls that have no request context
     * and waits for the operations still running to finish, before anything they use is stopped.
     */
    public static final int DRAIN = SmartLifecycle.DEFAULT_PHASE - 4096;

    /**
     * Publishers (messaging producers and outbox relays). They stop after the drain so that events published by
     * operations that were in flight can still be sent.
     */
    public static final int PUBLISHERS = SmartLifecycle.DEFAULT_PHASE - 8192;

    /**
     * Outbound clients (HTTP, gRPC and other service clients). They stop after the publishers and before the data
     * stores.
     */
    public static final int CLIENTS = SmartLifecycle.DEFAULT_PHASE - 9216;

    /**
     * Data stores (connection pools and other persistence resources). They stop last, and start first.
     */
    public static final int DATA_STORES = SmartLifecycle.DEFAULT_PHASE - 10240;

    private SynapseLifecyclePhases() {
    }
}
