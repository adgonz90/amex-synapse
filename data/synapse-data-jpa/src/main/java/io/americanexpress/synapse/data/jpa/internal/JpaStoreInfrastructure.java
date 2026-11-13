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
package io.americanexpress.synapse.data.jpa.internal;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.jpa.repository.config.JpaRepositoryConfigExtension;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.util.ClassUtils;

import io.americanexpress.synapse.data.DataAccess;
import io.americanexpress.synapse.data.internal.store.IgnoredKeys;
import io.americanexpress.synapse.data.internal.store.StoreDefinition;
import io.americanexpress.synapse.data.internal.store.StoreInfrastructure;
import io.americanexpress.synapse.data.internal.store.StoreRegistrar;
import io.americanexpress.synapse.data.internal.store.StoreRepositoryConfigurationSource;
import io.americanexpress.synapse.data.jpa.SynapseJpaTransactionManager;

/**
 * Registers a JPA store's beans: {@code <prefix>PersistenceManagedTypes}, {@code <prefix>EntityManagerFactory},
 * {@code <prefix>TransactionManager} and {@code <prefix>SchemaAction} (the {@link JpaSchemaAction} the store runs when
 * it starts), each built by {@link JpaStoreFactory}, and the Spring Data JPA repositories in and
 * below the store's package, which use that EntityManagerFactory and transaction manager. A name the registry already
 * has, such as a bean of the store's class, is skipped. Listed in {@code META-INF/spring.factories}.
 */
public final class JpaStoreInfrastructure implements StoreInfrastructure {

    /** The classes Boot's Hibernate auto-configuration requires, and {@link JpaStoreFactory} builds with. */
    private static final String[] REQUIRED_CLASSES = {
            "org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean",
            "jakarta.persistence.EntityManager", "org.hibernate.engine.spi.SessionImplementor"};

    private static final String OPEN_IN_VIEW = "spring.jpa.open-in-view";

    @Override
    public DataAccess access() {
        return DataAccess.JPA;
    }

    @Override
    public Class<?> transactionManagerType() {
        return SynapseJpaTransactionManager.class;
    }

    /**
     * Returns whether Hibernate, JPA and Spring's JPA support are on the class path.
     *
     * @param classLoader the class loader of the application
     * @return {@code true} when every class the module builds with is present
     */
    @Override
    public boolean isAvailable(@Nullable ClassLoader classLoader) {
        for (String className : REQUIRED_CLASSES) {
            if (!ClassUtils.isPresent(className, classLoader)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the Boot keys that JPA stores ignore: {@code spring.jpa.*}, since each store binds its own under
     * {@code synapse.data.stores.<name>.jpa}. {@code spring.jpa.open-in-view} has a WARN of its own: Boot's web
     * configuration applies it to a single store, or to the primary one. It warns only when the value isn't
     * {@code false} (Synapse's own default), comparing the value without logging it.
     *
     * @return the keys
     */
    @Override
    public List<IgnoredKeys> ignoredKeys() {
        IgnoredKeys openInView = IgnoredKeys
                .key(OPEN_IN_VIEW, OPEN_IN_VIEW + " is set. Synapse turns open-in-view off by default, since "
                        + "operations are the transaction boundary: with a single store, or a primary one, Boot "
                        + "applies the key to it and can keep an EntityManager open for a whole web request.", "false");
        return List.of(IgnoredKeys.prefix("spring.jpa"), openInView);
    }

    @Override
    public void register(StoreDefinition store, BeanDefinitionRegistry registry, Environment environment,
            ResourceLoader resourceLoader) {
        StoreRegistrar
                .registerStoreBean(registry, store, JpaStoreFactory.MANAGED_TYPES_SUFFIX,
                        PersistenceManagedTypes.class, JpaStoreFactory.BEAN_NAME, "managedTypes");
        StoreRegistrar
                .registerStoreBean(registry, store, JpaStoreFactory.ENTITY_MANAGER_FACTORY_SUFFIX,
                        LocalContainerEntityManagerFactoryBean.class, JpaStoreFactory.BEAN_NAME,
                        JpaStoreFactory.ENTITY_MANAGER_FACTORY_METHOD);
        StoreRegistrar
                .registerStoreBean(registry, store, JpaStoreFactory.TRANSACTION_MANAGER_SUFFIX,
                        SynapseJpaTransactionManager.class, JpaStoreFactory.BEAN_NAME, "transactionManager");
        StoreRegistrar
                .registerStoreBean(registry, store, JpaStoreFactory.SCHEMA_ACTION_SUFFIX, JpaSchemaAction.class,
                        JpaStoreFactory.BEAN_NAME, "schemaActionStep");
        Map<String, String> references = Map
                .of("entityManagerFactoryRef", store.beanName(JpaStoreFactory.ENTITY_MANAGER_FACTORY_SUFFIX),
                        "transactionManagerRef", store.beanName(JpaStoreFactory.TRANSACTION_MANAGER_SUFFIX));
        new StoreRepositoryConfigurationSource(store, EnableJpaRepositories.class, references, environment,
                resourceLoader, registry).registerRepositories(new JpaRepositoryConfigExtension());
    }
}
