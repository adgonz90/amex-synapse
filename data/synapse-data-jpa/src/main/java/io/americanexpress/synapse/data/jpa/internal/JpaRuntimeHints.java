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
package io.americanexpress.synapse.data.jpa.internal;

import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/**
 * Registers what a native image needs for JPA stores: the mapping resource {@value #MAPPING_RESOURCE}, which every
 * store's persistence unit reads, and its default entity listener, whose constructor Spring calls and whose callbacks
 * Hibernate invokes reflectively. Listed in {@code META-INF/spring/aot.factories}.
 */
public final class JpaRuntimeHints implements RuntimeHintsRegistrar {

    /** The mapping resource added to every store's persistence unit. */
    static final String MAPPING_RESOURCE = JpaStoreFactory.MAPPING_RESOURCE;

    /** The default entity listener, which is package-private. */
    static final String ENTITY_CALLBACKS = "io.americanexpress.synapse.data.jpa.EntityCallbacks";

    @Override
    public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
        hints.resources().registerPattern(MAPPING_RESOURCE);
        hints
                .reflection()
                .registerType(TypeReference.of(ENTITY_CALLBACKS), MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.INVOKE_DECLARED_METHODS);
    }
}
