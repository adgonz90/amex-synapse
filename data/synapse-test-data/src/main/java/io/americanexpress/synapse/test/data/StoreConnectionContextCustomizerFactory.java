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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.TestContextAnnotationUtils;
import org.springframework.util.ReflectionUtils;
import org.testcontainers.containers.JdbcDatabaseContainer;

import io.americanexpress.synapse.data.internal.store.StoreNames;

/**
 * Registers the connection details of each {@link StoreConnection} field of a test class, its superclasses and its
 * enclosing classes. It is registered in {@code META-INF/spring.factories}, so it runs for every Spring test, and adds
 * nothing to a test without such a field.
 * <p>
 * Each container is started if it isn't running, and its details are registered as the bean
 * {@code <prefix>StoreConnectionDetails}: a {@link JdbcConnectionDetails} qualified with the store name and not a
 * default candidate, so it reaches the store it names and nothing that injects connection details by type. Test
 * classes whose fields hold the same containers for the same stores share a cached context.
 */
public final class StoreConnectionContextCustomizerFactory implements ContextCustomizerFactory {

    /** The suffix of the connection details' bean name. */
    static final String BEAN_SUFFIX = "StoreConnectionDetails";

    @Override
    public @Nullable ContextCustomizer createContextCustomizer(Class<?> testClass,
            List<ContextConfigurationAttributes> configAttributes) {
        Map<String, Field> fields = new LinkedHashMap<>();
        Map<String, JdbcDatabaseContainer<?>> containers = new LinkedHashMap<>();
        collect(testClass, fields, containers);
        return containers.isEmpty() ? null : new StoreConnectionContextCustomizer(containers);
    }

    private static void collect(@Nullable Class<?> candidate, Map<String, Field> fields,
            Map<String, JdbcDatabaseContainer<?>> containers) {
        if (candidate == null || candidate == Object.class) {
            return;
        }
        ReflectionUtils.doWithLocalFields(candidate, field -> {
            StoreConnection connection = field.getAnnotation(StoreConnection.class);
            if (connection != null && !fields.containsValue(field)) {
                String store = connection.value();
                JdbcDatabaseContainer<?> container = container(field, store);
                Field other = fields.putIfAbsent(store, field);
                if (other != null) {
                    throw new IllegalStateException("The store '" + store + "' has more than one @StoreConnection: "
                            + describe(other) + " and " + describe(field) + ".");
                }
                containers.put(store, container);
            }
        });
        if (TestContextAnnotationUtils.searchEnclosingClass(candidate)) {
            collect(candidate.getEnclosingClass(), fields, containers);
        }
        collect(candidate.getSuperclass(), fields, containers);
    }

    private static JdbcDatabaseContainer<?> container(Field field, String store) {
        if (!Modifier.isStatic(field.getModifiers())) {
            throw new IllegalStateException("@StoreConnection " + describe(field) + " must be static.");
        }
        if (!StoreNames.isValid(store)) {
            throw new IllegalStateException(
                    "@StoreConnection " + describe(field) + " names '" + store + "', which is not a valid store name.");
        }
        ReflectionUtils.makeAccessible(field);
        if (!(ReflectionUtils.getField(field, null) instanceof JdbcDatabaseContainer<?> container)) {
            throw new IllegalStateException("@StoreConnection " + describe(field) + " must be a "
                    + JdbcDatabaseContainer.class.getName() + ".");
        }
        return container;
    }

    private static String describe(Field field) {
        return "field '" + field.getName() + "' in " + field.getDeclaringClass().getName();
    }

    /**
     * Starts the containers and registers their connection details. Equal when it connects the same stores to the
     * same containers (containers are equal only to themselves), which makes it part of the context cache key.
     */
    static final class StoreConnectionContextCustomizer implements ContextCustomizer {

        private final Map<String, JdbcDatabaseContainer<?>> containers;

        StoreConnectionContextCustomizer(Map<String, JdbcDatabaseContainer<?>> containers) {
            this.containers = Map.copyOf(containers);
        }

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
            if (!(context.getBeanFactory() instanceof BeanDefinitionRegistry registry)) {
                throw new IllegalStateException("@StoreConnection needs a context whose bean factory is a "
                        + BeanDefinitionRegistry.class.getName() + ".");
            }
            containers.forEach((store, container) -> {
                if (!container.isRunning()) {
                    container.start();
                }
                RootBeanDefinition definition = new RootBeanDefinition(JdbcConnectionDetails.class,
                        () -> new ContainerConnectionDetails(store, container));
                definition.setDefaultCandidate(false);
                definition.addQualifier(new AutowireCandidateQualifier(Qualifier.class, store));
                registry.registerBeanDefinition(StoreNames.beanPrefix(store) + BEAN_SUFFIX, definition);
            });
        }

        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof StoreConnectionContextCustomizer customizer
                    && containers.equals(customizer.containers);
        }

        @Override
        public int hashCode() {
            return containers.hashCode();
        }
    }

    /**
     * The connection details of a container's database, read from the container on each call. Its string form names
     * the store only, never the URL or a credential.
     */
    static final class ContainerConnectionDetails implements JdbcConnectionDetails {

        private final String store;

        private final JdbcDatabaseContainer<?> container;

        ContainerConnectionDetails(String store, JdbcDatabaseContainer<?> container) {
            this.store = store;
            this.container = container;
        }

        @Override
        public String getUsername() {
            return container.getUsername();
        }

        @Override
        public String getPassword() {
            return container.getPassword();
        }

        @Override
        public String getJdbcUrl() {
            return container.getJdbcUrl();
        }

        @Override
        public String getDriverClassName() {
            return container.getDriverClassName();
        }

        @Override
        public String toString() {
            return "Connection details of the store '" + store + "' from @StoreConnection";
        }
    }
}
