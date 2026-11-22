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
package io.americanexpress.synapse.api.rest.internal.aot;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.core.io.support.SpringFactoriesLoader;

import io.americanexpress.synapse.api.rest.internal.edge.ManagementPaths;
import io.americanexpress.synapse.api.rest.internal.paging.PageBody;

import static io.americanexpress.synapse.test.aot.SynapseAotAssertions.assertThatHints;
import static org.assertj.core.api.Assertions.assertThat;

class SynapseRestRuntimeHintsTest {

    @Test
    void registersTheHealthGroupThatManagementPathsBinds() throws NoSuchMethodException {
        RuntimeHints hints = new RuntimeHints();

        new SynapseRestRuntimeHints().registerHints(hints, getClass().getClassLoader());

        assertThatHints(hints).registersReflection(ManagementPaths.HealthGroup.class);
        assertThat(RuntimeHintsPredicates
                .reflection()
                .onConstructorInvocation(ManagementPaths.HealthGroup.class.getDeclaredConstructor(String.class)))
                .accepts(hints);
    }

    @Test
    void registersTheBindingOfThePageBodyThatJacksonWrites() {
        RuntimeHints hints = new RuntimeHints();

        new SynapseRestRuntimeHints().registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.reflection().onType(PageBody.class)).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onMethodInvocation(PageBody.class, "items")).accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onMethodInvocation(PageBody.class, "nextCursor"))
                .accepts(hints);
    }

    @Test
    void isRegisteredInAotFactories() {
        assertThat(SpringFactoriesLoader
                .forResourceLocation("META-INF/spring/aot.factories")
                .load(RuntimeHintsRegistrar.class))
                .hasAtLeastOneElementOfType(SynapseRestRuntimeHints.class);
    }
}
