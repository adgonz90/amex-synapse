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
package io.americanexpress.synapse.test.data;

import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.TestContextAnnotationUtils;
import org.springframework.util.ClassUtils;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.data.SynapseDataStore;
import io.americanexpress.synapse.data.internal.store.StoreDefinition;
import io.americanexpress.synapse.data.internal.store.StoreNames;
import io.americanexpress.synapse.data.internal.store.StoreRegistrar;

/**
 * Checks that the store a {@link SynapseDataTest} names is declared. Without the check, a misspelt store would start
 * an empty slice and each test would fail on a missing transaction manager. It is registered in
 * {@code META-INF/spring.factories} and adds nothing to a test without {@link SynapseDataTest}.
 * <p>
 * Once every bean definition is registered, the context fails with a {@link SynapseConfigurationException} when the
 * name is not a valid store name, or when no {@code @SynapseDataStore} with that name was registered. The second
 * message lists the stores already registered in the bean factory and those declared in the packages of the test's
 * configuration classes, and names the packages it scanned.
 */
public final class SynapseDataTestContextCustomizerFactory implements ContextCustomizerFactory {

    @Override
    public @Nullable ContextCustomizer createContextCustomizer(Class<?> testClass,
            List<ContextConfigurationAttributes> configAttributes) {
        SynapseDataTest test = TestContextAnnotationUtils.findMergedAnnotation(testClass, SynapseDataTest.class);
        return test == null ? null : new StoreCheckCustomizer(test.value());
    }

    /** Adds the check for one store. Equal for the same store, so it never splits the context cache. */
    record StoreCheckCustomizer(String store) implements ContextCustomizer {

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
            context.addBeanFactoryPostProcessor(new StoreCheck(store, mergedConfig.getClasses()));
        }
    }

    /**
     * Fails when the store is not declared. A post-processor added to the context runs its
     * {@code postProcessBeanFactory} after every registry post-processor, so the stores are registered by then.
     */
    static final class StoreCheck implements BeanFactoryPostProcessor {

        private final String store;

        private final Class<?>[] configurations;

        StoreCheck(String store, Class<?>[] configurations) {
            this.store = store;
            this.configurations = configurations.clone();
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            if (!StoreNames.isValid(store)) {
                throw new SynapseConfigurationException(
                        "'" + store + "' is not a valid store name (@SynapseDataTest(\"" + store + "\")).",
                        "Use lowercase letters, digits and dashes, starting with a letter, such as books or "
                                + "legacy-orders.");
            }
            if (beanFactory.containsBeanDefinition(StoreNames.beanPrefix(store) + StoreDefinition.BEAN_SUFFIX)) {
                return;
            }
            SortedSet<String> packages = new TreeSet<>();
            for (Class<?> configuration : configurations) {
                packages.add(ClassUtils.getPackageName(configuration));
            }
            SortedSet<String> declared = registeredStores(beanFactory);
            declared.addAll(scannedStores(packages, beanFactory.getBeanClassLoader()));
            throw new SynapseConfigurationException(
                    "@SynapseDataTest(\"" + store + "\") names a store that no @SynapseDataStore(\"" + store
                            + "\") declares. Declared stores: " + listed(declared) + ". Scanned packages: "
                            + listed(packages) + ".",
                    declared.isEmpty()
                            ? "Declare the store with @SynapseDataStore in the application's packages."
                            : "Name one of the declared stores, such as @SynapseDataTest(\"" + declared.first()
                                    + "\").");
        }

        private static String listed(SortedSet<String> names) {
            return names.isEmpty() ? "none" : String.join(", ", names);
        }

        /** Returns the stores already registered, whichever package declared them. */
        private static SortedSet<String> registeredStores(ConfigurableListableBeanFactory beanFactory) {
            SortedSet<String> registered = new TreeSet<>();
            for (String beanName : beanFactory.getBeanDefinitionNames()) {
                StoreDefinition definition = StoreRegistrar.storeOf(beanFactory.getBeanDefinition(beanName));
                if (definition != null) {
                    registered.add(definition.name());
                }
            }
            return registered;
        }

        /** Scans the packages of the test's configuration classes for {@code @SynapseDataStore} classes. */
        private static SortedSet<String> scannedStores(SortedSet<String> packages, @Nullable ClassLoader classLoader) {
            ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(
                    false);
            scanner.addIncludeFilter(new AnnotationTypeFilter(SynapseDataStore.class));
            scanner.setResourceLoader(new DefaultResourceLoader(classLoader));
            SortedSet<String> declared = new TreeSet<>();
            for (String scanned : packages) {
                for (BeanDefinition candidate : scanner.findCandidateComponents(scanned)) {
                    if (candidate instanceof AnnotatedBeanDefinition annotated) {
                        Map<String, @Nullable Object> attributes = annotated
                                .getMetadata()
                                .getAnnotationAttributes(SynapseDataStore.class.getName());
                        if (attributes != null && attributes.get("value") instanceof String name) {
                            declared.add(name);
                        }
                    }
                }
            }
            return declared;
        }
    }
}
