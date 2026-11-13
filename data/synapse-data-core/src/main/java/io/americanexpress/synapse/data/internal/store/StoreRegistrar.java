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

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.zaxxer.hikari.HikariDataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConstructorArgumentValues.ValueHolder;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.env.Environment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.ClassUtils;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.data.DataAccess;
import io.americanexpress.synapse.data.SynapseDataStore;
import io.americanexpress.synapse.data.internal.migration.FlywayStoreFactory;

/**
 * Registers a store's bean definitions for each {@link SynapseDataStore}: its {@link StoreDefinition}, the beans
 * every store gets ({@code <prefix>DataSource}, {@code <prefix>JdbcTemplate},
 * {@code <prefix>NamedParameterJdbcTemplate}, {@code <prefix>JdbcClient}, {@code <prefix>Flyway} while Flyway is on
 * the class path, and {@code <prefix>StoreLifecycle}) and then its access module's. Each bean names
 * {@link StoreFactory} (for the Flyway, {@link FlywayStoreFactory}) as its factory, is qualified with the store name
 * and is primary when the store sets {@code primary}. A name the registry already has, such as a bean of the store's
 * class, is skipped: that bean replaces Synapse's, and {@link StoreDefinitionsPostProcessor} gives it the store's
 * qualifier (unless it names its own) and primary flag.
 */
public final class StoreRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware, ResourceLoaderAware {

    /** The bean definition attribute that holds the name of the store a bean belongs to. */
    public static final String STORE_ATTRIBUTE = StoreRegistrar.class.getName() + ".store";

    private static final String JPA_MODULE = "synapse-data-jpa";

    private static final String JDBC_MODULE = "synapse-data-jdbc";

    private static final String FLYWAY = "org.flywaydb.core.Flyway";

    private @Nullable Environment environment;

    private ResourceLoader resourceLoader = new DefaultResourceLoader();

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        Environment environment = this.environment;
        if (environment == null) {
            throw new IllegalStateException("StoreRegistrar has no environment");
        }
        MergedAnnotation<SynapseDataStore> annotation = metadata.getAnnotations().get(SynapseDataStore.class);
        String name = annotation.getString("value");
        String declaredBy = metadata.getClassName();
        if (!StoreNames.isValid(name)) {
            throw new SynapseConfigurationException(
                    "'" + name + "' is not a valid store name (@SynapseDataStore on " + declaredBy + ").",
                    "Use lowercase letters, digits and dashes, starting with a letter, such as books or "
                            + "legacy-orders.");
        }
        StoreDefinition existing = declared(registry, name);
        if (existing != null) {
            throw new SynapseConfigurationException("The store '" + existing.name() + "' is declared twice: "
                    + existing.declaredBy() + " and " + declaredBy + ".", "Declare each store once.");
        }
        ClassLoader classLoader = resourceLoader.getClassLoader();
        StoreInfrastructure infrastructure = resolveAccess(name, annotation.getEnum("access", DataAccess.class),
                available(SpringFactoriesLoader
                        .forDefaultResourceLocation(classLoader)
                        .load(StoreInfrastructure.class), classLoader));
        boolean primary = StoreSettings.bind(environment, name).primary();
        StoreDefinition store = new StoreDefinition(name, StoreNames.beanPrefix(name),
                ClassUtils.getPackageName(declaredBy), infrastructure.access(), primary, declaredBy);

        registry.registerBeanDefinition(store.beanName(StoreDefinition.BEAN_SUFFIX), definitionOf(store));
        registerStoreBean(registry, store, "DataSource", HikariDataSource.class, StoreFactory.BEAN_NAME, "dataSource");
        registerStoreBean(registry, store, "JdbcTemplate", JdbcTemplate.class, StoreFactory.BEAN_NAME, "jdbcTemplate");
        registerStoreBean(registry, store, "NamedParameterJdbcTemplate", NamedParameterJdbcTemplate.class,
                StoreFactory.BEAN_NAME, "namedParameterJdbcTemplate");
        registerStoreBean(registry, store, "JdbcClient", JdbcClient.class, StoreFactory.BEAN_NAME, "jdbcClient");
        if (ClassUtils.isPresent(FLYWAY, classLoader)) {
            // By name: this class loads without Flyway.
            registerStoreBean(registry, store, FlywayStoreFactory.BEAN_SUFFIX,
                    ClassUtils.resolveClassName(FLYWAY, classLoader), FlywayStoreFactory.BEAN_NAME, "flyway");
        }
        registerStoreBean(registry, store, StoreLifecycle.BEAN_SUFFIX, StoreLifecycle.class, StoreFactory.BEAN_NAME,
                "lifecycle");
        infrastructure.register(store, registry, environment, resourceLoader);

