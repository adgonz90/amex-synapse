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
package io.americanexpress.synapse.test.data;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Connects a store to the database of a Testcontainers {@code JdbcDatabaseContainer}. It goes on a {@code static}
 * field of the test class, a superclass or an enclosing class, that holds the container, such as
 * {@code @StoreConnection("books") static PostgreSQLContainer books = SynapseContainers.postgres();}. The container
 * is started if it isn't running. The test's context then has a {@code JdbcConnectionDetails} bean named
 * {@code <prefix>StoreConnectionDetails} ({@code booksStoreConnectionDetails}), qualified with the store name and not
 * a default candidate, which the store connects through instead of its {@code url}, {@code username} and
 * {@code password}. Containers defined by {@code @Bean} methods are not supported. The details bean is registered
 * with an instance supplier, so test contexts processed ahead of time (AOT) are not supported either. A subclass or
 * a {@code @Nested} class can't replace a superclass's or enclosing class's connection for the same store: two
 * fields for one store fail.
 *
 * @see SynapseContainers
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface StoreConnection {

    /**
     * The store the container's database is for, such as {@code books}.
     *
     * @return the store name
     */
    String value();
}
