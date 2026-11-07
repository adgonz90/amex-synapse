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

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;

import io.americanexpress.synapse.core.lifecycle.SynapseLifecyclePhases;
import io.americanexpress.synapse.data.internal.migration.StoreMigration;

/**
 * Starts a store, in the {@link SynapseLifecyclePhases#DATA_STORES} phase: before every inbound phase, so no request
 * reaches a store whose schema isn't ready. Starting runs the store's migration, unless
 * {@code synapse.data.stores.<name>.migrations.enabled} is {@code false}, then each of the store's
 * {@link StoreStartStep}s in order. This is the first time the store connects: building its beans opens no connection.
 * Stopping stops the steps that started, in reverse order.
 *
 * <p>
 * Without a migration tool on the class path there is no migration to run. A store that sets no migration keys starts
 * without one (nothing scans the class path for migrations). A store that sets {@code migrations.locations} or a
 * {@code migrations.flyway} key with migrations enabled fails when its lifecycle is built (see
 * {@link StoreFactory#lifecycle}), since its migrations would otherwise never run.
 */
public final class StoreLifecycle implements SmartLifecycle {

    /** The suffix of the bean name of a store's lifecycle. */
    public static final String BEAN_SUFFIX = "StoreLifecycle";

    private final StoreDefinition store;

    private final @Nullable StoreMigration migration;

    private final boolean migrationsEnabled;

    private final ObjectProvider<StoreStartStep> steps;

    private final List<StoreStartStep> started = new ArrayList<>();

    private volatile boolean running;

    /**
     * Creates the lifecycle.
     *
     * @param store the store
     * @param migration the store's migration, or {@code null} when no migration tool is present
     * @param migrationsEnabled whether the migration runs
     * @param steps every store's start steps; only this store's run
     */
    public StoreLifecycle(StoreDefinition store, @Nullable StoreMigration migration, boolean migrationsEnabled,
            ObjectProvider<StoreStartStep> steps) {
        this.store = store;
        this.migration = migration;
        this.migrationsEnabled = migrationsEnabled;
        this.steps = steps;
    }

    /**
     * Returns the store this lifecycle starts.
     *
     * @return the store name
     */
    public String store() {
        return store.name();
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        StoreMigration migration = this.migration;
        if (migrationsEnabled && migration != null) {
            migration.migrate();
        }
        try {
            for (StoreStartStep step : steps
                    .orderedStream()
                    .filter(step -> store.name().equals(step.store()))
                    .toList()) {
                step.start();
                started.add(step);
            }
        } catch (RuntimeException ex) {
            // Spring stops only running beans, so undo the steps that started before failing.
            stopStarted();
            throw ex;
        }
        running = true;
    }

    @Override
    public synchronized void stop() {
        stopStarted();
        running = false;
    }

    private void stopStarted() {
        for (int i = started.size() - 1; i >= 0; i--) {
            started.get(i).stop();
        }
        started.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return SynapseLifecyclePhases.DATA_STORES;
    }
}
