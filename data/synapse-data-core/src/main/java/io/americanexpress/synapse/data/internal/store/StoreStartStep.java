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
package io.americanexpress.synapse.data.internal.store;

/**
 * Work a store does when it starts, after its migrations, such as an access module's schema validation. Each step is a
 * bean; the store's {@link StoreLifecycle} runs the steps whose {@link #store()} is its store, in bean order, and stops
 * them in reverse.
 */
public interface StoreStartStep {

    /**
     * Returns the store this step belongs to.
     *
     * @return the store name
     */
    String store();

    /**
     * Runs the step. It may connect to the database.
     */
    void start();

    /**
     * Undoes the step when the store stops. Does nothing by default.
     */
    default void stop() {
    }
}
