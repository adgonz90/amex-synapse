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

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.core.io.support.SpringFactoriesLoader;

import io.americanexpress.synapse.core.internal.context.ContextValueAccessor;

import static org.assertj.core.api.Assertions.assertThat;

class SynapseCoreRuntimeHintsTest {

    private static final String SERVICES = "META-INF/services/io.micrometer.context.ThreadLocalAccessor";

    @Test
    void registersServiceLoaderResourceAndAccessor() throws NoSuchMethodException {
        RuntimeHints hints = new RuntimeHints();

        new SynapseCoreRuntimeHints().registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.resource().forResource(SERVICES)).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(ContextValueAccessor.class)).accepts(hints);
        assertThat(RuntimeHintsPredicates
                .reflection()
                .onConstructorInvocation(ContextValueAccessor.class.getConstructor())).accepts(hints);
    }

    @Test
    void isRegisteredInAotFactories() {
        assertThat(SpringFactoriesLoader
                .forResourceLocation("META-INF/spring/aot.factories")
                .load(RuntimeHintsRegistrar.class))
                .hasAtLeastOneElementOfType(SynapseCoreRuntimeHints.class);
    }
}
