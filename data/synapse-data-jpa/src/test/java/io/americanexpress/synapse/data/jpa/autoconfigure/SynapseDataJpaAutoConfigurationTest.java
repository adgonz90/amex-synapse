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
package io.americanexpress.synapse.data.jpa.autoconfigure;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.spi.PersistenceUnitInfo;

import com.zaxxer.hikari.HikariDataSource;
import org.hibernate.JDBCException;
import org.hibernate.StaleStateException;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SessionImplementor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.BeanFactoryAnnotationUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.support.SQLExceptionTranslator;
import org.springframework.orm.jpa.JpaDialect;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.hibernate.SpringBeanContainer;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.core.error.ExceptionTranslator;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.data.CommitOutcomeUnknownException;
import io.americanexpress.synapse.data.DataErrors;
import io.americanexpress.synapse.data.autoconfigure.SynapseDataAutoConfiguration;
import io.americanexpress.synapse.data.internal.store.StoreFactory;
import io.americanexpress.synapse.data.jpa.SynapseJpaTransactionManager;
import io.americanexpress.synapse.data.jpa.fixture.CustomH2Dialect;
import io.americanexpress.synapse.data.jpa.fixture.H2TestVendor;
import io.americanexpress.synapse.data.jpa.fixture.books.Book;
import io.americanexpress.synapse.data.jpa.fixture.books.BooksStore;
import io.americanexpress.synapse.data.jpa.fixture.legacy.LegacyItem;
import io.americanexpress.synapse.data.jpa.fixture.legacy.LegacyStore;
import io.americanexpress.synapse.data.jpa.fixture.ownemf.BooksStoreWithOwnEntityManagerFactory;
import io.americanexpress.synapse.data.jpa.fixture.ownschemaaction.BooksStoreWithOwnSchemaAction;
import io.americanexpress.synapse.data.jpa.fixture.owntm.BooksStoreWithOwnTransactionManager;
import io.americanexpress.synapse.data.jpa.fixture.synapsetm.BooksStoreWithSynapseTransactionManager;
import io.americanexpress.synapse.data.jpa.internal.JpaExceptionTranslator;
import io.americanexpress.synapse.data.jpa.internal.JpaSchemaAction;
import io.americanexpress.synapse.data.jpa.internal.JpaStoreFactory;
import io.americanexpress.synapse.data.vendor.DataStoreVendor;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class SynapseDataJpaAutoConfigurationTest {

    private static final String FACTORY = "synapseJpaStoreFactory";

    private static final String TRANSLATOR = "synapseJpaExceptionTranslator";

    private static final String[] BOOKS_BEANS = {"booksPersistenceManagedTypes", "booksEntityManagerFactory",
            "booksTransactionManager"};

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations
                    .of(SynapseCoreAutoConfiguration.class, SynapseDataAutoConfiguration.class,
                            SynapseDataJpaAutoConfiguration.class,
                            DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
                            DataJpaRepositoriesAutoConfiguration.class,
                            TransactionManagerCustomizationAutoConfiguration.class,
                            TransactionAutoConfiguration.class))
            // What JpaDefaultsEnvironmentPostProcessor sets in an application (a context runner runs no post-processor).
            .withPropertyValues("spring.data.jpa.repositories.enabled=false")
            .withUserConfiguration(H2TestVendor.class);

    private final ApplicationContextRunner books = runner
            .withUserConfiguration(BooksStore.class)
            .withPropertyValues(store("books"));

    @Test
    void aStoreGetsAnEntityManagerFactoryAndTransactionManager() {
        books.run(context -> {
            assertThat(context).hasNotFailed();
            EntityManagerFactory entityManagerFactory = context
                    .getBean("booksEntityManagerFactory", EntityManagerFactory.class);
            LocalContainerEntityManagerFactoryBean factoryBean = context
                    .getBean("&booksEntityManagerFactory", LocalContainerEntityManagerFactoryBean.class);
            assertThat(factoryBean.getPersistenceUnitName()).isEqualTo("books");
            assertThat(factoryBean.getDataSource()).isSameAs(context.getBean("booksDataSource"));
            assertThat(context
                    .getBean("booksPersistenceManagedTypes", PersistenceManagedTypes.class)
                    .getManagedClassNames()).containsExactly(Book.class.getName());
            assertThat(entities(entityManagerFactory)).containsExactly(Book.class);
            assertThat(context.getBean("booksTransactionManager"))
                    .isInstanceOfSatisfying(SynapseJpaTransactionManager.class, transactionManager -> assertThat(
                            transactionManager.getEntityManagerFactory()).isSameAs(entityManagerFactory));
            for (String name : BOOKS_BEANS) {
                BeanDefinition definition = context.getBeanFactory().getBeanDefinition(name);
                assertThat(definition.isPrimary()).as(name).isTrue();
                assertThat(qualifier(definition)).as(name).isEqualTo("books");
            }
        });
    }

    /**
     * An auto-configuration processed before this one whose condition looks beans up by annotation, as Boot's gRPC
     * server configuration does ({@code @ConditionalOnBean(annotation = GrpcAdvice.class)}): Spring then asks every
     * definition for its type, the store's EntityManagerFactory included, before {@code synapseJpaStoreFactory}, the
     * factory bean that definition names, is defined. The definition carries its object type, so the lookup neither
     * fails nor needs the factory bean.
     */
    @Test
    void aConditionByAnnotationBeforeTheStoreFactoryIsDefinedFindsTheEntityManagerFactorysType() {
        books.withConfiguration(AutoConfigurations.of(LooksUpBeansByAnnotation.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(LooksUpBeansByAnnotation.class);
            assertThat(context.getBean("booksEntityManagerFactory")).isInstanceOf(EntityManagerFactory.class);
        });
    }

    @Test
    void bootsJpaBacksOff() {
        books.run(context -> {
            assertThat(context).doesNotHaveBean("entityManagerFactory");
            assertThat(context).doesNotHaveBean("transactionManager");
            assertThat(context).doesNotHaveBean("persistenceManagedTypes");
            assertThat(context.getBeansOfType(EntityManagerFactory.class).keySet())
                    .containsExactly("booksEntityManagerFactory");
            assertThat(context.getBeansOfType(TransactionManager.class).keySet())
                    .containsExactly("booksTransactionManager");
            // With one store's DataSource, Boot's HibernateJpaConfiguration still matches (a single candidate): its
            // vendor adapter and builder, made from spring.jpa.*, exist but build nothing of the store's.
            assertThat(context).hasBean("jpaVendorAdapter").hasBean("entityManagerFactoryBuilder");
        });
        books.withUserConfiguration(LegacyStore.class).withPropertyValues(store("legacy")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean("jpaVendorAdapter").doesNotHaveBean("entityManagerFactoryBuilder");
        });
        // A primary store's DataSource is a single candidate too, so Boot's configuration matches again.
        books
                .withUserConfiguration(LegacyStore.class)
                .withPropertyValues(store("legacy"))
                .withPropertyValues("synapse.data.stores.books.primary=true")
                .run(context -> assertThat(context).hasBean("jpaVendorAdapter").hasBean("entityManagerFactoryBuilder"));
    }

    /**
     * Minor 2: Boot applies spring.jpa.open-in-view to a single or primary store (JpaWebConfiguration), so startup
     * warns when the application sets it, naming the key, never its value.
     */
    @Test
    void warnsWhenOpenInViewIsSet(CapturedOutput output) {
        books.withPropertyValues("spring.jpa.open-in-view=true").run(started());
        assertThat(output.getOut().lines().filter(line -> line.contains("spring.jpa.")))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .contains(" WARN ", "io.americanexpress.synapse.data ")
                        .endsWith("spring.jpa.open-in-view is set. Synapse turns open-in-view off by default, since "
                                + "operations are the transaction boundary: with a single store, or a primary one, "
                                + "Boot applies the key to it and can keep an EntityManager open for a whole web "
                                + "request."));
        assertThat(output.getOut()).doesNotContain("open-in-view=true");
    }

    /** A store's jpa.mapping-resources are added to its persistence unit, as Boot adds spring.jpa.mapping-resources. */
    @Test
    void mappingResourcesAreHonoured() {
        books.withPropertyValues("synapse.data.stores.books.jpa.mapping-resources=orm/books-orm.xml").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(Objects
                    .requireNonNull(context
                            .getBean("&booksEntityManagerFactory", LocalContainerEntityManagerFactoryBean.class)
                            .getPersistenceUnitInfo())
                    .getMappingFileNames())
                    .containsExactly("io/americanexpress/synapse/data/jpa/synapse-orm.xml", "orm/books-orm.xml");
            EntityManager entityManager = context
                    .getBean("booksEntityManagerFactory", EntityManagerFactory.class)
                    .createEntityManager();
            try {
                assertThat(entityManager.createNamedQuery("Book.countAll").getSingleResult()).isEqualTo(0L);
            } finally {
                entityManager.close();
            }
        });
    }

    /** A per-store open-in-view or bootstrap would be silently ignored, so it fails startup instead. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "open-in-view=true|The store 'books' sets synapse.data.stores.books.jpa.open-in-view, but open-in-view "
                    + "is not a store setting.|Remove it. Boot's spring.jpa.open-in-view applies to a single or "
                    + "primary store, and Synapse turns it off by default.",
            "bootstrap=async|The store 'books' sets synapse.data.stores.books.jpa.bootstrap, but Synapse builds "
                    + "each store's EntityManagerFactory itself, before the store starts.|Remove it."})
    void perStoreOpenInViewAndBootstrapFail(String setting, String description, String action) {
        books.withPropertyValues("synapse.data.stores.books.jpa." + setting).run(context -> {
            assertThat(context).hasFailed();
            assertThat(NestedExceptionUtils.getMostSpecificCause(Objects.requireNonNull(context.getStartupFailure())))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, failure -> {
                        assertThat(failure.description()).isEqualTo(description);
                        assertThat(failure.action()).isEqualTo(action);
                    });
        });
    }

    /** N1: false is Synapse's own default, so setting it says nothing worth a WARN. */
    @Test
    void noWarningWhenOpenInViewIsSetToFalse(CapturedOutput output) {
        books.withPropertyValues("spring.jpa.open-in-view=FALSE ").run(started());
        assertThat(output.getOut()).doesNotContain("spring.jpa.");
    }

    /** Minor 3: the stores bind their own JPA settings, so Boot's spring.jpa.* are ignored, and startup says so. */
    @Test
    void warnsAboutSpringJpaKeys(CapturedOutput output) {
        books.withPropertyValues("spring.jpa.show-sql=true", "spring.jpa.hibernate.ddl-auto=update").run(started());
        assertThat(output.getOut().lines().filter(line -> line.contains("spring.jpa.")))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .contains(" WARN ", "io.americanexpress.synapse.data ")
                        .endsWith("spring.jpa.* is set (spring.jpa.hibernate.ddl-auto, spring.jpa.show-sql), but "
                                + "Synapse data stores don't use it. Configure each store under "
                                + "synapse.data.stores.<name>."));
        assertThat(output.getOut()).doesNotContain("=update", "=true");
    }

    @Test
    void eachStoreGetsItsOwnPersistenceUnit() {
        books.withUserConfiguration(LegacyStore.class).withPropertyValues(store("legacy")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(entities(context.getBean("booksEntityManagerFactory", EntityManagerFactory.class)))
                    .containsExactly(Book.class);
            assertThat(entities(context.getBean("legacyEntityManagerFactory", EntityManagerFactory.class)))
                    .containsExactly(LegacyItem.class);
            assertThat(context
                    .getBean("&legacyEntityManagerFactory", LocalContainerEntityManagerFactoryBean.class)
                    .getPersistenceUnitName()).isEqualTo("legacy");
            assertThat(context
                    .getBean("legacyTransactionManager", JpaTransactionManager.class)
                    .getEntityManagerFactory()).isSameAs(context.getBean("legacyEntityManagerFactory"));
            assertThat(context.getBeanFactory().getBeanDefinition("booksEntityManagerFactory").isPrimary())
                    .isFalse();
            assertThat(context.getBeanFactory().getBeanDefinition("legacyTransactionManager").isPrimary())
                    .isFalse();
        });
    }

    @Test
    void hibernateNeverReadsMetadata() {
        books.run(context -> {
            Map<String, Object> properties = vendorProperties(context);
            assertThat(properties)
                    .containsEntry("hibernate.boot.allow_jdbc_metadata_access", "false")
                    .containsEntry("jakarta.persistence.database-product-name", "H2")
                    .containsEntry("jakarta.persistence.database-product-version", "2.4")
                    .containsEntry("jakarta.persistence.database-major-version", "2")
                    .containsEntry("jakarta.persistence.database-minor-version", "4")
                    .doesNotContainKey("hibernate.dialect");
            assertThat(context
                    .getBean("&booksEntityManagerFactory", LocalContainerEntityManagerFactoryBean.class)
                    .getJpaPropertyMap())
                    .as("what Hibernate receives")
                    .containsAllEntriesOf(Map
                            .of("hibernate.boot.allow_jdbc_metadata_access", "false",
                                    "jakarta.persistence.database-product-name", "H2",
                                    "jakarta.persistence.database-product-version", "2.4", "hibernate.hbm2ddl.auto",
                                    "none"));
        });
    }

    @Test
    void hibernateResolvesTheDialectFromTheProductNameWithoutWarning(CapturedOutput output) {
        books.run(context -> {
            assertThat(context).hasNotFailed();
            Dialect dialect = dialect(context, "booksEntityManagerFactory");
            assertThat(dialect).isExactlyInstanceOf(H2Dialect.class);
            assertThat(dialect.getVersion().getMajor()).isEqualTo(2);
            assertThat(dialect.getVersion().getMinor()).isEqualTo(4);
        });
        assertThat(output).doesNotContain("HHH90000025");
    }

    @Test
    void aVendorWithoutAProductNameGivesItsDialect() {
        books.withUserConfiguration(DialectOnlyH2Vendor.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(vendorProperties(context))
                    .containsEntry("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
                    .doesNotContainKey("jakarta.persistence.database-product-name");
            assertThat(dialect(context, "booksEntityManagerFactory")).isExactlyInstanceOf(H2Dialect.class);
        });
    }

    @Test
    void aJpaDatabaseChoosesTheDialect() {
        books
                .withPropertyValues("synapse.data.stores.books.jpa.database=h2")
                .run(context -> assertThat(vendorProperties(context))
                        .containsEntry("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
                        .doesNotContainKey("jakarta.persistence.database-product-name"));
    }

    @Test
    void anExplicitProductNameWins() {
        books
                .withPropertyValues(
                        "synapse.data.stores.books.jpa.properties.jakarta.persistence.database-product-name=H2")
                .run(context -> assertThat(vendorProperties(context))
                        .containsEntry("jakarta.persistence.database-product-name", "H2")
                        .doesNotContainKey("hibernate.dialect"));
    }

    @Test
    void hibernateNeverChangesTheSchemaWhileTheFactoryIsBuilt() {
        books.run(context -> assertThat(vendorProperties(context)).containsEntry("hibernate.hbm2ddl.auto", "none"));
        // An allowed create runs when the store starts (JpaSchemaActionIT), on a database of its own.
        books
                .withPropertyValues("synapse.data.stores.books.url=jdbc:h2:mem:jpa-books-create;DB_CLOSE_DELAY=-1",
                        "synapse.data.stores.books.migrations.enabled=false",
                        "synapse.data.stores.books.allow-ddl-auto=true",
                        "synapse.data.stores.books.jpa.hibernate.ddl-auto=create")
                .run(context -> assertThat(vendorProperties(context))
                        .containsEntry("hibernate.hbm2ddl.auto", "none"));
    }

    @Test
    void databaseVersionIsConfigurable() {
        books
                .withPropertyValues("synapse.data.stores.books.database-version=2.3.232")
                .run(context -> assertThat(vendorProperties(context))
                        .containsEntry("jakarta.persistence.database-product-version", "2.3.232")
                        .containsEntry("jakarta.persistence.database-major-version", "2")
                        .containsEntry("jakarta.persistence.database-minor-version", "3"));
    }

    @Test
    void anExplicitDialectWins() {
        books
                .withPropertyValues(
                        "synapse.data.stores.books.jpa.database-platform=" + CustomH2Dialect.class.getName())
                .run(context -> {
                    assertThat(vendorProperties(context))
                            .containsEntry("hibernate.dialect", CustomH2Dialect.class.getName())
                            .doesNotContainKey("jakarta.persistence.database-product-name");
                    assertThat(dialect(context, "booksEntityManagerFactory")).isInstanceOf(CustomH2Dialect.class);
                });
        books
                .withPropertyValues(
                        "synapse.data.stores.books.jpa.properties.hibernate.dialect=" + CustomH2Dialect.class.getName())
                .run(context -> {
                    assertThat(vendorProperties(context))
                            .containsEntry("hibernate.dialect", CustomH2Dialect.class.getName())
                            .doesNotContainKey("jakarta.persistence.database-product-name");
                    assertThat(dialect(context, "booksEntityManagerFactory")).isInstanceOf(CustomH2Dialect.class);
                });
    }

    @Test
    void bindsJpaAndHibernateProperties() {
        books
                .withPropertyValues("synapse.data.stores.books.jpa.show-sql=true",
                        "synapse.data.stores.books.jpa.properties.hibernate.jdbc.batch_size=20",
                        "synapse.data.stores.books.jpa.hibernate.naming.physical-strategy="
                                + "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(vendorProperties(context))
                            .containsEntry("hibernate.show_sql", "true")
                            .containsEntry("hibernate.jdbc.batch_size", "20")
                            .containsEntry("hibernate.physical_naming_strategy",
                                    "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
                });
    }

    @Test
    void bootsJpaDefaultsApply() {
        books
                .run(context -> assertThat(vendorProperties(context))
                        .doesNotContainKey("hibernate.show_sql")
                        .containsEntry("hibernate.physical_naming_strategy",
                                "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl")
                        .containsEntry("hibernate.implicit_naming_strategy",
                                "org.springframework.boot.hibernate.SpringImplicitNamingStrategy"));
    }

    @Test
    void hibernateCreatesBeansThroughTheContext() {
        books
                .run(context -> assertThat(vendorProperties(context).get("hibernate.resource.beans.container"))
                        .isInstanceOf(SpringBeanContainer.class));
    }

    @Test
    void transactionManagerCustomizersApply() {
        books
                .withPropertyValues("spring.transaction.default-timeout=7s")
                .run(context -> assertThat(
                        context.getBean("booksTransactionManager", JpaTransactionManager.class).getDefaultTimeout())
                        .isEqualTo(7));
    }

    @Test
    void startsWithTheDatabaseUnreachable() {
        runner
                .withUserConfiguration(BooksStore.class)
                .withPropertyValues("synapse.data.stores.books.url=jdbc:h2:tcp://127.0.0.1:1/none",
                        "synapse.data.stores.books.username=sa", "synapse.data.stores.books.password=",
                        "synapse.data.stores.books.migrations.enabled=false",
                        "synapse.data.stores.books.jpa.hibernate.ddl-auto=none")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("booksEntityManagerFactory")).isInstanceOf(EntityManagerFactory.class);
                    assertThat(context).hasBean("booksTransactionManager");
                });
    }

    /**
     * Acceptance 17 at the JPA level: the database is reachable, so any connection Hibernate asked for would succeed
     * and start the pool.
     */
    @Test
    void buildingTheEntityManagerFactoryOpensNoConnection() {
        books
                .withPropertyValues("synapse.data.stores.books.migrations.enabled=false",
                        "synapse.data.stores.books.jpa.hibernate.ddl-auto=none")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("booksEntityManagerFactory")).isInstanceOf(EntityManagerFactory.class);
                    HikariDataSource dataSource = context.getBean("booksDataSource", HikariDataSource.class);
                    assertThat(dataSource.getHikariPoolMXBean()).as("the pool was never started").isNull();
                    try (Connection connection = dataSource.getConnection()) {
                        assertThat(connection.isValid(1)).as("the database is reachable").isTrue();
                    }
                });
    }

    @Test
    void aUniqueSqlExceptionTranslatorIsUsed() {
        books.withUserConfiguration(OwnSqlExceptionTranslator.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(translate(context)).isInstanceOf(TranslatedByTheApplication.class);
        });
    }

    @Test
    void severalSqlExceptionTranslatorsAreNotUsed() {
        books.withUserConfiguration(OwnSqlExceptionTranslator.class, OtherSqlExceptionTranslator.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(translate(context)).isNotNull().isNotInstanceOf(TranslatedByTheApplication.class);
        });
    }

    @Test
    void aUserEntityManagerFactoryInTheStoreClassReplacesSynapses() {
        runner
                .withUserConfiguration(BooksStoreWithOwnEntityManagerFactory.class)
                .withPropertyValues(store("books"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(EntityManagerFactory.class).keySet())
                            .containsExactly("booksEntityManagerFactory");
                    assertThat(context
                            .getBean("&booksEntityManagerFactory", LocalContainerEntityManagerFactoryBean.class)
                            .getPersistenceUnitName()).isEqualTo(BooksStoreWithOwnEntityManagerFactory.UNIT);
                    assertThat(context
                            .getBean("booksTransactionManager", JpaTransactionManager.class)
                            .getEntityManagerFactory()).isSameAs(context.getBean("booksEntityManagerFactory"));
                });
    }

    @Test
    void aUserTransactionManagerInTheStoreClassReplacesSynapses() {
        runner
                .withUserConfiguration(BooksStoreWithOwnTransactionManager.class)
                .withPropertyValues(store("books"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(PlatformTransactionManager.class).keySet())
                            .containsExactly("booksTransactionManager");
                    assertThat(context
                            .getBean("booksTransactionManager", JpaTransactionManager.class)
                            .getDefaultTimeout()).isEqualTo(BooksStoreWithOwnTransactionManager.TIMEOUT);
                    assertThat(context).hasBean("booksEntityManagerFactory");
                });
    }

    /**
     * A store whose transaction manager is a plain {@code JpaTransactionManager} loses the commit-outcome detection,
     * so the startup report warns, naming the store and the bean.
     */
    @Test
    void aPlainTransactionManagerIsWarnedAboutAtStartup(CapturedOutput output) {
        runner
                .withUserConfiguration(BooksStoreWithOwnTransactionManager.class)
                .withPropertyValues(store("books"))
                .run(started());
        assertThat(output.getOut().lines().filter(line -> line.contains("booksTransactionManager")))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .contains(" WARN ", "io.americanexpress.synapse.data ")
                        .endsWith("Data store books: its booksTransactionManager is a "
                                + "org.springframework.orm.jpa.JpaTransactionManager, not a "
                                + "io.americanexpress.synapse.data.jpa.SynapseJpaTransactionManager, so a connection "
                                + "lost while a transaction commits can be reported as a retryable UNAVAILABLE "
                                + "instead of COMMIT_OUTCOME_UNKNOWN, and with some managers (JdbcTransactionManager, "
                                + "JpaTransactionManager) a failed rollback hides the failure it "
                                + "replaced. Extend SynapseJpaTransactionManager to keep both."));
    }

    /** Synapse's own transaction manager, or a subclass of it, gets no warning. */
    @Test
    void synapsesTransactionManagerOrASubclassIsNotWarnedAbout(CapturedOutput output) {
        books.run(started());
        runner
                .withUserConfiguration(BooksStoreWithSynapseTransactionManager.class)
                .withPropertyValues(store("books"))
                .run(started());
        assertThat(output.getOut()).contains("Synapse data stores: books").doesNotContain("booksTransactionManager");
    }

    /**
     * With lazy initialization the store's own manager isn't created before the startup report, which then sees only
     * its bean definition: still no WARN.
     */
    @Test
    void synapsesTransactionManagerIsNotWarnedAboutWithLazyInitialization(CapturedOutput output) {
        books
                .withInitializer(context -> context
                        .addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()))
                .run(context -> {
                    assertThat(context.getBeanFactory().containsSingleton("booksTransactionManager"))
                            .as("created before the report")
                            .isFalse();
                    started().accept(context);
                });
        assertThat(output.getOut()).contains("Synapse data stores: books").doesNotContain("booksTransactionManager");
    }

    /**
     * Translators run in {@code ObjectProvider.orderedStream()} order, as the operation pipeline takes them: the data
     * translator, then the JPA one, then an application translator that declares no order, even one registered first.
     * So an unknown commit outcome is reported by the data translator, whatever the others would say.
     */
    @Test
    void translatorsRunInOrderAheadOfUnorderedApplicationTranslators() {
        books
                .withBean("applicationTranslator", ExceptionTranslator.class,
                        () -> exception -> new SynapseException(SynapseErrors.INTERNAL, exception))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    List<ExceptionTranslator> translators = context
                            .getBeanProvider(ExceptionTranslator.class)
                            .orderedStream()
                            .toList();
                    ExceptionTranslator data = context
                            .getBean("synapseDataExceptionTranslator",
                                    ExceptionTranslator.class);
                    ExceptionTranslator jpa = context.getBean(TRANSLATOR, ExceptionTranslator.class);
                    ExceptionTranslator application = context
                            .getBean("applicationTranslator",
                                    ExceptionTranslator.class);
                    assertThat(translators).containsSubsequence(data, jpa, application);
                    CommitOutcomeUnknownException commit = new CommitOutcomeUnknownException(
                            new RuntimeException("I/O error"));
                    commit.initApplicationException(new StaleStateException("Row was updated by another transaction"));
                    SynapseException first = translators
                            .stream()
                            .map(translator -> translator.translate(commit))
                            .filter(Objects::nonNull)
                            .findFirst()
                            .orElseThrow();
                    assertThat(first.code()).isEqualTo(DataErrors.COMMIT_OUTCOME_UNKNOWN.code());
                });
    }

    /**
     * I3, the reviewer's probe: two stores, books primary, its transaction manager replaced in its store class. The
     * replacement is the books store's, qualified and primary as Synapse's would be.
     */
    @Test
    void aReplacedTransactionManagerIsTheStoresQualifiedAndPrimary() {
        runner
                .withUserConfiguration(BooksStoreWithOwnTransactionManager.class, LegacyStore.class,
                        TransactionalServices.class)
                .withPropertyValues(store("books"))
                .withPropertyValues(store("legacy"))
                .withPropertyValues("synapse.data.stores.books.primary=true",
                        "synapse.data.stores.books.migrations.enabled=false",
                        "synapse.data.stores.books.jpa.hibernate.ddl-auto=none",
                        "synapse.data.stores.legacy.migrations.enabled=false",
                        "synapse.data.stores.legacy.jpa.hibernate.ddl-auto=none")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
                    Object books = context.getBean("booksTransactionManager");
                    BeanDefinition definition = beanFactory.getBeanDefinition("booksTransactionManager");
                    assertThat(definition.isPrimary()).isTrue();
                    assertThat(qualifier(definition)).isEqualTo("books");
                    assertThat(BeanFactoryAnnotationUtils
                            .qualifiedBeanOfType(beanFactory, TransactionManager.class, "books")).isSameAs(books);
                    assertThat(context.getBean(TransactionManager.class)).isSameAs(books);
                    TransactionalServices services = context.getBean(TransactionalServices.class);
                    assertThat(services.books()).isEqualTo("books");
                    assertThat(services.unqualified()).isEqualTo("books");
                    assertThat(services.legacy()).isEqualTo("legacy");
                });
    }

    @Test
    void aUserJpaStoreFactoryReplacesSynapses() {
        books.withUserConfiguration(UserJpaStoreFactory.class).run(context -> {
            assertThat(context).hasSingleBean(JpaStoreFactory.class);
            assertThat(context.getBean(FACTORY)).isSameAs(UserJpaStoreFactory.created);
            assertThat(context).hasBean("booksEntityManagerFactory");
        });
    }

    @Test
    void absentWithoutHibernate() {
        runner
                .withClassLoader(new FilteredClassLoader(SessionImplementor.class))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(FACTORY)
                        .doesNotHaveBean(TRANSLATOR)
                        .doesNotHaveBean(JpaSchemaAction.class));
    }

    @Test
    void everyStoreGetsSynapsesMappingResource() {
        books.withUserConfiguration(LegacyStore.class).withPropertyValues(store("legacy")).run(context -> {
            assertThat(context).hasNotFailed();
            for (String store : new String[]{"books", "legacy"}) {
                PersistenceUnitInfo unit = Objects
                        .requireNonNull(context
                                .getBean("&" + store + "EntityManagerFactory",
                                        LocalContainerEntityManagerFactoryBean.class)
                                .getPersistenceUnitInfo());
                assertThat(unit.getMappingFileNames())
                        .as(store)
                        .containsExactly("io/americanexpress/synapse/data/jpa/synapse-orm.xml");
            }
        });
    }

    @Test
    void registersTheJpaExceptionTranslator() {
        books.run(context -> assertThat(context.getBean(TRANSLATOR)).isInstanceOf(JpaExceptionTranslator.class));
    }

    @Test
    void aUserBeanNamedLikeTheJpaTranslatorReplacesSynapses() {
        books.withUserConfiguration(UserJpaExceptionTranslator.class).run(context -> {
            assertThat(context.getBean(TRANSLATOR)).isSameAs(UserJpaExceptionTranslator.TRANSLATOR);
            assertThat(context).doesNotHaveBean(JpaExceptionTranslator.class);
        });
    }

    @Test
    void anotherExceptionTranslatorDoesNotReplaceTheJpaTranslator() {
        books.withUserConfiguration(OtherExceptionTranslator.class).run(context -> {
            assertThat(context.getBean(TRANSLATOR)).isInstanceOf(JpaExceptionTranslator.class);
            assertThat(context.getBeansOfType(ExceptionTranslator.class).keySet())
                    .contains(TRANSLATOR, "otherExceptionTranslator");
        });
    }

    @Test
    void aStoreGetsASchemaAction() {
        books.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("booksSchemaAction"))
                    .isInstanceOfSatisfying(JpaSchemaAction.class,
                            action -> assertThat(action.store()).isEqualTo("books"));
            assertThat(qualifier(context.getBeanFactory().getBeanDefinition("booksSchemaAction")))
                    .isEqualTo("books");
        });
    }

    /** The application's schema action replaces Synapse's; the ddl-auto guard still runs on the factory's settings. */
    @Test
    void aUserSchemaActionInTheStoreClassReplacesSynapses() {
        runner
                .withUserConfiguration(BooksStoreWithOwnSchemaAction.class)
                .withPropertyValues(store("books"))
                .withPropertyValues("synapse.data.stores.books.url=jdbc:h2:mem:jpa-books-own-action;DB_CLOSE_DELAY=-1")
                .withPropertyValues("synapse.data.stores.books.migrations.enabled=false")
                .run(context -> {
                    assertThat(context).as("no validation of the empty database").hasNotFailed();
                    assertThat(context.getBean("booksSchemaAction"))
                            .isInstanceOf(BooksStoreWithOwnSchemaAction.OwnSchemaAction.class);
                    assertThat(context).doesNotHaveBean(JpaSchemaAction.class);
                });
        runner
                .withUserConfiguration(BooksStoreWithOwnSchemaAction.class)
                .withPropertyValues(store("books"))
                .withPropertyValues("synapse.data.stores.books.jpa.hibernate.ddl-auto=update")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(NestedExceptionUtils
                            .getMostSpecificCause(Objects.requireNonNull(context.getStartupFailure())))
                            .hasMessage("The store 'books' sets ddl-auto to update, which changes the schema at "
                                    + "startup.");
                });
    }

    @Test
    void absentWithoutTheStoreFactory() {
        runner
                .withClassLoader(new FilteredClassLoader(HikariDataSource.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(FACTORY));
    }

    @Test
    void aStoreWithoutHibernateHasNoAccess() {
        books.withClassLoader(new FilteredClassLoader(SessionImplementor.class)).run(context -> {
            assertThat(context).hasFailed();
            Throwable root = NestedExceptionUtils
                    .getMostSpecificCause(Objects.requireNonNull(context.getStartupFailure()));
            assertThat(root)
                    .isInstanceOfSatisfying(SynapseConfigurationException.class,
                            failure -> assertThat(failure.description())
                                    .isEqualTo("The store 'books' uses JPA or JDBC, but synapse-data-jpa or "
                                            + "synapse-data-jdbc is not on the class path."));
        });
    }

    private static ContextConsumer<AssertableApplicationContext> started() {
        return context -> {
            assertThat(context).hasNotFailed();
            context
                    .publishEvent(new ApplicationStartedEvent(new SpringApplication(), new String[0],
                            context.getSourceApplicationContext(), Duration.ZERO));
        };
    }

    private static Map<String, Object> vendorProperties(AssertableApplicationContext context) {
        return context.getBean(FACTORY, JpaStoreFactory.class).vendorProperties("books");
    }

    private static Dialect dialect(AssertableApplicationContext context, String name) {
        return context
                .getBean(name, EntityManagerFactory.class)
                .unwrap(SessionFactoryImplementor.class)
                .getJdbcServices()
                .getDialect();
    }

    private static @Nullable DataAccessException translate(AssertableApplicationContext context) {
        JpaDialect jpaDialect = Objects
                .requireNonNull(context
                        .getBean("&booksEntityManagerFactory", LocalContainerEntityManagerFactoryBean.class)
                        .getJpaDialect());
        return jpaDialect.translateExceptionIfPossible(new JDBCException("boom", new SQLException("boom", "99999")));
    }

    private static Set<Class<?>> entities(EntityManagerFactory entityManagerFactory) {
        return entityManagerFactory
                .getMetamodel()
                .getEntities()
                .stream()
                .map(EntityType::getJavaType)
                .collect(Collectors.toSet());
    }

    private static @Nullable Object qualifier(BeanDefinition definition) {
        AutowireCandidateQualifier qualifier = ((AbstractBeanDefinition) definition)
                .getQualifier(Qualifier.class.getName());
        return qualifier == null ? null : qualifier.getAttribute(AutowireCandidateQualifier.VALUE_KEY);
    }

    private static String[] store(String name) {
        return new String[]{"synapse.data.stores." + name + ".url=jdbc:h2:mem:jpa-" + name + ";DB_CLOSE_DELAY=-1",
                "synapse.data.stores." + name + ".username=sa", "synapse.data.stores." + name + ".password="};
    }

    /**
     * Processed before Synapse's JPA auto-configuration, with a condition that looks beans up by an annotation that no
     * bean has, so it never matches.
     */
    @AutoConfiguration(before = SynapseDataJpaAutoConfiguration.class)
    @ConditionalOnBean(annotation = LookedUp.class)
    static class LooksUpBeansByAnnotation {
    }

    /** An annotation that no bean has. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface LookedUp {
    }

    @Configuration(proxyBeanMethods = false)
    static class UserJpaExceptionTranslator {

        static final ExceptionTranslator TRANSLATOR = exception -> null;

        @Bean
        ExceptionTranslator synapseJpaExceptionTranslator() {
            return TRANSLATOR;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OtherExceptionTranslator {

        @Bean
        ExceptionTranslator otherExceptionTranslator() {
            return exception -> null;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserJpaStoreFactory {

        static @Nullable JpaStoreFactory created;

        @Bean
        JpaStoreFactory synapseJpaStoreFactory(ConfigurableListableBeanFactory beanFactory, Environment environment,
                ResourceLoader resourceLoader, StoreFactory storeFactory) {
            JpaStoreFactory factory = new JpaStoreFactory(beanFactory, environment, resourceLoader, storeFactory);
            created = factory;
            return factory;
        }
    }

    /** An H2 vendor that names no product, as for a database Hibernate can't resolve by name. */
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class DialectOnlyH2Vendor implements DataStoreVendor {

        private final H2TestVendor h2 = new H2TestVendor();

        @Override
        public String name() {
            return "h2-dialect-only";
        }

        @Override
        public boolean supports(String jdbcUrl) {
            return h2.supports(jdbcUrl);
        }

        @Override
        public String defaultDatabaseVersion() {
            return h2.defaultDatabaseVersion();
        }

        @Override
        public String hibernateDialect() {
            return h2.hibernateDialect();
        }

        @Override
        public String jdbcDialect() {
            return h2.jdbcDialect();
        }
    }

    /** What the application's SQL exception translator produces. */
    static class TranslatedByTheApplication extends DataAccessException {

        private static final long serialVersionUID = 1L;

        TranslatedByTheApplication(String message) {
            super(message);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnSqlExceptionTranslator {

        @Bean
        SQLExceptionTranslator ownSqlExceptionTranslator() {
            return (task, sql, ex) -> new TranslatedByTheApplication(task);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OtherSqlExceptionTranslator {

        @Bean
        SQLExceptionTranslator otherSqlExceptionTranslator() {
            return (task, sql, ex) -> new TranslatedByTheApplication(task);
        }
    }

    /** Tells which store's EntityManagerFactory a transaction is bound to. */
    @Configuration(proxyBeanMethods = false)
    static class TransactionalServices {

        private final EntityManagerFactory books;

        private final EntityManagerFactory legacy;

        TransactionalServices(@Qualifier("books") EntityManagerFactory books,
                @Qualifier("legacy") EntityManagerFactory legacy) {
            this.books = books;
            this.legacy = legacy;
        }

        @Transactional("books")
        public String books() {
            return bound();
        }

        @Transactional
        public String unqualified() {
            return bound();
        }

        @Transactional("legacy")
        public String legacy() {
            return bound();
        }

        private String bound() {
            if (TransactionSynchronizationManager.hasResource(books)) {
                return "books";
            }
            return TransactionSynchronizationManager.hasResource(legacy) ? "legacy" : "none";
        }
    }
}
