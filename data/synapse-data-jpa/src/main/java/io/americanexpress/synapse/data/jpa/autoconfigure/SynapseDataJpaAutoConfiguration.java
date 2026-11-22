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
package io.americanexpress.synapse.data.jpa.autoconfigure;

import jakarta.persistence.EntityManager;

import org.hibernate.engine.spi.SessionImplementor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

import io.americanexpress.synapse.data.SynapseDataStore;
import io.americanexpress.synapse.data.autoconfigure.SynapseDataAutoConfiguration;
import io.americanexpress.synapse.data.internal.store.StoreFactory;
import io.americanexpress.synapse.data.jpa.internal.JpaExceptionTranslator;
import io.americanexpress.synapse.data.jpa.internal.JpaStoreFactory;

/**
 * Builds the JPA beans of every {@link SynapseDataStore} that uses JPA: an EntityManagerFactory and a transaction
 * manager per store. The store's bean definitions name the {@code synapseJpaStoreFactory} bean defined here as their
 * factory. With a store's EntityManagerFactory and transaction manager defined, Boot's own back off. With a single
 * store, or a primary one, Boot's JPA configuration still matches (its DataSource is a single candidate): it defines
 * {@code jpaVendorAdapter} and {@code entityManagerFactoryBuilder} from {@code spring.jpa.*}, which no store uses, and
 * applies {@code spring.jpa.open-in-view} to that store (startup warns when the application sets it).
 */
@AutoConfiguration(after = SynapseDataAutoConfiguration.class, before = HibernateJpaAutoConfiguration.class)
@ConditionalOnClass({LocalContainerEntityManagerFactoryBean.class, EntityManager.class, SessionImplementor.class})
@ConditionalOnBean(StoreFactory.class)
public final class SynapseDataJpaAutoConfiguration {

    private static final String JPA_EXCEPTION_TRANSLATOR = "synapseJpaExceptionTranslator";

    SynapseDataJpaAutoConfiguration() {
    }

    @Bean(JpaStoreFactory.BEAN_NAME)
    @ConditionalOnMissingBean
    static JpaStoreFactory synapseJpaStoreFactory(ConfigurableListableBeanFactory beanFactory, Environment environment,
            ResourceLoader resourceLoader, StoreFactory storeFactory) {
        return new JpaStoreFactory(beanFactory, environment, resourceLoader, storeFactory);
    }

    /**
     * Translates JPA's and Hibernate's own exceptions into Synapse errors ({@code STALE_VERSION} for an optimistic lock
     * failure, {@code ALREADY_EXISTS} for a unique-constraint violation) for the operation pipeline. Define a bean
     * with the same name to replace it.
     *
     * @return the translator
     */
    @Bean(JPA_EXCEPTION_TRANSLATOR)
    @ConditionalOnMissingBean(name = JPA_EXCEPTION_TRANSLATOR)
    static JpaExceptionTranslator synapseJpaExceptionTranslator() {
        return new JpaExceptionTranslator();
    }
}
