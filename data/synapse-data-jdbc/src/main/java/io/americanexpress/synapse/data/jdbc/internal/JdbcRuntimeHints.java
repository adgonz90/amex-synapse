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
package io.americanexpress.synapse.data.jdbc.internal;

import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.data.jdbc.core.convert.IdGeneratingEntityCallback;

/**
 * Registers what a native image needs for JDBC stores: the public methods of the auditing callback and of Spring
 * Data's id-generating callback (which each store's aggregate template adds to its own callbacks), which Spring Data
 * finds reflectively when it discovers the entity callbacks (as Spring Data JDBC's own hints do for its callbacks).
 * The dialect class each vendor names is the vendor module's to register, since its {@code INSTANCE} field is read
 * reflectively. Listed in {@code META-INF/spring/aot.factories}.
 */
public final class JdbcRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
        hints
                .reflection()
                .registerType(JdbcAuditingCallback.class, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.INVOKE_PUBLIC_METHODS);
        hints.reflection().registerType(IdGeneratingEntityCallback.class, MemberCategory.INVOKE_PUBLIC_METHODS);
    }
}
