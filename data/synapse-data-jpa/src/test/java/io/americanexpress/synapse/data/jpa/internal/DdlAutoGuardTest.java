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
package io.americanexpress.synapse.data.jpa.internal;

import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jpa.autoconfigure.JpaProperties;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizationAutoConfiguration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.data.autoconfigure.SynapseDataAutoConfiguration;
import io.americanexpress.synapse.data.autoconfigure.SynapseDataStoreProperties;
import io.americanexpress.synapse.data.internal.store.StoreSettings;
import io.americanexpress.synapse.data.jpa.autoconfigure.SynapseDataJpaAutoConfiguration;
import io.americanexpress.synapse.data.jpa.fixture.H2TestVendor;
import io.americanexpress.synapse.data.jpa.fixture.books.BooksStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The {@code ddl-auto} guard (Defaults #12): which schema action a store runs, and which settings fail startup.
 */
class DdlAutoGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations
                    .of(SynapseCoreAutoConfiguration.class, SynapseDataAutoConfiguration.class,
                            SynapseDataJpaAutoConfiguration.class,
                            DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
                            TransactionManagerCustomizationAutoConfiguration.class,
                            TransactionAutoConfiguration.class))
            .withUserConfiguration(H2TestVendor.class, BooksStore.class);

    @ParameterizedTest
    @ValueSource(strings = {"create", "create-drop", "update"})
    void schemaChangingValuesFailWithoutAllowDdlAuto(String value) {
        runner
                .withPropertyValues(store("guard-" + value))
                .withPropertyValues("synapse.data.stores.books.jpa.hibernate.ddl-auto=" + value)
                .run(context -> assertFailure(context,
                        "The store 'books' sets ddl-auto to " + value + ", which changes the schema at startup.",
                        "Use migrations in db/migration/books, or allow it for one environment with "
                                + "synapse.data.stores.books.allow-ddl-auto: true in application-local.yaml or a test "
                                + "file."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "create-drop", "update"})
    void allowDdlAutoPermitsThem(String value) {
        runner
                .withPropertyValues(store("allowed-" + value))
                .withPropertyValues("synapse.data.stores.books.jpa.hibernate.ddl-auto=" + value,
                        "synapse.data.stores.books.allow-ddl-auto=true",
                        "synapse.data.stores.books.migrations.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(schemaAction(context).action()).isEqualTo(value);
                    assertThat(vendorProperties(context))
                            .as("Hibernate never runs the action itself")
                            .containsEntry("hibernate.hbm2ddl.auto", "none");
                });
    }

    @Test
    void defaultIsValidateWithFlyway() {
        assertThat(JpaStoreFactory
                .schemaAction("books", properties(), new JpaProperties(), new HibernateProperties(), true))
                .isEqualTo("validate");
        runner
                .withPropertyValues(store("default-flyway"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(schemaAction(context).action()).isEqualTo("validate");
                });
        runner
                .withPropertyValues(store("default-flyway-off"))
                .withPropertyValues("synapse.data.stores.books.migrations.enabled=false")
                .run(context -> {
                    assertThat(context)
                            .as("validates even with the migrations off, and the table is missing")
                            .hasFailed();
                    assertThat(NestedExceptionUtils
                            .getMostSpecificCause(Objects.requireNonNull(context.getStartupFailure())))
                            .isInstanceOfSatisfying(SynapseConfigurationException.class,
                                    failure -> assertThat(failure.description())
                                            .startsWith("The schema of the store 'books' does not match its "
                                                    + "entities: "));
                });
    }

    @Test
    void defaultIsNoneWithoutFlyway() {
        assertThat(JpaStoreFactory
                .schemaAction("books", properties(), new JpaProperties(), new HibernateProperties(), false))
                .isEqualTo("none");
        runner
                .withClassLoader(new FilteredClassLoader("org.flywaydb"))
                .withPropertyValues(store("default-noflyway"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(schemaAction(context).action()).isEqualTo("none");
                });
    }

    @Test
    void unknownValueFails() {
        runner
                .withPropertyValues(store("unknown"))
                .withPropertyValues("synapse.data.stores.books.jpa.hibernate.ddl-auto=drop",
                        "synapse.data.stores.books.allow-ddl-auto=true")
                .run(context -> assertFailure(context,
                        "The store 'books' sets ddl-auto to 'drop', which is not one of none, validate, update, "
                                + "create, create-drop.",
                        "Use one of these values."));
    }

    /** Review Focus 3: a raw Hibernate or JPA schema key fails like {@code ddl-auto: update}, even when allowed. */
    @ParameterizedTest
    @ValueSource(strings = {"hibernate.hbm2ddl.auto", "hibernate.hbm2ddl.auto.orm",
            "hibernate.hbm2ddl.halt_on_error", "jakarta.persistence.schema-generation.database.action",
            "javax.persistence.schema-generation.database.action",
            "javax.persistence.schema-generation.scripts.action"})
    void rawSchemaKeysAreRejected(String key) {
        runner
                .withPropertyValues(store("raw"))
                .withPropertyValues("synapse.data.stores.books.jpa.properties." + key + "=update",
                        "synapse.data.stores.books.allow-ddl-auto=true")
                .run(context -> assertFailure(context,
                        "The store 'books' sets " + key + " in synapse.data.stores.books.jpa.properties.",
                        "Use synapse.data.stores.books.jpa.hibernate.ddl-auto instead."));
    }

    @Test
    void rawSchemaKeysAreRejectedByFamily() {
        JpaProperties jpa = new JpaProperties();
        jpa.getProperties().put("jakarta.persistence.schema-generation.create-source", "metadata");
        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> JpaStoreFactory
                        .schemaAction("books", properties(), jpa, new HibernateProperties(), true))
                .satisfies(failure -> assertThat(failure.description())
                        .isEqualTo("The store 'books' sets jakarta.persistence.schema-generation.create-source in "
                                + "synapse.data.stores.books.jpa.properties."));
    }

    /** Boot's {@code generate-ddl} would ask Hibernate for {@code update}; Synapse doesn't silently ignore it. */
    @Test
    void generateDdlFails() {
        runner
                .withPropertyValues(store("generate-ddl"))
                .withPropertyValues("synapse.data.stores.books.jpa.generate-ddl=true",
                        "synapse.data.stores.books.allow-ddl-auto=true")
                .run(context -> assertFailure(context,
                        "The store 'books' sets synapse.data.stores.books.jpa.generate-ddl, which Synapse doesn't use "
                                + "to change the schema.",
                        "Use synapse.data.stores.books.jpa.hibernate.ddl-auto instead."));
        runner
                .withPropertyValues(store("generate-ddl-off"))
                .withPropertyValues("synapse.data.stores.books.jpa.generate-ddl=false")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * Hibernate's JPA action keys win over {@code hibernate.hbm2ddl.auto}, and Hibernate also reads them from system
     * properties and {@code hibernate.properties}, which the guard never sees: the store's own settings set every one
     * of them to none.
     */
    @Test
    void hibernateGetsNoneForEveryActionKey() {
        runner.withPropertyValues(store("none-keys")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context
                    .getBean("&booksEntityManagerFactory", LocalContainerEntityManagerFactoryBean.class)
                    .getJpaPropertyMap())
                    .containsEntry("hibernate.hbm2ddl.auto", "none")
                    .containsEntry("jakarta.persistence.schema-generation.database.action", "none")
                    .containsEntry("jakarta.persistence.schema-generation.scripts.action", "none");
        });
    }

    /** A failed DDL statement of an allowed action fails startup; validate and none need no such setting. */
    @Test
    void schemaChangingActionsHaltOnError() {
        for (String value : new String[]{"create", "create-drop", "update"}) {
            runner
                    .withPropertyValues(store("halt-" + value))
                    .withPropertyValues("synapse.data.stores.books.jpa.hibernate.ddl-auto=" + value,
                            "synapse.data.stores.books.allow-ddl-auto=true",
                            "synapse.data.stores.books.migrations.enabled=false")
                    .run(context -> assertThat(vendorProperties(context))
                            .as(value)
                            .containsEntry("hibernate.hbm2ddl.halt_on_error", "true"));
        }
        for (String value : new String[]{"validate", "none"}) {
            runner
                    .withPropertyValues(store("halt-" + value))
                    .withPropertyValues("synapse.data.stores.books.jpa.hibernate.ddl-auto=" + value)
                    .run(context -> assertThat(vendorProperties(context))
                            .as(value)
                            .doesNotContainKey("hibernate.hbm2ddl.halt_on_error"));
        }
    }

    /** The guard is the same in every environment: no environment name allows a schema-changing value. */
    @Test
    void noEnvironmentNameIsConsulted() {
        runner
                .withPropertyValues(store("profile"))
                .withPropertyValues("spring.profiles.active=local",
                        "synapse.data.stores.books.jpa.hibernate.ddl-auto=update")
                .run(context -> assertFailure(context,
                        "The store 'books' sets ddl-auto to update, which changes the schema at startup.",
                        "Use migrations in db/migration/books, or allow it for one environment with "
                                + "synapse.data.stores.books.allow-ddl-auto: true in application-local.yaml or a test "
                                + "file."));
    }

    private static void assertFailure(AssertableApplicationContext context, String description, String action) {
        assertThat(context).hasFailed();
        Throwable root = NestedExceptionUtils.getMostSpecificCause(Objects.requireNonNull(context.getStartupFailure()));
        assertThat(root).isInstanceOfSatisfying(SynapseConfigurationException.class, failure -> {
            assertThat(failure.description()).isEqualTo(description);
            assertThat(failure.action()).isEqualTo(action);
        });
    }

    private static Map<String, Object> vendorProperties(AssertableApplicationContext context) {
        return context.getBean(JpaStoreFactory.BEAN_NAME, JpaStoreFactory.class).vendorProperties("books");
    }

    private static JpaSchemaAction schemaAction(AssertableApplicationContext context) {
        return context.getBean("booksSchemaAction", JpaSchemaAction.class);
    }

    private static SynapseDataStoreProperties properties() {
        return StoreSettings.bind(new MockEnvironment(), "books");
    }

    private static String[] store(String database) {
        return new String[]{"synapse.data.stores.books.url=jdbc:h2:mem:ddl-" + database + ";DB_CLOSE_DELAY=-1",
                "synapse.data.stores.books.username=sa", "synapse.data.stores.books.password="};
    }
}
