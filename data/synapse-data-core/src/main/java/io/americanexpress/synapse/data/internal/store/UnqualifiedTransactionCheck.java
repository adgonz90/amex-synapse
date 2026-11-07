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
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.RepositoryDefinition;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.TransactionManagementConfigurer;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import io.americanexpress.synapse.core.SynapseConfigurationException;

/**
 * Fails startup when the service has two or more stores, none of them primary, and a bean has an unqualified
 * {@code @Transactional}: Spring couldn't choose its transaction manager, and the first call would fail. It doesn't
 * fail where Spring itself resolves one: when a {@code TransactionManager} is primary (a primary store's, including one
 * its store class replaces, or the application's own) or the application defines a
 * {@code TransactionManagementConfigurer} (read from the bean definitions, without creating them), and for the methods
 * of a class whose type-level {@code @Qualifier} names exactly one transaction manager (by bean name or qualifier, such
 * as a store name), which Spring (6.2 and later) then uses for them.
 *
 * <p>
 * Once the non-lazy singletons exist, it reads every bean's user class as Spring's transaction advisor does: with the
 * context's {@link TransactionAttributeSource} when the context has exactly one and it already exists, otherwise with
 * an {@link AnnotationTransactionAttributeSource} that, like {@code @EnableTransactionManagement}'s, accepts non-public
 * methods. Both read Spring's {@code @Transactional} and, when it is on the class path,
 * {@code jakarta.transaction.Transactional}, on methods, classes and interfaces. An attribute without a qualifier is
 * unqualified. Private and static methods are left out, since no proxy runs them in a transaction. An existing bean's
 * class is read behind its proxies; a bean that doesn't exist yet (lazy, prototype or scoped) is read from the type the
 * bean factory predicts, and is never created. For a lazy or prototype {@code @Bean} method, that is the method's
 * declared return type: when it is an interface, a {@code @Transactional} only on the implementing class is not seen.
 *
 * <p>
 * It skips Spring's own classes ({@code org.springframework.*}, such as {@code SimpleJpaRepository}) and Spring Data
 * repositories: a type that extends {@link Repository} or carries {@link RepositoryDefinition}, which is the type
 * Spring Data's repository factory beans give their beans. Spring Data runs a repository's transactions on the
 * transaction manager of the store that registered it. A {@code TransactionTemplate} can't be checked: it names its
 * transaction manager in code.
 */
public final class UnqualifiedTransactionCheck implements SmartInitializingSingleton {

    /** The check's bean name. */
    public static final String BEAN_NAME = "synapseUnqualifiedTransactionCheck";

    /** The most methods the failure lists. */
    static final int LISTED = 5;

    private static final String SPRING_PACKAGE = "org.springframework.";

    private final ConfigurableListableBeanFactory beanFactory;

