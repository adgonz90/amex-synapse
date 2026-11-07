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

import org.hibernate.boot.model.naming.ImplicitNamingStrategy;
import org.hibernate.boot.model.naming.ImplicitNamingStrategyJpaCompliantImpl;
import org.hibernate.boot.model.naming.PhysicalNamingStrategy;
import org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.annotation.Order;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.data.autoconfigure.SynapseDataAutoConfiguration;
import io.americanexpress.synapse.data.jpa.autoconfigure.SynapseDataJpaAutoConfiguration;
import io.americanexpress.synapse.data.jpa.fixture.H2TestVendor;
import io.americanexpress.synapse.data.jpa.fixture.books.BooksStore;
import io.americanexpress.synapse.data.jpa.fixture.legacy.LegacyStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I4 (provisional): a JPA store applies the application's {@link PhysicalNamingStrategy},
 * {@link ImplicitNamingStrategy} and {@link HibernatePropertiesCustomizer} beans, as Boot's own JPA configuration does,
 * with the qualifier rules of the JDBC stores: the store's own {@code <prefix><Type>} bean, else a bean qualified with
 * the store's name, else the unqualified one (the primary among several, and several without one fail) for the naming
 * strategies, and every applicable one for the customizers, as Boot applies them all; a bean with
 * another qualifier is never used. The guards run again on what the customizers return.
 */
@ExtendWith(OutputCaptureExtension.class)
class JpaHibernateCustomizationTest {

    private static final String PHYSICAL = "hibernate.physical_naming_strategy";

    private static final String IMPLICIT = "hibernate.implicit_naming_strategy";

    private static final PhysicalNamingStrategy UNQUALIFIED = new PhysicalNamingStrategyStandardImpl();

    private static final PhysicalNamingStrategy SECOND = new PhysicalNamingStrategyStandardImpl();

    private static final PhysicalNamingStrategy BOOKS = new PhysicalNamingStrategyStandardImpl();

    private static final PhysicalNamingStrategy LEGACY = new PhysicalNamingStrategyStandardImpl();

    private static final PhysicalNamingStrategy OWN = new PhysicalNamingStrategyStandardImpl();