        if (!registry.containsBeanDefinition(StoreDefinitionsPostProcessor.BEAN_NAME)) {
            RootBeanDefinition postProcessor = new RootBeanDefinition(StoreDefinitionsPostProcessor.class);
            postProcessor.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
            registry.registerBeanDefinition(StoreDefinitionsPostProcessor.BEAN_NAME, postProcessor);
        }
    }

    /**
     * Registers one of a store's beans, unless the registry already has a bean with its name. The bean is made by
     * calling {@code factoryMethod(storeName)} on the bean {@code factoryBean}; it is qualified with the store name,
     * primary when the store sets {@code primary}, and has {@code type} as its target type. A bean that already has the
     * name replaces Synapse's: it is marked as the store's, and gets the store's qualifier and primary flag once every
     * store is registered.
     *
     * <p>
     * The factory beans are defined by auto-configuration, after the stores, and what Spring can tell about a store
     * bean before its factory bean exists depends on how it is asked:
     * <ul>
     * <li>{@code getType} and lookups by annotation ({@code @ConditionalOnBean(annotation = ...)}, as Boot's gRPC
     * server configuration has) see the bean's type. When {@code type} is a {@code FactoryBean} (JPA's
     * {@code LocalContainerEntityManagerFactoryBean}), Spring would resolve its object type from the factory bean's
     * definition first, and fail while that bean isn't defined, so the definition also carries its object type
     * ({@link FactoryBean#OBJECT_TYPE_ATTRIBUTE}, the {@code FactoryBean}'s generic as a {@code ResolvableType}), which
     * Spring reads before anything else.</li>
     * <li>Lookups by type without eager initialization, which Boot's type-based conditions use
     * ({@code @ConditionalOnBean(DataSource.class)}, {@code @ConditionalOnMissingBean}), don't see the store's beans at
     * all: Spring skips a definition whose factory bean isn't defined yet (an eager {@code getBeansOfType} sees them).
     * A type-based condition on store beans belongs in an auto-configuration ordered after the access module's
     * ({@code SynapseDataJpaAutoConfiguration} or {@code SynapseDataJdbcAutoConfiguration}), where every factory bean
     * exists; data.adoc says so.</li>
     * </ul>
     *
     * @param registry the registry
     * @param store the store
     * @param suffix the bean name's suffix, such as {@code DataSource}
     * @param type the type the factory method returns
     * @param factoryBean the name of the factory bean
     * @param factoryMethod the factory method, which takes the store name
     * @return {@code true} when the bean was registered, {@code false} when the name was taken
     * @throws IllegalArgumentException when {@code type} is a {@code FactoryBean} whose object type can't be resolved
     *         from its declaration
     */
    public static boolean registerStoreBean(BeanDefinitionRegistry registry, StoreDefinition store, String suffix,
            Class<?> type, String factoryBean, String factoryMethod) {
        String beanName = store.beanName(suffix);
        if (registry.containsBeanDefinition(beanName)) {
            // The bean replaces Synapse's, so it is the store's: StoreDefinitionsPostProcessor gives it the store's
            // qualifier and primary flag, as Synapse's own bean would have.
            registry.getBeanDefinition(beanName).setAttribute(STORE_ATTRIBUTE, store.name());
            return false;
        }
        RootBeanDefinition definition = new RootBeanDefinition();
        definition.setFactoryBeanName(factoryBean);
        definition.setFactoryMethodName(factoryMethod);
        definition.getConstructorArgumentValues().addIndexedArgumentValue(0, store.name());
        definition.setTargetType(type);
        if (FactoryBean.class.isAssignableFrom(type)) {
            // The whole generic, as Spring resolves it from a factory method's return type, so FactoryBean<Foo<Bar>>
            // keeps Bar.
            ResolvableType objectType = ResolvableType.forClass(type).as(FactoryBean.class).getGeneric(0);
            if (objectType.resolve() == null) {
                throw new IllegalArgumentException(
                        "The FactoryBean " + type.getName() + " declares no object type for the store bean "
                                + beanName);
            }
            definition.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE, objectType);
        }
        definition.setPrimary(store.primary());
        definition.addQualifier(new AutowireCandidateQualifier(Qualifier.class, store.name()));
        definition.setAttribute(STORE_ATTRIBUTE, store.name());
        // As for a @Bean method: a pool is closed with the context.
        definition.setDestroyMethodName(AbstractBeanDefinition.INFER_METHOD);
        registry.registerBeanDefinition(beanName, definition);
        return true;
    }

    /**
     * Chooses a store's access module. {@link DataAccess#AUTO} takes the one access on the class path. This is the
     * seam that tests the cases the class path of a test can't produce.
     *
     * @param name the store name
     * @param access the declared access
     * @param infrastructures the access modules on the class path
     * @return the module that registers the store's access beans
     * @throws SynapseConfigurationException when the access module is missing, or {@code AUTO} finds both
     */
    static StoreInfrastructure resolveAccess(String name, DataAccess access,
            List<StoreInfrastructure> infrastructures) {
        if (access == DataAccess.AUTO) {
            Set<DataAccess> present = new TreeSet<>();
            infrastructures.forEach(infrastructure -> present.add(infrastructure.access()));
            if (present.isEmpty()) {
                throw missing(name, "JPA or JDBC", JPA_MODULE + " or " + JDBC_MODULE);
            }
            if (present.size() > 1) {
                throw new SynapseConfigurationException("The store '" + name + "' must name its access: both "
                        + JPA_MODULE + " and " + JDBC_MODULE + " are present.",
                        "Use @SynapseDataStore(value = \"" + name
                                + "\", access = DataAccess.JPA) or DataAccess.JDBC.");
            }
            return infrastructures.get(0);
        }
        for (StoreInfrastructure infrastructure : infrastructures) {
            if (infrastructure.access() == access) {
                return infrastructure;
            }
        }
        throw missing(name, access.name(), access == DataAccess.JPA ? JPA_MODULE : JDBC_MODULE);
    }

    /**
     * Leaves out the access modules whose libraries are missing, such as synapse-data-jpa without Hibernate.
     *
     * @param infrastructures the access modules listed in {@code META-INF/spring.factories}
     * @param classLoader the application's class loader
     * @return the modules that are available
     */
    static List<StoreInfrastructure> available(List<StoreInfrastructure> infrastructures,
            @Nullable ClassLoader classLoader) {
        return infrastructures.stream().filter(infrastructure -> infrastructure.isAvailable(classLoader)).toList();
    }

    private static SynapseConfigurationException missing(String name, String access, String module) {
        return new SynapseConfigurationException(
                "The store '" + name + "' uses " + access + ", but " + module + " is not on the class path.",
                "Add " + module + ".");
    }

    /**
     * Returns the store already declared with a name that binds the same properties, if any.
     */
    private static @Nullable StoreDefinition declared(BeanDefinitionRegistry registry, String name) {
        for (String beanName : registry.getBeanDefinitionNames()) {
            StoreDefinition store = storeOf(registry.getBeanDefinition(beanName));
            if (store != null && StoreNames.sameStore(store.name(), name)) {
                return store;
            }
        }
        return null;
    }

    /**
     * Reads a store definition back from its bean definition. Public only so that the data modules share it (such as
     * synapse-test-data); internal.
     *
     * @param definition a bean definition
     * @return the store, or {@code null} when the definition isn't a store definition
     */
    public static @Nullable StoreDefinition storeOf(BeanDefinition definition) {
        if (!StoreDefinition.class.getName().equals(definition.getBeanClassName())) {
            return null;
        }
        return new StoreDefinition((String) argument(definition, 0), (String) argument(definition, 1),
                (String) argument(definition, 2), (DataAccess) argument(definition, 3),
                (Boolean) argument(definition, 4), (String) argument(definition, 5));
    }

    private static Object argument(BeanDefinition definition, int index) {
        ValueHolder holder = definition.getConstructorArgumentValues().getIndexedArgumentValue(index, null);
        Object value = holder == null ? null : holder.getValue();
        if (value == null) {
            throw new IllegalStateException("Store definition without argument " + index);
        }
        return value;
    }

    private static RootBeanDefinition definitionOf(StoreDefinition store) {
        RootBeanDefinition definition = new RootBeanDefinition(StoreDefinition.class);
        definition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
        var arguments = definition.getConstructorArgumentValues();
        arguments.addIndexedArgumentValue(0, store.name());
        arguments.addIndexedArgumentValue(1, store.beanPrefix());
        arguments.addIndexedArgumentValue(2, store.basePackage());
        arguments.addIndexedArgumentValue(3, store.access());
        arguments.addIndexedArgumentValue(4, store.primary());
        arguments.addIndexedArgumentValue(5, store.declaredBy());
        return definition;
    }
}
