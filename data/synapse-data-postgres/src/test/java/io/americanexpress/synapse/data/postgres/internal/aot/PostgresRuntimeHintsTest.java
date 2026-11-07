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
package io.americanexpress.synapse.data.postgres.internal.aot;

import java.lang.reflect.Constructor;

import org.hibernate.dialect.PostgreSQLDialect;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.data.jdbc.core.dialect.JdbcPostgresDialect;

import io.americanexpress.synapse.data.postgres.PostgresVendor;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresRuntimeHintsTest {

    /** Hibernate creates a dialect it is given by name with one of its public constructors. */
    @Test
    void registersTheHibernateDialectsConstructors() {
        RuntimeHints hints = hints();

        Constructor<?>[] constructors = PostgreSQLDialect.class.getConstructors();
        assertThat(constructors).isNotEmpty();
        for (Constructor<?> constructor : constructors) {
            assertThat(RuntimeHintsPredicates.reflection().onConstructorInvocation(constructor))
                    .as(constructor.toString())
                    .accepts(hints);
        }
    }

    /** synapse-data-jdbc reads a JDBC dialect from its public static INSTANCE field. */
    @Test
    void registersTheJdbcDialectsInstanceField() {
        assertThat(RuntimeHintsPredicates.reflection().onFieldAccess(JdbcPostgresDialect.class, "INSTANCE"))
                .accepts(hints());
    }

    @Test
    void theHintsAreForTheVendorsDialects() {
        PostgresVendor vendor = new PostgresVendor();

        assertThat(vendor.hibernateDialect()).isEqualTo(PostgreSQLDialect.class.getName());
        assertThat(vendor.jdbcDialect()).isEqualTo(JdbcPostgresDialect.class.getName());
    }

    @Test
    void isListedInAotFactories() {
        assertThat(SpringFactoriesLoader
                .forResourceLocation("META-INF/spring/aot.factories")
                .load(RuntimeHintsRegistrar.class)).anyMatch(PostgresRuntimeHints.class::isInstance);
    }

    private static RuntimeHints hints() {
        RuntimeHints hints = new RuntimeHints();
        new PostgresRuntimeHints().registerHints(hints, PostgresRuntimeHintsTest.class.getClassLoader());
        return hints;
    }
}