    /**
     * Creates the check.
     *
     * @param beanFactory the bean factory holding the store definitions and the beans to check
     */
    public UnqualifiedTransactionCheck(ConfigurableListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    /**
     * Runs the check.
     *
     * @throws SynapseConfigurationException when there are several stores, none is primary, and a bean has an
     *         unqualified {@code @Transactional}
     */
    @Override
    public void afterSingletonsInstantiated() {
        int stores = 0;
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            if (StoreRegistrar.storeOf(beanFactory.getBeanDefinition(beanName)) != null) {
                stores++;
            }
        }
        if (stores < 2 || hasDefaultTransactionManager()) {
            return;
        }
        TransactionAttributeSource attributes = attributeSource();
        Set<Class<?>> seen = new HashSet<>();
        Set<String> unqualified = new LinkedHashSet<>();
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            Class<?> type = typeOf(beanName);
            if (type != null && seen.add(type) && !isSkipped(type)) {
                unqualified.addAll(unqualifiedMethods(type, attributes));
            }
        }
        if (!unqualified.isEmpty()) {
            throw new SynapseConfigurationException("There are " + stores + " stores and none is primary, so an "
                    + "unqualified @Transactional can't choose a transaction manager: " + list(unqualified) + ".",
                    "Name the store (@Transactional(\"<store>\")), or set synapse.data.stores.<name>.primary: true "
                            + "on one store.");
        }
    }

    /**
     * Whether Spring can resolve an unqualified {@code @Transactional} without a primary store: the application defines
     * a primary {@link TransactionManager}, or a {@link TransactionManagementConfigurer}. Only bean definitions are
     * read; no bean is created.
     */
    private boolean hasDefaultTransactionManager() {
        if (beanFactory.getBeanNamesForType(TransactionManagementConfigurer.class, true, false).length > 0) {
            return true;
        }
        for (String beanName : beanFactory.getBeanNamesForType(TransactionManager.class, true, false)) {
            if (beanFactory.containsBeanDefinition(beanName)
                    && beanFactory.getMergedBeanDefinition(beanName).isPrimary()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the context's transaction attribute source when it has exactly one and it already exists, otherwise one
     * like {@code @EnableTransactionManagement}'s.
     */
    private TransactionAttributeSource attributeSource() {
        String[] names = beanFactory.getBeanNamesForType(TransactionAttributeSource.class, false, false);
        if (names.length == 1 && beanFactory.containsSingleton(names[0])
                && beanFactory.getSingleton(names[0]) instanceof TransactionAttributeSource existing) {
            return existing;
        }
        return new AnnotationTransactionAttributeSource(false);
    }

    /**
     * Returns the user class of a bean without creating it: an existing bean's class behind its proxies, otherwise the
     * type the bean factory predicts (for a factory bean, the type of the object it makes).
     */
    private @Nullable Class<?> typeOf(String beanName) {
        if (beanFactory.getMergedBeanDefinition(beanName).isAbstract()) {
            return null;
        }
        if (beanFactory.containsSingleton(beanName) && !beanFactory.isFactoryBean(beanName)) {
            Object bean = beanFactory.getSingleton(beanName);
            if (bean != null) {
                return ClassUtils.getUserClass(AopProxyUtils.ultimateTargetClass(bean));
            }
        }
        Class<?> type = beanFactory.getType(beanName, false);
        return type == null ? null : ClassUtils.getUserClass(type);
    }

    /**
     * Whether the check skips a type: Spring's own classes, and Spring Data repositories, whose transactions use their
     * store's transaction manager.
     *
     * @param type a bean's user class
     * @return {@code true} when the type isn't checked
     */
    static boolean isSkipped(Class<?> type) {
        return type.getName().startsWith(SPRING_PACKAGE) || Repository.class.isAssignableFrom(type)
                || AnnotatedElementUtils.hasAnnotation(type, RepositoryDefinition.class);
    }

    /**
     * Whether a type-level qualifier names exactly one transaction manager. Spring (6.2 and later) then runs the
     * class's unqualified transactions on it; it ignores a qualifier that names none, and fails on one that names
     * several ({@code TransactionAspectSupport.determineTransactionManager}).
     */
    private boolean namesTransactionManager(@Nullable String qualifier) {
        if (qualifier == null || !StringUtils.hasText(qualifier)) {
            return false;
        }
        int matches = 0;
        for (String beanName : beanFactory.getBeanNamesForType(TransactionManager.class, true, false)) {
            if (BeanQualifiers.isQualifierMatch(beanFactory, qualifier, beanName)) {
                matches++;
            }
        }
        return matches == 1;
    }

    /**
     * Returns the type's methods that a proxy would run in a transaction without a qualifier, as
     * {@code Class#method}, sorted by method name; overloads are listed once.
     */
    private Set<String> unqualifiedMethods(Class<?> type, TransactionAttributeSource attributes) {
        Set<String> names = new TreeSet<>();
        if (!namesTransactionManager(BeanQualifiers.qualifier(type))
                && attributes.isCandidateClass(type)) {
            for (Method method : ReflectionUtils
                    .getUniqueDeclaredMethods(type, ReflectionUtils.USER_DECLARED_METHODS)) {
                int modifiers = method.getModifiers();
                if (!Modifier.isPrivate(modifiers) && !Modifier.isStatic(modifiers)) {
                    TransactionAttribute attribute = attributes.getTransactionAttribute(method, type);
                    if (attribute != null && !StringUtils.hasText(attribute.getQualifier())) {
                        names.add(method.getName());
                    }
                }
            }
        }
        Set<String> methods = new LinkedHashSet<>();
        names.forEach(name -> methods.add(type.getName() + "#" + name));
        return methods;
    }

    /** Lists the first {@value #LISTED} methods, then how many more there are. */
    private static String list(Set<String> methods) {
        List<String> listed = new ArrayList<>(methods).subList(0, Math.min(LISTED, methods.size()));
        int more = methods.size() - listed.size();
        String list = String.join(", ", listed);
        return more > 0 ? list + " and " + more + " more" : list;
    }
}
