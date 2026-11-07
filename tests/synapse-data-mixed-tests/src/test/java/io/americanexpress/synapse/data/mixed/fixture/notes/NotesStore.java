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
package io.americanexpress.synapse.data.mixed.fixture.notes;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;

import io.americanexpress.synapse.data.DataAccess;
import io.americanexpress.synapse.data.SynapseDataStore;

/**
 * Declares the notes store, which uses Spring Data JDBC and replaces its transaction manager.
 */
@Configuration(proxyBeanMethods = false)
@SynapseDataStore(value = "notes", access = DataAccess.JDBC)
public class NotesStore {

    /**
     * Replaces Synapse's transaction manager of the store.
     *
     * @param dataSource the store's pool
     * @return the transaction manager
     */
    @Bean
    JdbcTransactionManager notesTransactionManager(@Qualifier("notes") DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }
}
