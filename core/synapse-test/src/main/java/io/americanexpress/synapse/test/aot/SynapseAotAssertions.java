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
package io.americanexpress.synapse.test.aot;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.util.ClassUtils;

/**
 * Entry points for assertions on AOT runtime hints.
 */
public final class SynapseAotAssertions {

    private SynapseAotAssertions() {
    }

    /**
     * Applies the registrars to fresh hints and asserts on the result.
     *
     * @param registrars the registrars
     * @return assertions on the combined hints
     */
    public static RuntimeHintsAssert assertThatHints(RuntimeHintsRegistrar... registrars) {
        RuntimeHints hints = new RuntimeHints();
        ClassLoader loader = ClassUtils.getDefaultClassLoader();
        for (RuntimeHintsRegistrar registrar : registrars) {
            registrar.registerHints(hints, loader);
        }
        return new RuntimeHintsAssert(hints);
    }

    /**
     * Asserts on existing hints.
     *
     * @param hints the hints
     * @return assertions on them
     */
    public static RuntimeHintsAssert assertThatHints(RuntimeHints hints) {
        return new RuntimeHintsAssert(hints);
    }
}
