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
package io.americanexpress.synapse.test.operation;

import java.io.IOException;
import java.net.URL;
import java.time.Clock;
import java.util.Collections;
import java.util.Enumeration;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Configuration;

import io.americanexpress.synapse.core.environment.SynapseEnvironment;
import io.americanexpress.synapse.test.time.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutoConfigureSynapseOperationTest {

    @Configuration(proxyBeanMethods = false)
    @AutoConfigureSynapseOperation
    static class SliceConfiguration {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SliceConfiguration.class);

    @Test
    void importsTransactionSupportWhenOnTheClasspath() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TransactionAutoConfiguration.class);
            assertThat(context.getBean(Clock.class)).isInstanceOf(MutableClock.class);
        });
    }

    /**
     * Boot decides whether an {@code optional:} entry is present with a {@code ClassPathResource} on the thread's
     * context class loader, and loads the class with the bean class loader. Both must hide the package for the test to
     * mean "this jar is not on the classpath".
     */
    @Test
    void startsWithoutTransactionSupportOnTheClasspath() {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader hiding = new HidingClassLoader(original, "org.springframework.boot.transaction.");
        Thread.currentThread().setContextClassLoader(hiding);
        try {
            runner.withClassLoader(hiding).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(SynapseEnvironment.class);
                assertThat(context.getBean(Clock.class)).isInstanceOf(MutableClock.class);
            });
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Test
    void theHidingClassLoaderHidesClassesAndClassFiles() throws IOException {
        ClassLoader hiding = new HidingClassLoader(getClass().getClassLoader(),
                "org.springframework.boot.transaction.");
        String name = TransactionAutoConfiguration.class.getName();
        String path = name.replace('.', '/') + ".class";

        assertThat(getClass().getClassLoader().getResource(path)).isNotNull();
        assertThat(hiding.getResource(path)).isNull();
        assertThat(hiding.getResources(path).hasMoreElements()).isFalse();
        assertThat(hiding.getResourceAsStream(path)).isNull();
        assertThatThrownBy(() -> hiding.loadClass(name)).isInstanceOf(ClassNotFoundException.class);
        assertThat(hiding.getResource("org/springframework/boot/autoconfigure/aop/AopAutoConfiguration.class"))
                .isNotNull();
    }

    private static final class HidingClassLoader extends ClassLoader {

        private final String hiddenPackage;

        HidingClassLoader(ClassLoader parent, String hiddenPackage) {
            super(parent);
            this.hiddenPackage = hiddenPackage;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(this.hiddenPackage)) {
                throw new ClassNotFoundException(name);
            }
            return super.loadClass(name, resolve);
        }

        @Override
        public @Nullable URL getResource(String name) {
            return hidden(name) ? null : super.getResource(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            return hidden(name) ? Collections.emptyEnumeration() : super.getResources(name);
        }

        private boolean hidden(String resource) {
            return resource.startsWith(this.hiddenPackage.replace('.', '/'));
        }
    }
}
