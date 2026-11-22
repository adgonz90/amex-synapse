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
package io.americanexpress.synapse.data;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Import;

import io.americanexpress.synapse.data.internal.store.StoreRegistrar;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Declares a named data store on a data module's configuration class. The store is configured under
 * {@code synapse.data.stores.<name>}; its entities and repositories are in this class's package and below. Its beans
 * are named after the store ({@code booksDataSource}, {@code booksJdbcTemplate}, ...) and carry the qualifier
 * {@code <name>}. A bean with one of those names defined in the annotated class replaces Synapse's.
 */
@Target(TYPE)
@Retention(RUNTIME)
@Documented
@Import(StoreRegistrar.class)
public @interface SynapseDataStore {

    /**
     * The store name: lowercase letters, digits and dashes, starting with a letter, such as {@code books} or
     * {@code legacy-orders}.
     *
     * @return the store name
     */
    String value();

    /**
     * How the store's entities are accessed. {@link DataAccess#AUTO} uses the one access module on the class path;
     * with both synapse-data-jpa and synapse-data-jdbc present, name it.
     *
     * @return the access
     */
    DataAccess access() default DataAccess.AUTO;
}
