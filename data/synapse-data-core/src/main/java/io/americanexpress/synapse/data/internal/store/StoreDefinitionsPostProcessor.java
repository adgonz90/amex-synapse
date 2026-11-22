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
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName.Form;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

import io.americanexpress.synapse.core.SynapseConfigurationException;

/**
 * Runs once every store is registered, and checks the stores together: their packages must not overlap, every store
 * configured under {@code synapse.data.stores} must be declared, at most one may set {@code primary}, and no store's
 * {@code primary} may differ from the one it was registered with (under AOT, at build time). When the
 * service has a single store, its beans are primary whether or not it sets {@code primary}. A bean that replaces one
 * of a store's beans (see {@link StoreRegistrar#registerStoreBean}) is primary exactly as Synapse's would be, and is
 * qualified with the store name unless it names a qualifier of its own. It is one bean,
 * {@value #BEAN_NAME}: the data auto-configuration registers it, so the checks run even when no store is declared, and
 * {@link StoreRegistrar} registers it when the registry doesn't have it yet.
 */
public final class StoreDefinitionsPostProcessor implements BeanDefinitionRegistryPostProcessor, EnvironmentAware {

    /** The post-processor's bean name. */
    public static final String BEAN_NAME = "synapseStoreDefinitionsPostProcessor";

    /**
     * The bean definition {@code @SynapseDataTest} registers. The slice leaves out the stores it doesn't test, so their
     * settings are expected.
     */
    static final String TEST_SLICE = "synapseDataTestSlice";

    private static final ConfigurationPropertyName STORES = ConfigurationPropertyName
            .of(StoreSettings.PREFIX);

