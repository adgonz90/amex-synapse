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

import java.util.Objects;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizationAutoConfiguration;
import org.springframework.core.NestedExceptionUtils;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.data.autoconfigure.SynapseDataAutoConfiguration;
import io.americanexpress.synapse.data.jpa.autoconfigure.SynapseDataJpaAutoConfiguration;
import io.americanexpress.synapse.data.jpa.fixture.H2TestVendor;
import io.americanexpress.synapse.data.jpa.fixture.books.BooksStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I1: a JPA or Hibernate connection key in a store's {@code jpa.properties} would move the store's
 * EntityManagerFactory off the store's pool (past the vendor and credential checks), and Hibernate would log its URL.
 * Each fails startup, naming the key, never its value.
 */
@ExtendWith(OutputCaptureExtension.class)
class JpaConnectionKeysTest {

    private static final String ELSEWHERE = "jdbc:h2:mem:elsewhere;DB_CLOSE_DELAY=-1";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations
                    .of(SynapseCoreAutoConfiguration.class, SynapseDataAutoConfiguration.class,
                            SynapseDataJpaAutoConfiguration.class, DataSourceAutoConfiguration.class,
                            HibernateJpaAutoConfiguration.class,
                            TransactionManagerCustomizationAutoConfiguration.class,
                            TransactionAutoConfiguration.class))
            .withPropertyValues("spring.data.jpa.repositories.enabled=false",
                    "synapse.data.stores.books.url=jdbc:h2:mem:connection-keys;DB_CLOSE_DELAY=-1",
                    "synapse.data.stores.books.username=sa", "synapse.data.stores.books.password=",
                    "synapse.data.stores.books.migrations.enabled=false",
                    "synapse.data.stores.books.jpa.hibernate.ddl-auto=none")
            .withUserConfiguration(H2TestVendor.class, BooksStore.class);

    /** The reviewer's probe: without the guard, the store's EntityManagerFactory ran on another database. */
    @ParameterizedTest
    @ValueSource(strings = {"jakarta.persistence.jdbc.url", "hibernate.connection.url"})
    void aConnectionUrlKeyDoesNotMoveTheStoreOffItsPool(String key, CapturedOutput output) {
        runner
                .withPropertyValues("synapse.data.stores.books.jpa.properties[" + key + "]=" + ELSEWHERE,
                        "synapse.data.stores.books.jpa.properties[jakarta.persistence.jdbc.user]=sa")
                .run(context -> {
                    assertThat(context)
                            .as(() -> "started, and the store's EntityManagerFactory ran on " + database(context))
                            .hasFailed();
                    assertFailure(context, "synapse.data.stores.books.jpa.properties." + key, "url");
                });
        assertThat(output.getAll()).doesNotContainIgnoringCase("elsewhere");
    }

    @ParameterizedTest
    @CsvSource({"jakarta.persistence.jdbc.url,url", "jakarta.persistence.jdbc.user,username",
            "jakarta.persistence.jdbc.password,password", "jakarta.persistence.jdbc.driver,driver-class-name",
            "javax.persistence.jdbc.url,url", "javax.persistence.jdbc.user,username",
            "javax.persistence.jdbc.password,password", "javax.persistence.jdbc.driver,driver-class-name",
            "hibernate.connection.url,url", "hibernate.connection.username,username",
            "hibernate.connection.user,username", "hibernate.connection.password,password",
            "hibernate.connection.driver_class,driver-class-name", "hibernate.connection.provider_class,url",
            "hibernate.connection.datasource,url", "jakarta.persistence.nonJtaDataSource,url",
            "jakarta.persistence.jtaDataSource,url", "javax.persistence.nonJtaDataSource,url",
            "javax.persistence.jtaDataSource,url", "Hibernate.Connection.URL,url",
            "hibernate.multi_tenant_connection_provider,url", "hibernate.multi_tenant.schema_mapper,url",
            "hibernate.multi_tenant.credentials_mapper,username"})
    void connectionKeysFail(String key, String storeKey, CapturedOutput output) {
        runner
                .withPropertyValues("synapse.data.stores.books.jpa.properties[" + key + "]=s3cr3t-value")
                .run(context -> assertFailure(context, "synapse.data.stores.books.jpa.properties." + key, storeKey));
        assertThat(output.getAll()).doesNotContain("s3cr3t-value");
    }

    /** The whole {@code jakarta.persistence.jdbc.*} family: JPA defines no tuning key in it. */
    @Test
    void everyJpaJdbcKeyFails() {
        runner
                .withPropertyValues("synapse.data.stores.books.jpa.properties[jakarta.persistence.jdbc.other]=x")
                .run(context -> assertFailure(context,
                        "synapse.data.stores.books.jpa.properties.jakarta.persistence.jdbc.other", "url"));
    }

    /**
     * Hibernate merges its global settings (system properties, a class-path {@code hibernate.properties}) into every
     * factory's, and honours a connection provider or credentials there JVM-wide, so those fail too, naming the key,
     * never its value.
     */
    @ParameterizedTest
    @CsvSource({"hibernate.connection.provider_class,url", "hibernate.connection.username,username",
            "hibernate.connection.user,username", "hibernate.connection.password,password",
            "jakarta.persistence.jdbc.user,username", "jakarta.persistence.jdbc.password,password",
            "javax.persistence.jdbc.user,username", "javax.persistence.jdbc.password,password",
            "hibernate.multi_tenant_connection_provider,url", "hibernate.multi_tenant.schema_mapper,url",
            "hibernate.multi_tenant.credentials_mapper,username"})
    void globalConnectionKeysFail(String key, String storeKey, CapturedOutput output) {
        String earlier = System.getProperty(key);
        System.setProperty(key, "s3cr3t-value");
        try {
            runner.run(context -> {
                assertThat(context).hasFailed();
                Throwable root = NestedExceptionUtils
                        .getMostSpecificCause(Objects.requireNonNull(context.getStartupFailure()));
                assertThat(root).isInstanceOfSatisfying(SynapseConfigurationException.class, failure -> {
                    assertThat(failure.description())
                            .isEqualTo("Hibernate's global settings (system properties or hibernate.properties) set "
                                    + key + ", which would replace the " + storeKey + " of the store 'books'.");
                    assertThat(failure.action())
                            .isEqualTo("Remove " + key + " from the JVM's system properties and hibernate.properties, "
                                    + "and set synapse.data.stores.books." + storeKey + " instead.");
                });
            });
        } finally {
            if (earlier == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, earlier);
            }
        }
        assertThat(output.getAll()).doesNotContain("s3cr3t-value");
    }

    /** Hibernate's connection tuning keys don't say where to connect, so they stay allowed. */
    @Test
    void connectionTuningKeysStayAllowed() {
        runner
                .withPropertyValues(
                        "synapse.data.stores.books.jpa.properties[hibernate.connection.provider_disables_autocommit]"
                                + "=false",
                        "synapse.data.stores.books.jpa.properties[hibernate.connection.handling_mode]"
                                + "=DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION",
                        "synapse.data.stores.books.jpa.properties[hibernate.connection.isolation]=2")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(database(context)).isEqualTo("CONNECTION-KEYS");
                });
    }

    private static void assertFailure(AssertableApplicationContext context, String key, String storeKey) {
        assertThat(context).hasFailed();
        Throwable root = NestedExceptionUtils.getMostSpecificCause(Objects.requireNonNull(context.getStartupFailure()));
        assertThat(root).isInstanceOfSatisfying(SynapseConfigurationException.class, failure -> {
            assertThat(failure.description())
                    .isEqualTo("The store 'books' sets " + key + ", which would replace its " + storeKey + ".");
            assertThat(failure.action())
                    .isEqualTo("Remove " + key + " and set synapse.data.stores.books." + storeKey + " instead.");
            assertThat(failure.description() + failure.action()).doesNotContain("s3cr3t", "elsewhere");
        });
    }

    private static Object database(AssertableApplicationContext context) {
        EntityManager entityManager = context
                .getBean("booksEntityManagerFactory", EntityManagerFactory.class)
                .createEntityManager();
        try {
            return entityManager.createNativeQuery("select database()").getSingleResult();
        } finally {
            entityManager.close();
        }
    }
}