    private static final ImplicitNamingStrategy IMPLICIT_STRATEGY = new ImplicitNamingStrategyJpaCompliantImpl();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations
                    .of(SynapseCoreAutoConfiguration.class, SynapseDataAutoConfiguration.class,
                            SynapseDataJpaAutoConfiguration.class, DataSourceAutoConfiguration.class,
                            HibernateJpaAutoConfiguration.class,
                            TransactionManagerCustomizationAutoConfiguration.class,
                            TransactionAutoConfiguration.class))
            .withPropertyValues("spring.data.jpa.repositories.enabled=false")
            .withPropertyValues(store("books"))
            .withUserConfiguration(H2TestVendor.class, BooksStore.class);

    private final ApplicationContextRunner booksAndLegacy = runner
            .withUserConfiguration(LegacyStore.class)
            .withPropertyValues(store("legacy"));

    @Test
    void anUnqualifiedNamingStrategyApplies() {
        runner.withUserConfiguration(UnqualifiedPhysical.class, Implicit.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(hibernateProperties(context, "books"))
                    .containsEntry(PHYSICAL, UNQUALIFIED)
                    .containsEntry(IMPLICIT, IMPLICIT_STRATEGY);
        });
    }

    @Test
    void aStoreQualifiedBeanWinsForItsStore() {
        booksAndLegacy.withUserConfiguration(UnqualifiedPhysical.class, BooksPhysical.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(hibernateProperties(context, "books")).containsEntry(PHYSICAL, BOOKS);
            assertThat(hibernateProperties(context, "legacy")).containsEntry(PHYSICAL, UNQUALIFIED);
        });
    }

    @Test
    void theStoresOwnBeanWins() {
        booksAndLegacy
                .withUserConfiguration(UnqualifiedPhysical.class, BooksPhysical.class, OwnPhysical.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(hibernateProperties(context, "books")).containsEntry(PHYSICAL, OWN);
                });
    }

    @Test
    void thePrimaryAmongSeveralUnqualifiedBeansApplies() {
        runner.withUserConfiguration(UnqualifiedPhysical.class, PrimaryPhysical.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(hibernateProperties(context, "books")).containsEntry(PHYSICAL, SECOND);
        });
    }

    @Test
    void severalUnqualifiedBeansWithoutAPrimaryFail() {
        // Two stores, so Boot's own JPA configuration (which also looks the beans up) stays off.
        booksAndLegacy
                .withUserConfiguration(UnqualifiedPhysical.class, SecondPhysical.class)
                .run(context -> assertFailure(context,
                        "The store 'books' finds more than one PhysicalNamingStrategy bean: secondPhysical, "
                                + "unqualifiedPhysical.",
                        "Mark one of them @Primary, or define booksPhysicalNamingStrategy in the store's "
                                + "@SynapseDataStore class."));
    }

    @Test
    void aBeanWithAnotherQualifierIsIgnored() {
        runner.withUserConfiguration(LegacyPhysical.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(hibernateProperties(context, "books"))
                    .containsEntry(PHYSICAL, "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl");
        });
    }

    /**
     * Every customizer that applies to a store runs, as Boot runs every one: an unqualified one for every store, and
     * one qualified with a store for that store only.
     */
    @Test
    void everyApplicableCustomizerApplies() {
        booksAndLegacy.withUserConfiguration(BatchCustomizer.class, LegacyFetchCustomizer.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(hibernateProperties(context, "books"))
                    .containsEntry("hibernate.jdbc.batch_size", "25")
                    .doesNotContainKey("hibernate.jdbc.fetch_size");
            assertThat(hibernateProperties(context, "legacy"))
                    .containsEntry("hibernate.jdbc.fetch_size", "50")
                    .containsEntry("hibernate.jdbc.batch_size", "25");
        });
    }

    @Test
    void twoUnqualifiedCustomizersBothApply() {
        runner.withUserConfiguration(BatchCustomizer.class, SecondCustomizer.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(hibernateProperties(context, "books"))
                    .containsEntry("hibernate.jdbc.batch_size", "25")
                    .containsEntry("hibernate.order_updates", "true");
        });
    }

    /**
     * The unqualified customizers, then those qualified with the store, then the store's own, each group in
     * {@code @Order} order: a later one sees, and may replace, what an earlier one set, so the most specific wins.
     */
    @Test
    void customizersRunUnqualifiedThenQualifiedThenOwnInOrder() {
        runner
                .withUserConfiguration(OrderedCustomizers.class, BooksQualifiedCustomizer.class,
                        OwnCustomizer.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(hibernateProperties(context, "books"))
                            .containsEntry("synapse.test.order", "first,second,books,own");
                });
    }

    /** On a key both set, the customizer qualified with the store wins over the unqualified one: it runs later. */
    @Test
    void aStoreQualifiedCustomizerWinsOverAnUnqualifiedOneOnTheSameKey() {
        booksAndLegacy.withUserConfiguration(BatchCustomizer.class, BooksBatchCustomizer.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(hibernateProperties(context, "books")).containsEntry("hibernate.jdbc.batch_size", "99");
            assertThat(hibernateProperties(context, "legacy")).containsEntry("hibernate.jdbc.batch_size", "25");
        });
    }

    @Test
    void aCustomizerWithAnotherQualifierIsNeverApplied() {
        runner.withUserConfiguration(LegacyFetchCustomizer.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(hibernateProperties(context, "books")).doesNotContainKey("hibernate.jdbc.fetch_size");
        });
    }

    /** The guards run on the combined result: one customizer among several that sets a guarded key still fails. */
    @Test
    void oneOfSeveralCustomizersSettingAGuardedKeyFails() {
        runner
                .withUserConfiguration(BatchCustomizer.class, SecondCustomizer.class)
                .withBean("schemaCustomizer", HibernatePropertiesCustomizer.class,
                        () -> properties -> properties.put("hibernate.hbm2ddl.auto", "update"))
                .run(context -> assertFailure(context,
                        "A HibernatePropertiesCustomizer sets hibernate.hbm2ddl.auto for the store 'books', which "
                                + "would change its schema behind its ddl-auto.",
                        "Remove hibernate.hbm2ddl.auto from the customizer, and use "
                                + "synapse.data.stores.books.jpa.hibernate.ddl-auto instead."));
    }

    /** A customizer can't bypass the ddl-auto guard: the guard runs again on the properties it returns. */
    @ParameterizedTest
    @CsvSource({"hibernate.hbm2ddl.auto", "hibernate.hbm2ddl.auto.orm",
            "jakarta.persistence.schema-generation.database.action"})
    void aCustomizerCantSetASchemaKey(String key, CapturedOutput output) {
        runner
                .withPropertyValues("synapse.data.stores.books.allow-ddl-auto=true")
                .withBean(HibernatePropertiesCustomizer.class, () -> properties -> properties.put(key, "update"))
                .run(context -> assertFailure(context,
                        "A HibernatePropertiesCustomizer sets " + key + " for the store 'books', which would change "
                                + "its schema behind its ddl-auto.",
                        "Remove " + key + " from the customizer, and use "
                                + "synapse.data.stores.books.jpa.hibernate.ddl-auto instead."));
    }

    /** A customizer can't bypass the connection-key guard either; the failure names the key, never the value. */
    @ParameterizedTest
    @CsvSource({"jakarta.persistence.jdbc.url,url", "hibernate.connection.password,password"})
    void aCustomizerCantSetAConnectionKey(String key, String storeKey, CapturedOutput output) {
        runner
                .withBean(HibernatePropertiesCustomizer.class,
                        () -> properties -> properties.put(key, "jdbc:h2:mem:s3cr3t"))
                .run(context -> assertFailure(context,
                        "A HibernatePropertiesCustomizer sets " + key + " for the store 'books', which would replace "
                                + "its " + storeKey + ".",
                        "Remove " + key + " from the customizer, and set synapse.data.stores.books." + storeKey
                                + " instead."));
        assertThat(output.getAll()).doesNotContain("s3cr3t");
    }

    /** What Hibernate receives: the persistence unit's properties. */
    private static Map<String, Object> hibernateProperties(AssertableApplicationContext context, String store) {
        return context
                .getBean("&" + store + "EntityManagerFactory", LocalContainerEntityManagerFactoryBean.class)
                .getJpaPropertyMap();
    }

    private static void assertFailure(AssertableApplicationContext context, String description, String action) {
        assertThat(context).hasFailed();
        Throwable root = NestedExceptionUtils.getMostSpecificCause(Objects.requireNonNull(context.getStartupFailure()));
        assertThat(root).isInstanceOfSatisfying(SynapseConfigurationException.class, failure -> {
            assertThat(failure.description()).isEqualTo(description);
            assertThat(failure.action()).isEqualTo(action);
        });
    }

    private static String[] store(String name) {
        return new String[]{"synapse.data.stores." + name + ".url=jdbc:h2:mem:custom-" + name + ";DB_CLOSE_DELAY=-1",
                "synapse.data.stores." + name + ".username=sa", "synapse.data.stores." + name + ".password=",
                "synapse.data.stores." + name + ".migrations.enabled=false",
                "synapse.data.stores." + name + ".jpa.hibernate.ddl-auto=none"};
    }

    @Configuration(proxyBeanMethods = false)
    static class UnqualifiedPhysical {

        @Bean
        PhysicalNamingStrategy unqualifiedPhysical() {
            return UNQUALIFIED;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SecondPhysical {

        @Bean
        PhysicalNamingStrategy secondPhysical() {
            return SECOND;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PrimaryPhysical {

        @Bean
        @Primary
        PhysicalNamingStrategy secondPhysical() {
            return SECOND;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class BooksPhysical {

        @Bean
        @Qualifier("books")
        PhysicalNamingStrategy booksPhysical() {
            return BOOKS;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LegacyPhysical {

        @Bean
        @Qualifier("legacy")
        PhysicalNamingStrategy legacyPhysical() {
            return LEGACY;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnPhysical {

        @Bean
        PhysicalNamingStrategy booksPhysicalNamingStrategy() {
            return OWN;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Implicit {

        @Bean
        ImplicitNamingStrategy implicitStrategy() {
            return IMPLICIT_STRATEGY;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class BatchCustomizer {

        @Bean
        HibernatePropertiesCustomizer batchCustomizer() {
            return properties -> properties.put("hibernate.jdbc.batch_size", "25");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SecondCustomizer {

        @Bean
        HibernatePropertiesCustomizer secondCustomizer() {
            return properties -> properties.put("hibernate.order_updates", "true");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LegacyFetchCustomizer {

        @Bean
        @Qualifier("legacy")
        HibernatePropertiesCustomizer legacyFetchCustomizer() {
            return properties -> properties.put("hibernate.jdbc.fetch_size", "50");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OrderedCustomizers {

        @Bean
        @Order(2)
        HibernatePropertiesCustomizer secondInOrder() {
            return properties -> append(properties, "second");
        }

        @Bean
        @Order(1)
        HibernatePropertiesCustomizer firstInOrder() {
            return properties -> append(properties, "first");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class BooksQualifiedCustomizer {

        @Bean
        @Qualifier("books")
        HibernatePropertiesCustomizer booksCustomizer() {
            return properties -> append(properties, "books");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnCustomizer {

        @Bean
        HibernatePropertiesCustomizer booksHibernatePropertiesCustomizer() {
            return properties -> append(properties, "own");
        }
    }

    /** Records which customizers ran, in their order. */
    private static void append(Map<String, Object> properties, String customizer) {
        properties.merge("synapse.test.order", customizer, (ran, next) -> ran + "," + next);
    }

    @Configuration(proxyBeanMethods = false)
    static class BooksBatchCustomizer {

        @Bean
        @Qualifier("books")
        HibernatePropertiesCustomizer booksBatchCustomizer() {
            return properties -> properties.put("hibernate.jdbc.batch_size", "99");
        }
    }
}
