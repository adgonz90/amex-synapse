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
package io.americanexpress.synapse.core.internal.pipeline;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.aop.framework.autoproxy.AutoProxyUtils;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.operation.BusinessOperation;
import io.americanexpress.synapse.core.operation.ServiceOperation;

/**
 * Prepares operation and business bean definitions for the pipeline before any bean is created. It marks each one to
 * be proxied by class ({@link AutoProxyUtils#PRESERVE_TARGET_CLASS_ATTRIBUTE}), so the bean can still be injected as
 * its concrete class, and fails startup for classes a class-based proxy cannot run the pipeline on: final classes,
 * records, lambdas, anonymous, hidden or synthetic classes, and classes whose {@code execute} method is final.
 *
 * <p>
 * A bean whose class is known only after it is created (a {@code @Bean} method declared to return the interface) is
 * checked again by the {@link OperationProxyVerifier} once it exists.
 *
 * <p>
 * It also marks the test doubles that Spring's test bean overrides ({@code @MockitoBean}, {@code @TestBean}) put in
 * place of an operation or business bean, so that the verifier lets them skip the pipeline (see
 * {@link #TEST_DOUBLE_ATTRIBUTE}).
 */
public final class OperationBeanDefinitionChecker implements BeanFactoryPostProcessor {

    /**
     * Set to {@code TRUE} on the definition of an operation or business bean that a Spring test bean override replaced
     * with a test double. Such a double is registered as an instance, so it is never proxied, and it is meant to skip
     * the pipeline.
     */
    static final String TEST_DOUBLE_ATTRIBUTE = OperationBeanDefinitionChecker.class.getName() + ".testDouble";

    /**
     * The singleton that spring-test's {@code BeanOverrideContextCustomizer} registers when a test declares bean
     * overrides. Matched by name so that Synapse does not depend on spring-test.
     */
    static final String BEAN_OVERRIDE_REGISTRY = "org.springframework.test.context.bean.override."
            + "internalBeanOverrideRegistry";

    private static final Method SERVICE_EXECUTE = Objects
            .requireNonNull(ReflectionUtils.findMethod(ServiceOperation.class, "execute", Object.class));

    private static final Method BUSINESS_EXECUTE = Objects
            .requireNonNull(ReflectionUtils.findMethod(BusinessOperation.class, "execute", Object.class));

    private static final String NAMED_CLASS = "Declare it as a named, non-final top-level or static nested class "
            + "(not a record, lambda or anonymous class) so that Synapse can wrap it in the invocation pipeline.";

    /**
     * Marks and checks the operation and business bean definitions.
     *
     * <p>
     * This post-processor is not ordered, so it runs after the ordered ones, including spring-test's
     * {@code BeanOverrideBeanFactoryPostProcessor}. That one replaces an overridden bean by registering the test double
     * as a singleton under a bean definition (the bean's own, or one it adds), before any application bean is created.
     * So when bean overrides are present, an operation or business singleton that already exists here, under a bean
     * definition, is such a double.
     *
     * @param beanFactory the bean factory
     */
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        boolean beanOverrides = beanFactory.containsSingleton(BEAN_OVERRIDE_REGISTRY);
        Set<String> names = new LinkedHashSet<>();
        names.addAll(List.of(beanFactory.getBeanNamesForType(ServiceOperation.class, true, false)));
        names.addAll(List.of(beanFactory.getBeanNamesForType(BusinessOperation.class, true, false)));
        for (String name : names) {
            if (!beanFactory.containsBeanDefinition(name)) {
                continue;
            }
            BeanDefinition definition = beanFactory.getBeanDefinition(name);
            definition.setAttribute(AutoProxyUtils.PRESERVE_TARGET_CLASS_ATTRIBUTE, Boolean.TRUE);
            if (beanOverrides && beanFactory.containsSingleton(name)) {
                definition.setAttribute(TEST_DOUBLE_ATTRIBUTE, Boolean.TRUE);
            }
            Class<?> type = beanFactory.getType(name, false);
            if (type != null && !type.isInterface()) {
                String beanName = ScopedProxyUtils.isScopedTarget(name)
                        ? ScopedProxyUtils.getOriginalBeanName(name)
                        : name;
                checkClass(beanName, ClassUtils.getUserClass(type));
            }
        }
    }

    /**
     * Fails if a class-based proxy of the class could not run the pipeline.
     *
     * @param beanName the bean name, for the message
     * @param type the bean's class
     * @throws SynapseConfigurationException if the class is a lambda, anonymous, hidden, synthetic, a record or final,
     *     or its {@code execute} method is final
     */
    static void checkClass(String beanName, Class<?> type) {
        check(describe(beanName, type), type);
    }

    /**
     * Fails if a class-based proxy of the class could not run the pipeline, for a class met on its first call
     * (a lazy bean whose class was not known at startup), when the bean name is not at hand.
     *
     * @param type the operation or business class
     * @throws SynapseConfigurationException as {@link #checkClass(String, Class)} does
     */
    static void checkClass(Class<?> type) {
        String kind = ServiceOperation.class.isAssignableFrom(type) ? "Operation class" : "Business operation class";
        check(kind + " " + type.getName(), type);
    }

    private static void check(String bean, Class<?> type) {
        if (ClassUtils.isLambdaClass(type)) {
            throw new SynapseConfigurationException(bean + " is a lambda, which Synapse cannot proxy by class.",
                    NAMED_CLASS);
        }
        if (type.isAnonymousClass()) {
            throw new SynapseConfigurationException(
                    bean + " is an anonymous class, which has no name to derive the operation name from.",
                    NAMED_CLASS);
        }
        if (type.isHidden() || type.isSynthetic()) {
            throw new SynapseConfigurationException(
                    bean + " is a hidden or synthetic class, which Synapse cannot proxy by class.", NAMED_CLASS);
        }
        if (type.isRecord()) {
            throw new SynapseConfigurationException(
                    bean + " is a record; records are final, so Synapse cannot proxy them by class.", NAMED_CLASS);
        }
        if (Modifier.isFinal(type.getModifiers())) {
            throw new SynapseConfigurationException(
                    bean + " is a final class, so Synapse cannot proxy it by class.",
                    "Remove the final modifier from " + type.getName() + ".");
        }
        checkExecute(bean, type, SERVICE_EXECUTE);
        checkExecute(bean, type, BUSINESS_EXECUTE);
    }

    private static void checkExecute(String bean, Class<?> type, Method contract) {
        if (!contract.getDeclaringClass().isAssignableFrom(type)) {
            return;
        }
        Method specific = ClassUtils.getMostSpecificMethod(contract, type);
        Method implementation = BridgeMethodResolver.findBridgedMethod(specific);
        if (Modifier.isFinal(implementation.getModifiers()) || Modifier.isFinal(specific.getModifiers())) {
            throw new SynapseConfigurationException(
                    bean + " has a final execute method (declared in "
                            + implementation.getDeclaringClass().getName()
                            + "), which a proxy cannot override, so calls would skip the pipeline.",
                    "Remove the final modifier from the execute method.");
        }
    }

    static String describe(String beanName, Class<?> type) {
        String kind = ServiceOperation.class.isAssignableFrom(type) ? "Operation bean" : "Business operation bean";
        return kind + " '" + beanName + "' (" + type.getName() + ")";
    }
}
