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
package io.americanexpress.synapse.data.jdbc.fixture.ownnaming;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.relational.core.mapping.DefaultNamingStrategy;
import org.springframework.data.relational.core.mapping.NamingStrategy;

import io.americanexpress.synapse.data.SynapseDataStore;

/**
 * Declares the books store with a naming strategy of its own, which prefixes table names with {@code S_}.
 */
@Configuration(proxyBeanMethods = false)
@SynapseDataStore("books")
public class BooksStoreWithOwnNaming {

    @Bean
    NamingStrategy booksNamingStrategy() {
        return new DefaultNamingStrategy() {

            @Override
            public String getTableName(Class<?> type) {
                return "S_" + super.getTableName(type);
            }
        };
    }
}
