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
package io.americanexpress.synapse.data.jdbc.autoconfigure;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.data.relational.RelationalManagedTypes;

import io.americanexpress.synapse.data.Auditable;
import io.americanexpress.synapse.data.SynapseDataStore;
import io.americanexpress.synapse.data.autoconfigure.SynapseDataAutoConfiguration;
import io.americanexpress.synapse.data.internal.audit.AuditStamps;
import io.americanexpress.synapse.data.internal.store.StoreFactory;
import io.americanexpress.synapse.data.jdbc.internal.JdbcAuditingCallback;
import io.americanexpress.synapse.data.jdbc.internal.JdbcStoreFactory;

/**
 * Builds the Spring Data JDBC beans of every {@link SynapseDataStore} that uses JDBC: a transaction manager, the
 * vendor's dialect, conversions, a mapping context, converter, data access strategy and aggregate template per store.
 * The store's bean definitions name the {@code synapseJdbcStoreFactory} bean defined here as their factory.
 * <p>
 * Boot's Spring Data JDBC repositories are off by default ({@code spring.data.jdbc.repositories.enabled=false}). An
 * application that turns them on gets the beans of Boot's configuration that it defines only when missing backed off
 * to the stores', but Boot still defines {@code idGeneratingBeforeSaveCallback} on the primary store's beans. With
 * several stores and none primary that fails startup. With a primary store, that callback is context-wide, so it runs
 * for every store's aggregates and takes their {@code @Sequence} ids from the primary store's database: a sequence the
 * primary lacks fails the save, and one it has gives ids from the wrong database.
 */
@AutoConfiguration(after = SynapseDataAutoConfiguration.class, beforeName = "org.springframework.boot.data.jdbc.autoconfigure.DataJdbcRepositoriesAutoConfiguration")
@ConditionalOnClass({JdbcAggregateTemplate.class, RelationalManagedTypes.class})
@ConditionalOnBean(StoreFactory.class)
public final class SynapseDataJdbcAutoConfiguration {

    private static final String AUDITING_CALLBACK = "synapseJdbcAuditingCallback";

    SynapseDataJdbcAutoConfiguration() {
    }

    @Bean(JdbcStoreFactory.BEAN_NAME)
    @ConditionalOnMissingBean
    static JdbcStoreFactory synapseJdbcStoreFactory(ConfigurableListableBeanFactory beanFactory,
            ApplicationContext applicationContext, StoreFactory storeFactory) {
        return new JdbcStoreFactory(beanFactory, applicationContext, storeFactory);
    }

    /**
     * Stamps {@link Auditable} aggregates when a JDBC store saves them: the creation once, the modification on every
     * save. Define a bean with the same name to replace it.
     *
     * @param stamps the time and user to stamp, shared with the JPA stores
     * @return the callback
     */
    @Bean(AUDITING_CALLBACK)
    @ConditionalOnMissingBean(name = AUDITING_CALLBACK)
    static JdbcAuditingCallback synapseJdbcAuditingCallback(AuditStamps stamps) {
        return new JdbcAuditingCallback(stamps);
    }
}
