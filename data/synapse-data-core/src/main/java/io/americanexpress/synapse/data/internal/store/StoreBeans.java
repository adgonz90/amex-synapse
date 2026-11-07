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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.Order;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.util.ClassUtils;

import io.americanexpress.synapse.core.SynapseConfigurationException;

/**
 * How the store factories reach a store's other beans, and the application's beans a store uses. Public only so that
 * the data modules share it; internal.
 */
public final class StoreBeans {

    private static final Logger logger = LoggerFactory.getLogger(StoreBeans.class);

    private StoreBeans() {
    }

    /**
     * Returns one of a store's beans, {@code <prefix><suffix>}, and records that the bean being built,
     * {@code <prefix><dependentSuffix>}, depends on it, so the context destroys the dependent first.
     *
     * @param <T> the bean's type
     * @param beanFactory the bean factory
     * @param store the store name
     * @param suffix the suffix of the bean to return, such as {@code DataSource}
     * @param type the type to return it as
     * @param dependentSuffix the suffix of the bean being built, such as {@code JdbcTemplate}
     * @return the bean
     */
    public static <T> T dependency(ConfigurableListableBeanFactory beanFactory, String store, String suffix,
            Class<T> type, String dependentSuffix) {
        String prefix = StoreNames.beanPrefix(store);
        T bean = beanFactory.getBean(prefix + suffix, type);
        beanFactory.registerDependentBean(prefix + suffix, prefix + dependentSuffix);
        return bean;
    }

    /**
     * Returns the application's bean of a type for a store. Every store's own bean of that type
     * ({@code <prefix><suffix>}) is not the application's. Among the others, those qualified with the store's name
     * apply to it, else the unqualified ones; a bean with another qualifier is never used for a store, and a DEBUG
     * line names it. The qualifiers are read with {@link BeanQualifiers#qualifier}, without creating any bean. Of the
     * beans that apply, the only one is used, or the primary one among several.
     *
     * @param <T> the bean's type
     * @param beanFactory the bean factory
     * @param store the store name
     * @param type the bean's type
     * @param suffix the suffix of a store's own bean of that type, such as {@code NamingStrategy}
     * @return the bean, or {@code null} when none applies
     * @throws SynapseConfigurationException when several apply, and not exactly one of them is primary
     */
    public static <T> @Nullable T applicationBean(ConfigurableListableBeanFactory beanFactory, String store,
            Class<T> type, String suffix) {
        String name = applicationBeanName(beanFactory, store, type, suffix);
        return name == null ? null : beanFactory.getBean(name, type);
    }

    /**
     * Returns the name of the application's bean of a type for a store, chosen as {@link #applicationBean} chooses
     * it, without creating any bean.
     *
     * @param beanFactory the bean factory
     * @param store the store name
     * @param type the bean's type
     * @param suffix the suffix of a store's own bean of that type
     * @return the bean name, or {@code null} when none applies
     * @throws SynapseConfigurationException when several apply, and not exactly one of them is primary
     */
    public static @Nullable String applicationBeanName(ConfigurableListableBeanFactory beanFactory, String store,
            Class<?> type, String suffix) {
        Map<String, Optional<String>> others = qualifiers(beanFactory, store, type, suffix);
        List<String> forStore = names(others, Optional.of(store));
        List<String> candidates = forStore.isEmpty() ? names(others, Optional.empty()) : forStore;
        if (candidates.isEmpty()) {
            return null;
        }
        List<String> chosen = candidates.size() == 1
                ? candidates
                : candidates.stream().filter(name -> isPrimary(beanFactory, name)).toList();
        if (chosen.size() != 1) {
            String own = StoreNames.beanPrefix(store) + suffix;
            throw new SynapseConfigurationException(
                    "The store '" + store + "' finds more than one " + type.getSimpleName() + " bean: "
                            + String.join(", ", candidates) + ".",
                    "Mark one of them @Primary, or define " + own + " in the store's @SynapseDataStore class.");
        }
        return chosen.get(0);
    }

