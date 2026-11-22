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
package io.americanexpress.synapse.data.mixed.fixture.books;

import org.springframework.context.annotation.Configuration;

import io.americanexpress.synapse.data.DataAccess;
import io.americanexpress.synapse.data.SynapseDataStore;

/**
 * Declares the books store, which uses JPA.
 */
@Configuration(proxyBeanMethods = false)
@SynapseDataStore(value = "books", access = DataAccess.JPA)
public class BooksStore {
}
