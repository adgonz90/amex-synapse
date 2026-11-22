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

import java.util.Arrays;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.util.ClassUtils;

/**
 * Reads beans' qualifiers from their bean definitions, without creating any bean, the one way every data module does.
 * Public only so that the data modules share it; internal.
 */
public final class BeanQualifiers {

    /** The qualifier of a bean qualified with a type other than {@code @Qualifier}: never a store name. */
    public static final String ANOTHER_QUALIFIER = "<another qualifier type>";

    private BeanQualifiers() {
    }

    /**
     * Returns a bean's qualifier, read as Spring's {@code QualifierAnnotationAutowireCandidateResolver} reads it,
     * without creating the bean: a qualifier added to the definition (such as a store bean's), else the
     * {@code @Qualifier} on its factory method, else the one on its class (as written on a scanned class, or the type
     * the bean factory predicts, such as a factory method's return type). A qualifier of a type other than
     * {@code @Qualifier} is {@link #ANOTHER_QUALIFIER}, which is never a store name. A bean without a definition (a
     * registered singleton) has none.
     *
     * @param beanFactory the bean factory
     * @param beanName the bean's name
     * @return the qualifier, or {@code null} when the bean has none
     */
    public static @Nullable String qualifier(ConfigurableListableBeanFactory beanFactory, String beanName) {
        if (!beanFactory.containsBeanDefinition(beanName)) {
            return null;
        }
        BeanDefinition definition = beanFactory.getBeanDefinition(beanName);
        String declared = declaredQualifier(definition);
        if (declared != null) {
            return declared;
        }
        Class<?> type = beanFactory.getType(beanName, false);
        return type == null ? null : qualifier(type);
    }

    /**
     * Returns the qualifier a bean definition declares itself, without its type: one added to the definition, else
     * the {@code @Qualifier} on its factory method (read through {@link FactoryMethods}, so also from a definition AOT
     * processing generated), or on its class as the definition's metadata records it.
     *
     * @param definition a bean definition
     * @return the qualifier, {@link #ANOTHER_QUALIFIER}, or {@code null} when the definition declares none
     */
    public static @Nullable String declaredQualifier(BeanDefinition definition) {
        if (definition instanceof AbstractBeanDefinition added && !added.getQualifiers().isEmpty()) {
            AutowireCandidateQualifier qualifier = added.getQualifier(Qualifier.class.getName());
            Object value = qualifier == null ? null : qualifier.getAttribute(AutowireCandidateQualifier.VALUE_KEY);
            return value != null ? value.toString() : ANOTHER_QUALIFIER;
        }
        MergedAnnotations factoryMethod = FactoryMethods.annotations(definition);
        MergedAnnotation<Qualifier> qualifier;
        if (factoryMethod != null) {
            qualifier = factoryMethod.get(Qualifier.class);
        } else if (definition instanceof AnnotatedBeanDefinition annotated) {
            qualifier = annotated.getMetadata().getAnnotations().get(Qualifier.class);
        } else {
            return null;
        }
        return qualifier.isPresent() ? qualifier.getString("value") : null;
    }

    /**
     * Returns the {@code @Qualifier} on a class, behind its CGLIB subclass, present, meta-present or inherited.
     *
     * @param type a class
     * @return the qualifier, or {@code null} when the class has none
     */
    public static @Nullable String qualifier(Class<?> type) {
        MergedAnnotation<Qualifier> qualifier = MergedAnnotations
                .from(ClassUtils.getUserClass(type), SearchStrategy.INHERITED_ANNOTATIONS)
                .get(Qualifier.class);
        return qualifier.isPresent() ? qualifier.getString("value") : null;
    }

    /**
     * Whether a qualifier names a bean: its name, one of its aliases, or its {@link #qualifier}.
     *
     * @param beanFactory the bean factory
     * @param qualifier the qualifier, such as a store name or a bean name
     * @param beanName the bean's name
     * @return {@code true} when the qualifier names the bean
     */
    public static boolean isQualifierMatch(ConfigurableListableBeanFactory beanFactory, String qualifier,
            String beanName) {
        return qualifier.equals(beanName) || Arrays.asList(beanFactory.getAliases(beanName)).contains(qualifier)
                || qualifier.equals(qualifier(beanFactory, beanName));
    }
}
