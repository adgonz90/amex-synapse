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

import io.americanexpress.synapse.data.DataAccess;

/**
 * A declared store, as {@code @SynapseDataStore} declared it. The registrar registers one per store as the bean
 * {@code <prefix>StoreDefinition}.
 *
 * @param name the store name, as written
 * @param beanPrefix the prefix of the store's bean names
 * @param basePackage the package of the declaring class: the store's entities and repositories are in it and below
 * @param access the resolved access, {@link DataAccess#JPA} or {@link DataAccess#JDBC}
 * @param primary whether the store sets {@code primary}; a single store's beans are primary either way
 * @param declaredBy the name of the declaring class
 */
public record StoreDefinition(String name, String beanPrefix, String basePackage, DataAccess access, boolean primary,
        String declaredBy) {

    /** The suffix of the bean name of a store's definition. */
    public static final String BEAN_SUFFIX = "StoreDefinition";

    /**
     * Returns the name of one of the store's beans.
     *
     * @param suffix the bean's suffix, such as {@code DataSource}
     * @return the bean name, such as {@code booksDataSource}
     */
    public String beanName(String suffix) {
        return beanPrefix + suffix;
    }
}
