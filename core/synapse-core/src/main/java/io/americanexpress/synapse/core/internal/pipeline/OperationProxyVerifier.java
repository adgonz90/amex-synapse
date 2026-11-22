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

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.config.AopConfigUtils;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.scope.ScopedObject;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.util.ClassUtils;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.internal.binding.OperationTypes;
import io.americanexpress.synapse.core.operation.BusinessOperation;
import io.americanexpress.synapse.core.operation.OperationKind;
import io.americanexpress.synapse.core.operation.OperationNames;
import io.americanexpress.synapse.core.operation.ServiceOperation;

/**
 * Verifies, once the non-lazy singletons exist, that each operation and business bean runs through the pipeline.
 *
 * <ul>
 * <li>From the bean definitions, without creating any bean: every operation and business bean (singleton, lazy,
 * prototype or scoped) has a class that can be proxied, is exactly one kind of operation, and has a valid, unique
 * name; and Spring's auto-proxy creator is registered. A scoped bean is checked through its
 * {@code scopedTarget.<name>} definition and reported under its own name.</li>
 * <li>On the singletons that already exist (not factory beans or scoped proxies): the bean is a class-based proxy
 * carrying the Synapse advisor. Lazy beans are never created by this check. Test doubles that a Spring test bean
 * override ({@code @MockitoBean}, {@code @TestBean}) registered in place of a bean are exempt: they skip the pipeline
 * on purpose.</li>
 * </ul>
 *
 * It also logs one WARN listing the names under {@code synapse.operations} that match no operation bean, since their
 * settings would otherwise do nothing silently, and one for each prefix an API plugin contributes as an
 * {@link OperationSettingsPrefix} bean (such as {@code synapse.api.rest.operations}).
 */
public final class OperationProxyVerifier implements SmartInitializingSingleton {

    private static final Logger LOGGER = LoggerFactory.getLogger(OperationProxyVerifier.class);

    private static final String OPERATIONS = "synapse.operations";

    private final ConfigurableListableBeanFactory beanFactory;

    private final Environment environment;

    /**
     * Creates the verifier.
     *
     * @param beanFactory the bean factory holding the operation beans
     * @param environment the environment, scanned for {@code synapse.operations.<name>} keys and the keys under
     *     each {@link OperationSettingsPrefix} bean's prefix
     */
    public OperationProxyVerifier(ConfigurableListableBeanFactory beanFactory, Environment environment) {
        this.beanFactory = beanFactory;
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Map<String, String> beans = candidates();
        Map<String, String> operations = new HashMap<>();
        Map<String, String> businesses = new HashMap<>();
        Map<String, Object> existing = new LinkedHashMap<>();
        for (Map.Entry<String, String> bean : beans.entrySet()) {
            String beanName = bean.getKey();
            Object instance = existingInstance(beanName);
            if (instance != null) {
                existing.put(beanName, instance);
            }
            // An existing bean's own class (through any proxy, including the JDK proxy around a lambda) is the real
            // one; the definition's type is used only for beans that do not exist yet.
            Class<?> type = instance != null
                    ? AopProxyUtils.ultimateTargetClass(instance)
                    : beanFactory.getType(bean.getValue(), false);
            if (type != null && !type.isInterface()) {
                verifyType(beanName, ClassUtils.getUserClass(type), operations, businesses);
            }
        }
        if (!beans.isEmpty() && !beanFactory.containsBean(AopConfigUtils.AUTO_PROXY_CREATOR_BEAN_NAME)) {
            String beanName = beans.keySet().iterator().next();
            throw new SynapseConfigurationException(
                    "Bean '" + beanName + "' and the other operation and business beans would skip the Synapse "
                            + "invocation pipeline: Spring's auto-proxy creator is not registered "
                            + "(spring.aop.auto=false, or spring.aop.proxy-target-class=false without AspectJ).",
                    "Keep Spring's auto-proxying on: remove spring.aop.auto=false, and do not set "
                            + "spring.aop.proxy-target-class=false without AspectJ on the classpath.");
        }
        for (Map.Entry<String, Object> bean : existing.entrySet()) {
            if (!isTestDouble(bean.getKey())) {
                verifyProxied(bean.getKey(), bean.getValue());
            }
        }
        warnAboutUnknownOperations(operations.keySet());
    }

    /**
     * Returns the operation and business beans, by the name users see, with the name of the definition that holds
     * their type: the {@code scopedTarget.<name>} definition for a scoped bean, the bean's own otherwise.
     */
    private Map<String, String> candidates() {
        Set<String> names = new LinkedHashSet<>();
        names.addAll(List.of(beanFactory.getBeanNamesForType(ServiceOperation.class, true, false)));
        names.addAll(List.of(beanFactory.getBeanNamesForType(BusinessOperation.class, true, false)));
        Map<String, String> beans = new LinkedHashMap<>();
        for (String name : names) {
            if (ScopedProxyUtils.isScopedTarget(name)) {
                beans.put(ScopedProxyUtils.getOriginalBeanName(name), name);
            } else {
                String target = ScopedProxyUtils.getTargetBeanName(name);
                beans.putIfAbsent(name, beanFactory.containsBeanDefinition(target) ? target : name);
            }
        }
        return beans;
    }

    /** Returns the bean if it already exists and is not a factory bean or a scoped proxy, without creating it. */
    private @Nullable Object existingInstance(String beanName) {
        if (!beanFactory.containsSingleton(beanName) || beanFactory.isFactoryBean(beanName)) {
            return null;
        }
        Object bean = beanFactory.getBean(beanName);
        return bean instanceof ScopedObject ? null : bean;
    }

