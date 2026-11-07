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
package io.americanexpress.synapse.data.jpa.fixture.factoryemf;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

import io.americanexpress.synapse.data.SynapseDataStore;
import io.americanexpress.synapse.data.jpa.internal.JpaStoreFactory;

/**
 * Declares the books store and defines its EntityManagerFactory itself, by calling Synapse's JPA store factory: the
 * bean is the application's even though Synapse's code built it, and its integrator ran.
 */
@Configuration(proxyBeanMethods = false)
@SynapseDataStore("books")
public class BooksStoreWithFactoryEmf {

    @Bean
    LocalContainerEntityManagerFactoryBean booksEntityManagerFactory(JpaStoreFactory factory) {
        return factory.entityManagerFactory("books");
    }
}
