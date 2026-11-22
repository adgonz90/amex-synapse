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
package io.americanexpress.synapse.data.jpa.fixture.ownschemaaction;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.americanexpress.synapse.data.SynapseDataStore;
import io.americanexpress.synapse.data.internal.store.StoreStartStep;

/**
 * Declares the books store and replaces its schema action with one that does nothing.
 */
@Configuration(proxyBeanMethods = false)
@SynapseDataStore("books")
public class BooksStoreWithOwnSchemaAction {

    @Bean
    StoreStartStep booksSchemaAction() {
        return new OwnSchemaAction();
    }

    /** The application's schema action: it leaves the schema alone. */
    public static final class OwnSchemaAction implements StoreStartStep {

        @Override
        public String store() {
            return "books";
        }

        @Override
        public void start() {
            // The application manages the schema itself.
        }
    }
}
