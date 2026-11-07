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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import io.americanexpress.synapse.core.lifecycle.SynapseLifecyclePhases;
import io.americanexpress.synapse.data.DataAccess;
import io.americanexpress.synapse.data.internal.migration.StoreMigration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoreLifecycleTest {

    private static final StoreDefinition BOOKS = new StoreDefinition("books", "books", "com.example.books",
            DataAccess.JDBC, false, "com.example.books.BooksStore");

    private final List<String> events = new ArrayList<>();

    @Test
    void phaseIsDataStores() {
        StoreLifecycle lifecycle = lifecycle(migration("books"), true);

        assertThat(lifecycle.getPhase()).isEqualTo(SynapseLifecyclePhases.DATA_STORES);
        assertThat(lifecycle.isAutoStartup()).isTrue();
        assertThat(lifecycle.isRunning()).isFalse();
        assertThat(lifecycle.store()).isEqualTo("books");
    }

    @Test
    void migratesThenRunsTheStoresSteps() {
        StoreLifecycle lifecycle = lifecycle(migration("books"), true, step("books", "first"), step("legacy", "other"),
                step("books", "second"));

        lifecycle.start();

        assertThat(events).containsExactly("migrate books", "start books first", "start books second");
        assertThat(lifecycle.isRunning()).isTrue();
    }

    @Test
    void disabledMigrationsSkipMigrate() {
        StoreLifecycle lifecycle = lifecycle(migration("books"), false, step("books", "first"));

        lifecycle.start();

        assertThat(events).containsExactly("start books first");
    }

    @Test
    void withoutAMigrationToolTheStepsStillRun() {
        StoreLifecycle lifecycle = lifecycle(null, true, step("books", "first"));

        lifecycle.start();

        assertThat(events).containsExactly("start books first");
    }

    @Test
    void aFailedMigrationRunsNoStep() {
        StoreMigration failing = new StoreMigration() {

            @Override
            public String store() {
                return "books";
            }

            @Override
            public void migrate() {
                throw new IllegalStateException("The migrations of the store 'books' failed.");
            }
        };
        StoreLifecycle lifecycle = lifecycle(failing, true, step("books", "first"));

        assertThatThrownBy(lifecycle::start).hasMessage("The migrations of the store 'books' failed.");
        assertThat(events).isEmpty();
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    void aFailedStepStopsTheStepsThatStarted() {
        StoreStartStep failing = new StoreStartStep() {

            @Override
            public String store() {
                return "books";
            }

            @Override
            public void start() {
                throw new IllegalStateException("validation failed");
            }
        };
        StoreLifecycle lifecycle = lifecycle(migration("books"), true, step("books", "first"), failing,
                step("books", "third"));

        assertThatThrownBy(lifecycle::start).hasMessage("validation failed");
        assertThat(events).containsExactly("migrate books", "start books first", "stop books first");
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    void startingTwiceRunsOnce() {
        StoreLifecycle lifecycle = lifecycle(migration("books"), true, step("books", "first"));

        lifecycle.start();
        lifecycle.start();

        assertThat(events).containsExactly("migrate books", "start books first");
    }

    @Test
    void stopRunsStepsInReverse() {
        StoreLifecycle lifecycle = lifecycle(migration("books"), true, step("books", "first"), step("legacy", "other"),
                step("books", "second"));
        lifecycle.start();
        events.clear();

        lifecycle.stop();

        assertThat(events).containsExactly("stop books second", "stop books first");
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    void stoppingBeforeStartingDoesNothing() {
        StoreLifecycle lifecycle = lifecycle(migration("books"), true, step("books", "first"));

        lifecycle.stop();

        assertThat(events).isEmpty();
    }

    private StoreLifecycle lifecycle(@Nullable StoreMigration migration, boolean enabled, StoreStartStep... steps) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        for (int i = 0; i < steps.length; i++) {
            beanFactory.registerSingleton("step" + i, steps[i]);
        }
        return new StoreLifecycle(BOOKS, migration, enabled, beanFactory.getBeanProvider(StoreStartStep.class));
    }

    private StoreMigration migration(String store) {
        return new StoreMigration() {

            @Override
            public String store() {
                return store;
            }

            @Override
            public void migrate() {
                events.add("migrate " + store);
            }
        };
    }

    private StoreStartStep step(String store, String name) {
        return new StoreStartStep() {

            @Override
            public String store() {
                return store;
            }

            @Override
            public void start() {
                events.add("start " + store + " " + name);
            }

            @Override
            public void stop() {
                events.add("stop " + store + " " + name);
            }
        };
    }
}
