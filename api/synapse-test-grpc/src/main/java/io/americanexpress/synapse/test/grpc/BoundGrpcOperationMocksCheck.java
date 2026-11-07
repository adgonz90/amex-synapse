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
package io.americanexpress.synapse.test.grpc;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import io.americanexpress.synapse.api.grpc.GrpcBindings;
import io.americanexpress.synapse.core.operation.ServiceOperation;

/**
 * Fails a {@link SynapseGrpcTest} before any bean is created when an operation that the bindings use has no bean.
 * The slice leaves operations out, so the test declares a {@code @MockitoBean} (or a {@code @TestBean}) for each one;
 * without this check the test would fail later with Spring's unsatisfied-dependency error for the first missing
 * operation only.
 *
 * <p>
 * It reads the parameters of each {@code @Bean} method that returns {@link GrpcBindings}, and names every
 * {@link ServiceOperation} parameter type that has no bean, grouped by the method. Bindings registered another way
 * (an instance or a supplier) are not checked, and neither are the operations {@code .bindAllByName()} looks up: an
 * unbound method fails when the bindings are compiled, with the method named. It runs after spring-test's
 * {@code BeanOverrideBeanFactoryPostProcessor} (ordered {@code Ordered.LOWEST_PRECEDENCE - 10}), which registers the
 * test doubles.
 */
public final class BoundGrpcOperationMocksCheck implements BeanFactoryPostProcessor, Ordered {

    private static final String MESSAGE = "@SynapseGrpcTest: declare a @MockitoBean for each operation the bindings "
            + "use; missing: ";

    /**
     * Checks that every operation the bindings use has a bean.
     *
     * @param beanFactory the bean factory
     * @throws IllegalStateException naming each missing operation and the {@code @Bean} method that uses it
     */
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        List<String> missing = new ArrayList<>();
        for (String name : beanFactory.getBeanNamesForType(GrpcBindings.class, true, false)) {
            if (!beanFactory.containsBeanDefinition(name)) {
                continue;
            }
            BeanDefinition definition = beanFactory.getMergedBeanDefinition(name);
            String factoryMethod = definition.getFactoryMethodName();
            if (factoryMethod == null) {
                continue;
            }
            Class<?> factoryClass = factoryClass(beanFactory, definition);
            Set<String> operations = missingOperations(beanFactory, factoryClass, factoryMethod);
            if (!operations.isEmpty()) {
                missing
                        .add(String.join(", ", operations) + " (bound by " + factoryClass.getSimpleName() + "."
                                + factoryMethod + ")");
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(MESSAGE + String.join("; ", missing));
        }
    }

    /**
     * Runs last, after spring-test's bean overrides.
     *
     * @return {@link Ordered#LOWEST_PRECEDENCE}
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /**
     * Returns the class that declares the factory method: the factory bean's class for an instance {@code @Bean}
     * method, the bean class for a static one. A proxied {@code @Configuration} class is reported by its own name.
     */
    private static Class<?> factoryClass(ConfigurableListableBeanFactory beanFactory, BeanDefinition definition) {
        String factoryBean = definition.getFactoryBeanName();
        Class<?> type = (factoryBean != null)
                ? Objects.requireNonNull(beanFactory.getType(factoryBean, false))
                : ClassUtils
                        .resolveClassName(Objects.requireNonNull(definition.getBeanClassName()),
                                beanFactory.getBeanClassLoader());
        return ClassUtils.getUserClass(type);
    }

    private static Set<String> missingOperations(ConfigurableListableBeanFactory beanFactory, Class<?> factoryClass,
            String factoryMethod) {
        Set<String> missing = new LinkedHashSet<>();
        Method[] methods = ReflectionUtils
                .getUniqueDeclaredMethods(factoryClass,
                        method -> method.getName().equals(factoryMethod)
                                && GrpcBindings.class.isAssignableFrom(method.getReturnType()));
        for (Method method : methods) {
            for (Class<?> parameter : method.getParameterTypes()) {
                if (ServiceOperation.class.isAssignableFrom(parameter)
                        && beanFactory.getBeanNamesForType(parameter, true, false).length == 0) {
                    missing.add(parameter.getSimpleName());
                }
            }
        }
        return missing;
    }
}
