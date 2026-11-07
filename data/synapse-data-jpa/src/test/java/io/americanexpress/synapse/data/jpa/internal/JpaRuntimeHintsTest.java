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

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.core.io.support.SpringFactoriesLoader;

import static org.assertj.core.api.Assertions.assertThat;

class JpaRuntimeHintsTest {

    private static final String CALLBACKS = "io.americanexpress.synapse.data.jpa.EntityCallbacks";

    @Test
    void registersTheMappingResource() {
        assertThat(RuntimeHintsPredicates.resource().forResource("io/americanexpress/synapse/data/jpa/synapse-orm.xml"))
                .accepts(hints());
    }

    @Test
    void registersTheEntityCallbacksConstructorAndCallbacks() throws ClassNotFoundException {
        RuntimeHints hints = hints();
        Class<?> callbacks = Class.forName(CALLBACKS);

        assertThat(callbacks.getDeclaredConstructors()).hasSize(1);
        for (Constructor<?> constructor : callbacks.getDeclaredConstructors()) {
            assertThat(RuntimeHintsPredicates.reflection().onConstructorInvocation(constructor)).accepts(hints);
        }
        for (String name : new String[]{"prePersist", "preUpdate"}) {
            Method method = findMethod(callbacks, name);
            assertThat(RuntimeHintsPredicates.reflection().onMethodInvocation(method)).as(name).accepts(hints);
        }
    }

    @Test
    void isListedInAotFactories() {
        assertThat(SpringFactoriesLoader
                .forResourceLocation("META-INF/spring/aot.factories")
                .load(RuntimeHintsRegistrar.class)).anyMatch(JpaRuntimeHints.class::isInstance);
    }

    private static RuntimeHints hints() {
        RuntimeHints hints = new RuntimeHints();
        new JpaRuntimeHints().registerHints(hints, JpaRuntimeHintsTest.class.getClassLoader());
        return hints;
    }

    private static Method findMethod(Class<?> type, String name) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new AssertionError("No method " + name + " on " + type.getName());
    }
}
