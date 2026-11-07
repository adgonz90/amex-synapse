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
package io.americanexpress.synapse.data.autoconfigure;

import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import io.americanexpress.synapse.data.SynapseDataStore;
import io.americanexpress.synapse.data.internal.store.StoreDefinitionsPostProcessor;

/**
 * Checks the {@link SynapseDataStore} declarations against each other and against {@code synapse.data.stores}, even
 * when no store is declared: a store's settings without its declaration are a mistake whatever the pool, so this has
 * no condition on Hikari.
 */
@AutoConfiguration
public final class SynapseDataStoreChecksAutoConfiguration {

    SynapseDataStoreChecksAutoConfiguration() {
    }

    /**
     * The post-processor that checks the stores. The store registrar registers the same bean when it runs first.
     *
     * @return the post-processor
     */
    @Bean(StoreDefinitionsPostProcessor.BEAN_NAME)
    @ConditionalOnMissingBean(name = StoreDefinitionsPostProcessor.BEAN_NAME)
    static BeanDefinitionRegistryPostProcessor synapseStoreDefinitionsPostProcessor() {
        return new StoreDefinitionsPostProcessor();
    }
}
