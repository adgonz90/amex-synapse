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
package io.americanexpress.synapse.core.internal.aot;

import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

import io.americanexpress.synapse.core.internal.context.ContextValueAccessor;

/**
 * Registers what a native image needs for the core's own types: the service file through which Micrometer's context
 * propagation finds the {@link ContextValueAccessor}, and the accessor's constructor, which the service loader calls
 * reflectively. Listed in {@code META-INF/spring/aot.factories}.
 */
public final class SynapseCoreRuntimeHints implements RuntimeHintsRegistrar {

    /** The service file that registers the {@link ContextValueAccessor} with Micrometer's context propagation. */
    static final String THREAD_LOCAL_ACCESSOR_SERVICES = "META-INF/services/io.micrometer.context.ThreadLocalAccessor";

    @Override
    public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
        hints.resources().registerPattern(THREAD_LOCAL_ACCESSOR_SERVICES);
        hints.reflection().registerType(ContextValueAccessor.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
    }
}
