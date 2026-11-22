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
package io.americanexpress.synapse.data.internal.store;

import java.lang.reflect.Method;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.type.MethodMetadata;

/**
 * Reads the factory method of a bean (such as its {@code @Bean} method) from its definition, without creating the
 * bean, the same way on the JVM and in AOT mode. A definition that AOT processing generates is a plain
 * {@link RootBeanDefinition}: it carries no factory method metadata and no factory method name, and only its instance
 * supplier knows the method ({@link RootBeanDefinition#getResolvedFactoryMethod()}, as Spring reads it). Public only
 * so that the data modules share it; internal.
 */
public final class FactoryMethods {

    private FactoryMethods() {
    }

    /**
     * Returns the annotations on a bean's factory method: from the definition's metadata, else from the method its
     * definition resolves or its instance supplier names.
     *
     * @param definition a bean definition
     * @return the factory method's annotations, or {@code null} when the definition names no factory method
     */
    public static @Nullable MergedAnnotations annotations(BeanDefinition definition) {
        if (definition instanceof AnnotatedBeanDefinition annotated) {
            MethodMetadata metadata = annotated.getFactoryMethodMetadata();
            if (metadata != null) {
                return metadata.getAnnotations();
            }
        }
        Method method = resolved(definition);
        return method == null ? null : MergedAnnotations.from(method);
    }

    /**
     * Returns the name of a bean's factory method: the definition's, else that of the method its instance supplier
     * names.
     *
     * @param definition a bean definition
     * @return the name, or {@code null} when the definition names no factory method
     */
    public static @Nullable String name(BeanDefinition definition) {
        String name = definition.getFactoryMethodName();
        if (name != null) {
            return name;
        }
        Method method = resolved(definition);
        return method == null ? null : method.getName();
    }

    /**
     * Whether a bean is made by a factory bean's method, such as a store bean that Synapse registers and the
     * application didn't replace.
     *
     * @param beanFactory the bean factory
     * @param beanName the bean's name
     * @param factoryBeanName the factory bean's name
     * @param factoryMethodName the factory method's name
     * @return {@code true} when the bean's definition names that factory bean and method
     */
    public static boolean isMadeBy(ConfigurableListableBeanFactory beanFactory, String beanName,
            String factoryBeanName, String factoryMethodName) {
        if (!beanFactory.containsBeanDefinition(beanName)) {
            return false;
        }
        BeanDefinition definition = beanFactory.getMergedBeanDefinition(beanName);
        return factoryBeanName.equals(definition.getFactoryBeanName())
                && factoryMethodName.equals(name(definition));
    }

    private static @Nullable Method resolved(BeanDefinition definition) {
        return definition instanceof RootBeanDefinition root ? root.getResolvedFactoryMethod() : null;
    }
}
