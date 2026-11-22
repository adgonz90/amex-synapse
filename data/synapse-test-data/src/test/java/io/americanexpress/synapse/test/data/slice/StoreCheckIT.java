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
package io.americanexpress.synapse.test.data.slice;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestContextManager;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.test.data.SynapseDataTest;
import io.americanexpress.synapse.test.data.SynapseDataTestContextCustomizerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A {@code @SynapseDataTest} whose store no {@code @SynapseDataStore} declares fails when its context loads, with a
 * message that names the value and the declared stores, instead of failing each test on a missing transaction
 * manager. The slices are loaded through Spring's {@link TestContextManager}, as the JUnit extension loads them.
 */
class StoreCheckIT {

    @Test
    void aStoreThatNoStoreClassDeclaresFails() {
        assertThatThrownBy(() -> load(MisspeltStoreSlice.class))
                .hasRootCauseInstanceOf(SynapseConfigurationException.class)
                .rootCause()
                .hasMessage("@SynapseDataTest(\"bokks\") names a store that no @SynapseDataStore(\"bokks\") declares. "
                        + "Declared stores: books, legacy. Scanned packages: io.americanexpress.synapse.test.data.slice.")
                .satisfies(failure -> assertThat(((SynapseConfigurationException) failure).action())
                        .isEqualTo("Name one of the declared stores, such as @SynapseDataTest(\"books\")."));
    }

    @Test
    void anInvalidStoreNameFails() {
        assertThatThrownBy(() -> load(InvalidStoreSlice.class))
                .hasRootCauseInstanceOf(SynapseConfigurationException.class)
                .rootCause()
                .hasMessage("'Books' is not a valid store name (@SynapseDataTest(\"Books\")).");
    }

    @Test
    void aDeclaredStoreStarts() throws Exception {
        load(DeclaredStoreSlice.class);
    }

    /** The check adds nothing to the context cache key but the store. */
    @Test
    void slicesOfTheSameStoreShareTheCheck() {
        SynapseDataTestContextCustomizerFactory factory = new SynapseDataTestContextCustomizerFactory();

        assertThat(factory.createContextCustomizer(DeclaredStoreSlice.class, List.of()))
                .isEqualTo(factory.createContextCustomizer(SynapseDataTestIT.class, List.of()))
                .isNotEqualTo(factory.createContextCustomizer(MisspeltStoreSlice.class, List.of()));
        assertThat(factory.createContextCustomizer(StoreCheckIT.class, List.of())).isNull();
    }

    private static void load(Class<?> testClass) throws Exception {
        TestContextManager manager = new TestContextManager(testClass);
        manager.prepareTestInstance(testClass.getDeclaredConstructor().newInstance());
    }

    @SynapseDataTest("bokks")
    static class MisspeltStoreSlice {
    }

    @SynapseDataTest("Books")
    static class InvalidStoreSlice {
    }

    @SynapseDataTest("books")
    static class DeclaredStoreSlice {
    }
}
