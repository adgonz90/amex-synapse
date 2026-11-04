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
package io.americanexpress.synapse.core.operation;

import java.util.concurrent.CompletableFuture;

/**
 * Publishes events to a messaging system.
 *
 * @param <E> the event type
 */
public interface PublisherOperation<E> {

    /**
     * Publishes an event and waits for the broker to accept it.
     *
     * @param event the event
     */
    void publish(E event);

    /**
     * Publishes an event without waiting.
     *
     * @param event the event
     * @return a future that completes when the broker has accepted the event
     */
    CompletableFuture<Void> publishAsync(E event);
}
