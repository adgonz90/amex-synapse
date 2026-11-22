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
package io.americanexpress.synapse.data.postgres.autoconfigure;

import java.util.Map;
import jakarta.persistence.EntityManagerFactory;

import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.core.dialect.JdbcPostgresDialect;

import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.data.autoconfigure.SynapseDataAutoConfiguration;
import io.americanexpress.synapse.data.jdbc.autoconfigure.SynapseDataJdbcAutoConfiguration;
import io.americanexpress.synapse.data.jpa.autoconfigure.SynapseDataJpaAutoConfiguration;
import io.americanexpress.synapse.data.jpa.internal.JpaStoreFactory;
import io.americanexpress.synapse.data.postgres.PostgresVendor;
import io.americanexpress.synapse.data.postgres.fixture.books.BooksStore;
import io.americanexpress.synapse.data.postgres.fixture.legacy.LegacyStore;
import io.americanexpress.synapse.data.vendor.DataStoreVendor;

import static org.assertj.core.api.Assertions.assertThat;

class SynapsePostgresAutoConfigurationTest {

    private static final String VENDOR = "postgresDataStoreVendor";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SynapsePostgresAutoConfiguration.class));

    /**
     * Stores on PostgreSQL that nothing connects to: the port is closed, and neither migrations nor a schema action
     * run when the context starts, so only bean creation is exercised.
     */
    private final ApplicationContextRunner stores = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations
                    .of(SynapseCoreAutoConfiguration.class, SynapseDataAutoConfiguration.class,
                            SynapseDataJpaAutoConfiguration.class, SynapseDataJdbcAutoConfiguration.class,
                            SynapsePostgresAutoConfiguration.class,
                            TransactionManagerCustomizationAutoConfiguration.class,
                            TransactionAutoConfiguration.class));

    private final ApplicationContextRunner books = stores
            .withUserConfiguration(BooksStore.class)
            .withPropertyValues(unreachable("books"))
            .withPropertyValues("synapse.data.stores.books.jpa.hibernate.ddl-auto=none");

    @Test
    void contributesTheVendor() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(DataStoreVendor.class).hasBean(VENDOR);
            DataStoreVendor vendor = context.getBean(VENDOR, DataStoreVendor.class);
            assertThat(vendor).isExactlyInstanceOf(PostgresVendor.class);
            assertThat(vendor.supports("jdbc:postgresql://h/db")).isTrue();
            assertThat(vendor.supports("jdbc:h2:mem:x")).isFalse();
            assertThat(vendor.name()).isEqualTo("postgres");
            assertThat(vendor.defaultDatabaseVersion()).isEqualTo("15");
            assertThat(vendor.databaseProductName()).isEqualTo("PostgreSQL");
            assertThat(vendor.hibernateDialect()).isEqualTo("org.hibernate.dialect.PostgreSQLDialect");
            assertThat(vendor.jdbcDialect())
                    .isEqualTo("org.springframework.data.jdbc.core.dialect.JdbcPostgresDialect");
        });
    }

    /** Every URL form the PostgreSQL driver accepts starts with {@code jdbc:postgresql:}, which the driver checks. */
    @ParameterizedTest
    @ValueSource(strings = {"jdbc:postgresql://h/db", "jdbc:postgresql://h:5432/db", "jdbc:postgresql:db",
            "jdbc:postgresql:/", "jdbc:postgresql://h1:5432,h2:5432/db?targetServerType=primary",
            "jdbc:postgresql://[::1]:5432/db", "jdbc:postgresql:///db"})
    void supportsThePostgresqlScheme(String url) {
        assertThat(new PostgresVendor().supports(url)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:h2:mem:x", "jdbc:postgres://h/db", "jdbc:pgsql://h/db", "jdbc:postgresql",
            "jdbc:tc:postgresql:18:///db", "JDBC:POSTGRESQL://h/db", "postgresql://h/db", ""})
    void supportsNoOtherUrl(String url) {
        assertThat(new PostgresVendor().supports(url)).isFalse();
    }

    @Test
    void backsOffToAUserVendor() {
        runner.withUserConfiguration(UserVendor.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(DataStoreVendor.class);
            assertThat(context.getBean(VENDOR)).isSameAs(UserVendor.VENDOR);
        });
    }

    /** It backs off by name: a vendor bean of another name is another database's. */
    @Test
    void anotherVendorKeepsIt() {
        runner.withUserConfiguration(OtherVendor.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(DataStoreVendor.class)).containsOnlyKeys(VENDOR, "otherVendor");
            assertThat(context.getBean(VENDOR)).isInstanceOf(PostgresVendor.class);
        });
    }

    @Test
    void absentWithoutTheDriver() {
        runner
                .withClassLoader(new FilteredClassLoader(org.postgresql.Driver.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(VENDOR));
    }

    @Test
    void isAnAutoConfiguration() {
        assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
                .contains(SynapsePostgresAutoConfiguration.class.getName());
    }

    /**
     * Hibernate resolves the dialect from the vendor's product name, PostgreSQL, at the vendor's default version, or
     * at the store's {@code database-version}; no connection is opened.
     */
    @Test
    void storeOnPostgresGetsTheDialectAndVersion() {
        books.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(vendorProperties(context))
                    .containsEntry("jakarta.persistence.database-product-name", "PostgreSQL")
                    .containsEntry("jakarta.persistence.database-product-version", "15")
                    .containsEntry("jakarta.persistence.database-major-version", "15")
                    .containsEntry("jakarta.persistence.database-minor-version", "0")
                    .doesNotContainKey("hibernate.dialect");
            Dialect dialect = dialect(context);
            assertThat(dialect).isExactlyInstanceOf(PostgreSQLDialect.class);
            assertThat(dialect.getVersion().getMajor()).isEqualTo(15);
        });
        books
                .withPropertyValues("synapse.data.stores.books.database-version=17")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(vendorProperties(context))
                            .containsEntry("jakarta.persistence.database-product-version", "17")
                            .containsEntry("jakarta.persistence.database-major-version", "17");
                    Dialect dialect = dialect(context);
                    assertThat(dialect).isExactlyInstanceOf(PostgreSQLDialect.class);
                    assertThat(dialect.getVersion().getMajor()).isEqualTo(17);
                });
    }

    /** A JDBC store on PostgreSQL gets Spring Data JDBC's PostgreSQL dialect, read from its INSTANCE field. */
    @Test
    void jdbcStoreOnPostgresGetsThePostgresDialect() {
        stores.withUserConfiguration(LegacyStore.class).withPropertyValues(unreachable("legacy")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("legacyJdbcDialect")).isSameAs(JdbcPostgresDialect.INSTANCE);
        });
    }

    private static Map<String, Object> vendorProperties(AssertableApplicationContext context) {
        return context.getBean(JpaStoreFactory.BEAN_NAME, JpaStoreFactory.class).vendorProperties("books");
    }

    private static Dialect dialect(AssertableApplicationContext context) {
        return context
                .getBean("booksEntityManagerFactory", EntityManagerFactory.class)
                .unwrap(SessionFactoryImplementor.class)
                .getJdbcServices()
                .getDialect();
    }

    private static String[] unreachable(String store) {
        String prefix = "synapse.data.stores." + store;
        return new String[]{prefix + ".url=jdbc:postgresql://127.0.0.1:1/" + store, prefix + ".username=app",
                prefix + ".password=", prefix + ".migrations.enabled=false"};
    }

    @Configuration(proxyBeanMethods = false)
    static class UserVendor {

        static final DataStoreVendor VENDOR = new PostgresVendor();

        @Bean
        DataStoreVendor postgresDataStoreVendor() {
            return VENDOR;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OtherVendor {

        @Bean
        DataStoreVendor otherVendor() {
            return new DataStoreVendor() {

                @Override
                public String name() {
                    return "other";
                }

                @Override
                public boolean supports(String jdbcUrl) {
                    return jdbcUrl.startsWith("jdbc:other:");
                }

                @Override
                public String defaultDatabaseVersion() {
                    return "1";
                }

                @Override
                public String hibernateDialect() {
                    return "org.hibernate.dialect.H2Dialect";
                }

                @Override
                public String jdbcDialect() {
                    return "org.springframework.data.jdbc.core.dialect.JdbcH2Dialect";
                }
            };
        }
    }
}