    /**
     * Whether the bean is a test double that a Spring test bean override registered in place of the bean, as marked by
     * the {@link OperationBeanDefinitionChecker}. Such a double skips the pipeline on purpose.
     */
    private boolean isTestDouble(String beanName) {
        return beanFactory.containsBeanDefinition(beanName) && Boolean.TRUE
                .equals(beanFactory
                        .getBeanDefinition(beanName)
                        .getAttribute(OperationBeanDefinitionChecker.TEST_DOUBLE_ATTRIBUTE));
    }

    private static void verifyType(String beanName, Class<?> type, Map<String, String> operations,
            Map<String, String> businesses) {
        boolean service = ServiceOperation.class.isAssignableFrom(type);
        String described = OperationBeanDefinitionChecker.describe(beanName, type);
        if (service && BusinessOperation.class.isAssignableFrom(type)) {
            throw new SynapseConfigurationException(
                    described + " implements both ServiceOperation and BusinessOperation.",
                    "Implement QueryOperation or CommandOperation for an operation exposed to callers, or "
                            + "BusinessOperation for business logic, not both.");
        }
        OperationBeanDefinitionChecker.checkClass(beanName, type);
        if (service) {
            kindOf(described, type);
        }
        String name = nameOf(described, type);
        Map<String, String> names = service ? operations : businesses;
        String other = names.putIfAbsent(name, described);
        if (other != null) {
            // Names key metrics, spans and settings, so two beans with one name would merge silently.
            throw new SynapseConfigurationException(
                    described + " has the name '" + name + "', which " + lowerFirst(other) + " already has. "
                            + (service ? "Operation" : "Business") + " names must be unique.",
                    "Give one of them a different name with @OperationName, or rename its class.");
        }
    }

    private static String lowerFirst(String text) {
        return Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    private static void verifyProxied(String beanName, Object bean) {
        Class<?> type = OperationTypes.userClassOf(bean);
        OperationBeanDefinitionChecker.checkClass(beanName, type);
        Class<?> advisor = bean instanceof ServiceOperation<?, ?> ? OperationAdvisor.class : BusinessAdvisor.class;
        if (!AopUtils.isCglibProxy(bean) || !(bean instanceof Advised advised)
                || Arrays.stream(advised.getAdvisors()).noneMatch(advisor::isInstance)) {
            throw new SynapseConfigurationException(
                    OperationBeanDefinitionChecker.describe(beanName, type)
                            + " is not wrapped in the Synapse invocation pipeline: it is not a class-based proxy "
                            + "with the Synapse advisor, so its calls would skip validation, observation and error "
                            + "translation. It was registered as an instance, or created before Spring's "
                            + "auto-proxy creator could wrap it (for example as a dependency of a "
                            + "BeanPostProcessor).",
                    "Define the operation as a bean that Spring creates, and do not inject it into a "
                            + "BeanPostProcessor or BeanFactoryPostProcessor.");
        }
    }

    private static void kindOf(String described, Class<?> type) {
        try {
            OperationKind.of(type);
        } catch (SynapseConfigurationException invalid) {
            throw new SynapseConfigurationException(described + ": " + invalid.description(), invalid.action());
        }
    }

    private static String nameOf(String described, Class<?> type) {
        String name;
        try {
            name = OperationNames.nameOf(type);
        } catch (SynapseConfigurationException invalid) {
            throw new SynapseConfigurationException(described + ": " + invalid.description(), invalid.action());
        }
        if (!OperationNames.isValidName(name)) {
            throw new SynapseConfigurationException(
                    described + " has the invalid name '" + name + "', derived from its class name.",
                    "Name it with @OperationName, using lower-case words joined by single hyphens, such as "
                            + "'get-book'.");
        }
        return name;
    }

    private void warnAboutUnknownOperations(Set<String> known) {
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            return;
        }
        Set<String> prefixes = new LinkedHashSet<>();
        prefixes.add(OPERATIONS);
        beanFactory
                .getBeanProvider(OperationSettingsPrefix.class)
                .orderedStream()
                .forEach(contributed -> prefixes.add(contributed.prefix()));
        for (String prefix : prefixes) {
            Set<String> unknown = new TreeSet<>();
            for (PropertySource<?> source : configurable.getPropertySources()) {
                if (source instanceof EnumerablePropertySource<?> enumerable) {
                    for (String key : enumerable.getPropertyNames()) {
                        String name = operationName(prefix, key);
                        if (name != null && !name.isEmpty() && !known.contains(name)) {
                            unknown.add(name);
                        }
                    }
                }
            }
            if (!unknown.isEmpty()) {
                LOGGER
                        .warn("No operation bean has these names under {}, so their settings have no effect: {}. "
                                + "Check the operation names for typos.", prefix, unknown);
            }
        }
    }

    /**
     * Returns the operation name of a key under a prefix such as {@code synapse.operations}, written
     * {@code <prefix>.<name>...}, {@code <prefix>.[<name>]...} or {@code <prefix>[<name>]...}; {@code null} for any
     * other key.
     */
    static @Nullable String operationName(String prefix, String key) {
        if (!key.startsWith(prefix)) {
            return null;
        }
        String rest = key.substring(prefix.length());
        if (rest.startsWith(".")) {
            rest = rest.substring(1);
        } else if (!rest.startsWith("[")) {
            return null;
        }
        if (rest.startsWith("[")) {
            int end = rest.indexOf(']');
            return end < 0 ? rest.substring(1) : rest.substring(1, end);
        }
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '.' || c == '[') {
                end = i;
                break;
            }
        }
        return rest.substring(0, end);
    }
}
