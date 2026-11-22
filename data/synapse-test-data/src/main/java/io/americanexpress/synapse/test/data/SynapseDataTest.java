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
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.autoconfigure.OverrideAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTestContextBootstrapper;
import org.springframework.boot.test.context.filter.annotation.TypeExcludeFilters;
import org.springframework.core.annotation.AliasFor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.BootstrapWith;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test slice for one data store: starts the {@code @SynapseDataStore} class of the named store, with its pool,
 * migrations, entities and repositories, and leaves out every other store and component (operations, controllers,
 * services). Each test runs in a transaction of that store, rolled back afterwards, as with {@code @DataJpaTest}.
 * The context fails to load, naming the declared stores, when no {@code @SynapseDataStore} declares the named store.
 *
 * <p>
 * The application's test settings may configure other stores: the slice expects their settings and doesn't start
 * them. The store connects as {@code synapse.data.stores.<name>} says, unless the test connects it to a container
 * with {@link StoreConnection}. The slice registers a {@link io.americanexpress.synapse.test.time.MutableClock} fixed
 * at {@code 2026-01-01T00:00:00Z} unless the test defines its own {@link java.time.Clock}, so audit timestamps and
 * UUIDv7 ids follow it, and activates the {@code test} profile.
 *
 * @see AutoConfigureSynapseData
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@BootstrapWith(SpringBootTestContextBootstrapper.class)
@ExtendWith(SpringExtension.class)
@OverrideAutoConfiguration(enabled = false)
@TypeExcludeFilters(SynapseDataTypeExcludeFilter.class)
@AutoConfigureSynapseData
@Transactional
@ActiveProfiles("test")
public @interface SynapseDataTest {

    /**
     * The store to test, such as {@code books}: the value of its {@code @SynapseDataStore}. It is also the qualifier of
     * the transaction manager each test runs in.
     *
     * @return the store name
     */
    @AliasFor(annotation = Transactional.class, attribute = "transactionManager")
    String value();
}
