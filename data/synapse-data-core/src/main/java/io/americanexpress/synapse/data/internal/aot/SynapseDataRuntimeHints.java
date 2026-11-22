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
package io.americanexpress.synapse.data.internal.aot;

import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Registers what a native image needs for every data store: the migrations in each store's default location,
 * {@code classpath:db/migration/<store>}. Migrations in other {@code migrations.locations} need hints of the
 * application's own. Listed in {@code META-INF/spring/aot.factories}.
 */
public final class SynapseDataRuntimeHints implements RuntimeHintsRegistrar {

    /** The resource pattern of the stores' default migration locations. */
    static final String MIGRATIONS = "db/migration/*/**";

    @Override
    public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
        hints.resources().registerPattern(MIGRATIONS);
    }
}