    private @Nullable Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        List<StoreDefinition> stores = new ArrayList<>();
        for (String name : registry.getBeanDefinitionNames()) {
            StoreDefinition store = StoreRegistrar.storeOf(registry.getBeanDefinition(name));
            if (store != null) {
                stores.add(store);
            }
        }
        checkPackages(stores);
        Environment environment = this.environment;
        if (environment != null) {
            checkKeys(stores, environment, !registry.containsBeanDefinition(TEST_SLICE));
            checkPrimaryUnchanged(stores, environment);
        }
        checkPrimaries(stores);
        Set<String> primaries = new HashSet<>();
        for (StoreDefinition store : stores) {
            if (store.primary() || stores.size() == 1) {
                primaries.add(store.name());
            }
        }
        for (String name : registry.getBeanDefinitionNames()) {
            BeanDefinition definition = registry.getBeanDefinition(name);
            if (definition.getAttribute(StoreRegistrar.STORE_ATTRIBUTE) instanceof String store) {
                if (primaries.contains(store)) {
                    definition.setPrimary(true);
                }
                if (definition instanceof AbstractBeanDefinition qualified && !hasQualifier(registry, name)) {
                    // A bean that replaces Synapse's, without a qualifier of its own.
                    qualified.addQualifier(new AutowireCandidateQualifier(Qualifier.class, store));
                }
            }
        }
    }

    /**
     * Whether a bean has a qualifier: one that Synapse added to a store bean, or one the replacing bean names itself
     * (on its definition, its {@code @Bean} method, or its class), which it keeps.
     */
    private static boolean hasQualifier(BeanDefinitionRegistry registry, String name) {
        if (registry instanceof ConfigurableListableBeanFactory beanFactory) {
            return BeanQualifiers.qualifier(beanFactory, name) != null;
        }
        return BeanQualifiers.declaredQualifier(registry.getBeanDefinition(name)) != null;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // Everything happens on the registry.
    }

    /**
     * Fails when one store's package is another's or lies inside it: the outer store would scan the inner store's
     * entities and repositories. The outer store is named first.
     */
    private static void checkPackages(List<StoreDefinition> stores) {
        for (int i = 0; i < stores.size(); i++) {
            for (int j = i + 1; j < stores.size(); j++) {
                StoreDefinition first = stores.get(i);
                StoreDefinition second = stores.get(j);
                if (contains(first.basePackage(), second.basePackage())) {
                    throw overlap(first, second);
                }
                if (contains(second.basePackage(), first.basePackage())) {
                    throw overlap(second, first);
                }
            }
        }
    }

    private static boolean contains(String outer, String inner) {
        return inner.equals(outer) || inner.startsWith(outer + ".");
    }

    private static SynapseConfigurationException overlap(StoreDefinition outer, StoreDefinition inner) {
        return new SynapseConfigurationException(
                "The packages of the stores '" + outer.name() + "' (" + outer.basePackage() + ") and '" + inner.name()
                        + "' (" + inner.basePackage() + ") overlap.",
                "Give each store's @SynapseDataStore class, entities and repositories their own package, outside "
                        + "every other store's package.");
    }

    /**
     * Checks the keys under {@code synapse.data.stores}; only their names are read, never their values. A key that
     * writes its store in brackets ({@code synapse.data.stores[legacy-orders].username}) always fails: a store binds
     * only the dotted form, so the key would be ignored. Unless {@code checkUndeclared} is {@code false}, a key that
     * names a store no {@code @SynapseDataStore} declares fails too. Names are compared as Boot matches them, ignoring
     * case and dashes: {@code SYNAPSE_DATA_STORES_LEGACYORDERS_PASSWORD} and {@code legacyOrders} belong to
     * {@code legacy-orders}. Each failure names the first offending key or store in sorted order.
     */
    private static void checkKeys(List<StoreDefinition> stores, Environment environment, boolean checkUndeclared) {
        Set<String> declared = new HashSet<>();
        stores.forEach(store -> declared.add(uniform(store.name())));
        int storeElement = STORES.getNumberOfElements();
        SortedMap<String, ConfigurationPropertyName> bracketed = new TreeMap<>();
        // Keyed by the uniform name, so each store is reported once, in the form first seen.
        SortedMap<String, String> undeclared = new TreeMap<>();
        for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
            if (source instanceof IterableConfigurationPropertySource iterable) {
                for (ConfigurationPropertyName key : iterable) {
                    if (STORES.isAncestorOf(key)) {
                        if (key.chop(storeElement + 1).isLastElementIndexed()) {
                            bracketed.putIfAbsent(key.toString(), key);
                        }
                        String name = key.getElement(storeElement, Form.ORIGINAL);
                        String uniform = uniform(name);
                        if (!declared.contains(uniform)) {
                            undeclared.putIfAbsent(uniform, name);
                        }
                    }
                }
            }
        }
        if (!bracketed.isEmpty()) {
            throw bracketed(bracketed.values().iterator().next());
        }
        if (checkUndeclared && !undeclared.isEmpty()) {
            throw undeclared(undeclared.values().iterator().next());
        }
    }

    private static SynapseConfigurationException bracketed(ConfigurationPropertyName key) {
        int storeElement = STORES.getNumberOfElements();
        StringBuilder dotted = new StringBuilder(StoreSettings
                .prefix(key.getElement(storeElement, Form.ORIGINAL)));
        if (key.getNumberOfElements() > storeElement + 1) {
            dotted.append('.').append(key.subName(storeElement + 1));
        }
        return new SynapseConfigurationException(
                "The key " + key + " names its store in brackets, but store keys use the dotted form, so it would "
                        + "be ignored.",
                "Write it as " + dotted + ".");
    }

    /**
     * Returns a store name as Boot compares a property name element: lower case, letters and digits only.
     */
    private static String uniform(String name) {
        StringBuilder uniform = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = Character.toLowerCase(name.charAt(i));
            if (Character.isLetterOrDigit(c)) {
                uniform.append(c);
            }
        }
        return uniform.toString();
    }

    private static SynapseConfigurationException undeclared(String name) {
        return new SynapseConfigurationException(
                StoreSettings.prefix(name) + " is configured, but no @SynapseDataStore(\"" + name
                        + "\") declares the store.",
                "Add the data module that declares it, or remove the settings.");
    }

    /**
     * Fails when a store's {@code primary} setting differs from the one its definition was registered with, which
     * its beans' primary flags already carry. Under AOT the definitions were generated at build time, and this
     * post-processor runs again at startup with the run-time environment, so a change made after the build (in an
     * environment file, a variable or the command line) would otherwise be ignored. On the JVM the two are equal
     * unless a property source arrives after the store was registered, such as a {@code @PropertySource} on a
     * configuration class that a later configuration round registers. The message covers both cases and names only
     * the key.
     */
    private static void checkPrimaryUnchanged(List<StoreDefinition> stores, Environment environment) {
        for (StoreDefinition store : stores) {
            if (StoreSettings.bind(environment, store.name()).primary() != store.primary()) {
                String key = StoreSettings.prefix(store.name()) + ".primary";
                throw new SynapseConfigurationException(
                        "The store '" + store.name() + "' sets " + key + " differently from when its beans were "
                                + "registered (in AOT mode, at build time).",
                        "Set " + key + " in config.yaml or application.yaml, the same in every environment and not "
                                + "in a @PropertySource, and in AOT mode build again.");
            }
        }
    }

    private static void checkPrimaries(List<StoreDefinition> stores) {
        List<String> primaries = stores.stream().filter(StoreDefinition::primary).map(StoreDefinition::name).toList();
        if (primaries.size() > 1) {
            throw new SynapseConfigurationException(
                    "More than one store sets primary: " + String.join(", ", primaries)
                            + ".",
                    "Set synapse.data.stores.<name>.primary: true on one store only.");
        }
    }
}
