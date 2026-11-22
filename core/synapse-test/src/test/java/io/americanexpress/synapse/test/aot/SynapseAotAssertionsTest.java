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

import java.io.Serializable;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

import static io.americanexpress.synapse.test.aot.SynapseAotAssertions.assertThatHints;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SynapseAotAssertionsTest {

    static class Registrar implements RuntimeHintsRegistrar {
        @Override
        public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
            hints.reflection().registerType(String.class, MemberCategory.INVOKE_PUBLIC_METHODS);
            hints.resources().registerPattern("META-INF/demo.properties");
            hints.proxies().registerJdkProxy(Runnable.class, Serializable.class);
        }
    }

    @Test
    void hintAssertionsPassAndFail() {
        assertThatHints(new Registrar())
                .registersReflection(String.class)
                .registersReflection(String.class, MemberCategory.INVOKE_PUBLIC_METHODS)
                .registersResource("META-INF/demo.properties")
                .registersJdkProxy(Runnable.class, Serializable.class);

        assertThatThrownBy(() -> assertThatHints(new Registrar()).registersResource("META-INF/missing.properties"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("META-INF/missing.properties");
        assertThatThrownBy(() -> assertThatHints(new Registrar()).registersReflection(Integer.class))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("java.lang.Integer");
        assertThatThrownBy(() -> assertThatHints(new Registrar())
                .registersReflection(String.class,
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("java.lang.String")
                .hasMessageContaining("INVOKE_DECLARED_CONSTRUCTORS");
        assertThatThrownBy(() -> assertThatHints(new Registrar()).registersJdkProxy(Comparable.class))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("java.lang.Comparable");
    }

    @Test
    void hintsCanBeAssertedDirectly() {
        RuntimeHints hints = new RuntimeHints();
        new Registrar().registerHints(hints, getClass().getClassLoader());

        assertThatHints(hints).registersReflection(String.class);
    }
}