    /**
     * Returns every application bean of a type that applies to a store, as Boot applies every bean of a customizer
     * type: the unqualified ones, then those qualified with the store's name, so that the more specific one, applied
     * later, wins on a key both set. Each group is in order ({@code @Order} on the bean's {@code @Bean} method, else
     * {@link Ordered}, {@code @Order} or {@code @Priority} on its class). Every
     * store's own bean of that type ({@code <prefix><suffix>}) is left out, and a bean with another qualifier is never
     * used (a DEBUG line names it).
     *
     * @param <T> the beans' type
     * @param beanFactory the bean factory
     * @param store the store name
     * @param type the beans' type
     * @param suffix the suffix of a store's own bean of that type, such as {@code HibernatePropertiesCustomizer}
     * @return the beans, in the order to apply them
     */
    public static <T> List<T> applicationBeans(ConfigurableListableBeanFactory beanFactory, String store,
            Class<T> type, String suffix) {
        Map<String, Optional<String>> others = qualifiers(beanFactory, store, type, suffix);
        List<T> beans = new ArrayList<>(ordered(beanFactory, names(others, Optional.empty()), type));
        beans.addAll(ordered(beanFactory, names(others, Optional.of(store)), type));
        return beans;
    }

    /** Returns the beans, created, sorted by their order; equal orders keep the names' order. */
    private static <T> List<T> ordered(ConfigurableListableBeanFactory beanFactory, List<String> names,
            Class<T> type) {
        List<Map.Entry<Integer, T>> beans = new ArrayList<>();
        for (String name : names) {
            T bean = beanFactory.getBean(name, type);
            beans.add(Map.entry(order(beanFactory, name, bean), bean));
        }
        beans.sort(Map.Entry.comparingByKey());
        return beans.stream().map(Map.Entry::getValue).toList();
    }

    /**
     * Returns a bean's order as Spring's ordered streams read it: {@code @Order} on its {@code @Bean} method (read
     * through {@link FactoryMethods}, also in AOT mode), else its {@link Ordered#getOrder()}, else {@code @Order} or
     * {@code @Priority} on its class, else the lowest precedence.
     */
    private static int order(ConfigurableListableBeanFactory beanFactory, String name, Object bean) {
        MergedAnnotations factoryMethod = beanFactory.containsBeanDefinition(name)
                ? FactoryMethods.annotations(beanFactory.getBeanDefinition(name))
                : null;
        if (factoryMethod != null) {
            MergedAnnotation<Order> order = factoryMethod.get(Order.class);
            if (order.isPresent()) {
                return order.getInt("value");
            }
        }
        if (bean instanceof Ordered ordered) {
            return ordered.getOrder();
        }
        Integer order = OrderUtils.getOrder(ClassUtils.getUserClass(bean));
        return order != null ? order : Ordered.LOWEST_PRECEDENCE;
    }

    /**
     * Returns the qualifier of each application bean of a type, without creating any: every store's own bean of that
     * type ({@code <prefix><suffix>}) is not the application's. A DEBUG line names each bean with another qualifier
     * than the store's name.
     */
    private static Map<String, Optional<String>> qualifiers(ConfigurableListableBeanFactory beanFactory, String store,
            Class<?> type, String suffix) {
        Set<String> excluded = new HashSet<>();
        for (String definition : beanFactory.getBeanNamesForType(StoreDefinition.class, false, false)) {
            excluded.add(beanFactory.getBean(definition, StoreDefinition.class).beanName(suffix));
        }
        Map<String, Optional<String>> others = new TreeMap<>();
        for (String name : beanFactory.getBeanNamesForType(type, true, false)) {
            if (!excluded.contains(name)) {
                others.put(name, Optional.ofNullable(BeanQualifiers.qualifier(beanFactory, name)));
            }
        }
        others.forEach((name, qualifier) -> {
            if (qualifier.isPresent() && !store.equals(qualifier.get()) && logger.isDebugEnabled()) {
                logger
                        .debug("The store '{}' doesn't use the {} bean {}, because it has another qualifier.", store,
                                type.getSimpleName(), name);
            }
        });
        return others;
    }

    /** Returns the names, in order, of the beans with a qualifier: a store's name, or none. */
    private static List<String> names(Map<String, Optional<String>> qualifiers, Optional<String> qualifier) {
        return qualifiers
                .entrySet()
                .stream()
                .filter(entry -> entry.getValue().equals(qualifier))
                .map(Map.Entry::getKey)
                .toList();
    }

    private static boolean isPrimary(ConfigurableListableBeanFactory beanFactory, String beanName) {
        return beanFactory.containsBeanDefinition(beanName)
                && beanFactory.getMergedBeanDefinition(beanName).isPrimary();
    }
}
