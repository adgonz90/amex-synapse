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
package io.americanexpress.synapse.data.jdbc.autoconfigure;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.zaxxer.hikari.HikariDataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.data.jdbc.autoconfigure.DataJdbcRepositoriesAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizationAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.convert.CustomConversions;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.data.jdbc.core.convert.DataAccessStrategy;
import org.springframework.data.jdbc.core.convert.JdbcConverter;
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions;
import org.springframework.data.jdbc.core.dialect.JdbcDialect;
import org.springframework.data.jdbc.core.dialect.JdbcH2Dialect;
import org.springframework.data.jdbc.core.dialect.JdbcPostgresDialect;
import org.springframework.data.jdbc.core.mapping.JdbcMappingContext;
import org.springframework.data.mapping.PersistentEntity;
import org.springframework.data.mapping.callback.EntityCallback;
import org.springframework.data.relational.RelationalManagedTypes;
import org.springframework.data.relational.core.conversion.AbstractRelationalConverter;
import org.springframework.data.relational.core.mapping.DefaultNamingStrategy;
import org.springframework.data.relational.core.mapping.NamingStrategy;
import org.springframework.data.relational.core.mapping.event.BeforeConvertCallback;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionManager;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.data.SynapseDataStore;
import io.americanexpress.synapse.data.autoconfigure.SynapseDataAutoConfiguration;
import io.americanexpress.synapse.data.internal.store.StoreBeans;
import io.americanexpress.synapse.data.internal.store.StoreFactory;
import io.americanexpress.synapse.data.jdbc.SynapseJdbcTransactionManager;
import io.americanexpress.synapse.data.jdbc.fixture.H2TestVendor;
import io.americanexpress.synapse.data.jdbc.fixture.books.Book;
import io.americanexpress.synapse.data.jdbc.fixture.books.BookRepository;
import io.americanexpress.synapse.data.jdbc.fixture.books.BooksStore;
import io.americanexpress.synapse.data.jdbc.fixture.legacy.LegacyItem;
import io.americanexpress.synapse.data.jdbc.fixture.legacy.LegacyItemRepository;
import io.americanexpress.synapse.data.jdbc.fixture.legacy.LegacyStore;
import io.americanexpress.synapse.data.jdbc.fixture.legacy.Shelf;
import io.americanexpress.synapse.data.jdbc.fixture.legacy.Slot;
import io.americanexpress.synapse.data.jdbc.fixture.ownbeans.BooksStoreWithOwnBeans;
import io.americanexpress.synapse.data.jdbc.fixture.ownnaming.BooksStoreWithOwnNaming;
import io.americanexpress.synapse.data.jdbc.fixture.plaintm.BooksStoreWithPlainTransactionManager;
import io.americanexpress.synapse.data.jdbc.fixture.synapsetm.BooksStoreWithSynapseTransactionManager;
import io.americanexpress.synapse.data.jdbc.internal.JdbcAuditingCallback;
import io.americanexpress.synapse.data.jdbc.internal.JdbcStoreFactory;
import io.americanexpress.synapse.data.vendor.DataStoreVendor;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class SynapseDataJdbcAutoConfigurationTest {

    private static final String FACTORY = "synapseJdbcStoreFactory";

    private static final String CALLBACK = "synapseJdbcAuditingCallback";

    private static final String[] BOOKS_BEANS = {"booksTransactionManager", "booksJdbcDialect",
            "booksRelationalManagedTypes", "booksJdbcCustomConversions", "booksJdbcMappingContext",
            "booksJdbcConverter",
            "booksDataAccessStrategy", "booksJdbcAggregateTemplate", "bookRepository"};

    private static final String UNREACHABLE = "jdbc:h2:tcp://127.0.0.1:1/none";

    /** Without a vendor, for the tests that bring their own. */
    private final ApplicationContextRunner withoutVendor = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations
                    .of(SynapseCoreAutoConfiguration.class, SynapseDataAutoConfiguration.class,
                            SynapseDataJdbcAutoConfiguration.class, DataSourceAutoConfiguration.class,
                            DataSourceTransactionManagerAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
                            DataJdbcRepositoriesAutoConfiguration.class,
                            TransactionManagerCustomizationAutoConfiguration.class,
                            TransactionAutoConfiguration.class))
            // What JdbcDefaultsEnvironmentPostProcessor sets in an application (a context runner runs no
            // post-processor).
            .withPropertyValues("spring.data.jdbc.repositories.enabled=false");

    private final ApplicationContextRunner runner = withoutVendor.withUserConfiguration(H2TestVendor.class);

    private final ApplicationContextRunner books = runner
            .withUserConfiguration(BooksStore.class)
            .withPropertyValues(store("books"));

    /**
     * A store whose transaction manager is a plain {@code DataSourceTransactionManager} loses the commit-outcome
     * detection, so the startup report warns, naming the store and the bean.
     */
    @Test
    void aPlainTransactionManagerIsWarnedAboutAtStartup(CapturedOutput output) {
        runner
                .withUserConfiguration(BooksStoreWithPlainTransactionManager.class)
                .withPropertyValues(store("books"))
                .run(started());
        assertThat(output.getOut().lines().filter(line -> line.contains("booksTransactionManager")))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .contains(" WARN ", "io.americanexpress.synapse.data ")
                        .endsWith("Data store books: its booksTransactionManager is a "
                                + "org.springframework.jdbc.datasource.DataSourceTransactionManager, not a "
                                + "io.americanexpress.synapse.data.jdbc.SynapseJdbcTransactionManager, so a connection "
                                + "lost while a transaction commits can be reported as a retryable UNAVAILABLE "
                                + "instead of COMMIT_OUTCOME_UNKNOWN, and with some managers (JdbcTransactionManager, "
                                + "JpaTransactionManager) a failed rollback hides the failure it "
                                + "replaced. Extend SynapseJdbcTransactionManager to keep both."));
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
     * its bean definition: still no WARN. A plain replacement not created yet, whose bean method declares a supertype of
     * Synapse's, is warned about as one Synapse can't tell apart from a subclass.
     */
    @Test
    void lazyInitializationWarnsOnlyAboutAReplacement(CapturedOutput output) {
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
        runner
                .withUserConfiguration(BooksStoreWithPlainTransactionManager.class)
                .withPropertyValues(store("books"))
                .withInitializer(context -> context
                        .addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()))
                .run(started());
        assertThat(output.getOut().lines().filter(line -> line.contains("booksTransactionManager")))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .contains(" WARN ",
                                "is declared as a org.springframework.jdbc.datasource.DataSourceTransactionManager "
                                        + "and isn't created yet (lazy initialization)",
                                "Declare the bean as a "
                                        + "SynapseJdbcTransactionManager"));
    }

    @Test
    void aJdbcStoreGetsItsInfrastructure() {
        books.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("booksTransactionManager"))
                    .isInstanceOfSatisfying(SynapseJdbcTransactionManager.class, transactionManager -> assertThat(
                            transactionManager.getDataSource()).isSameAs(context.getBean("booksDataSource")));
            assertThat(context.getBean("booksJdbcDialect")).isSameAs(JdbcH2Dialect.INSTANCE);
            JdbcMappingContext mappingContext = context.getBean("booksJdbcMappingContext", JdbcMappingContext.class);
            assertThat(entities(mappingContext)).containsExactly(Book.class);
            JdbcConverter converter = context.getBean("booksJdbcConverter", JdbcConverter.class);
            assertThat(converter.getMappingContext()).isSameAs(mappingContext);
            assertThat(context.getBean("booksDataAccessStrategy")).isInstanceOf(DataAccessStrategy.class);
            assertThat(context.getBean("booksJdbcAggregateTemplate"))
                    .isInstanceOfSatisfying(JdbcAggregateTemplate.class,
                            template -> assertThat(template.getConverter()).isSameAs(converter));
            assertThat(context.getBean("booksRelationalManagedTypes")).isInstanceOf(RelationalManagedTypes.class);
            assertThat(conversions(converter)).isSameAs(context.getBean("booksJdbcCustomConversions"));
            assertThat(tableName(context, "books", Book.class)).as("Spring Data's default naming").isEqualTo("book");
            assertThat(context).hasSingleBean(BookRepository.class);
            for (String name : BOOKS_BEANS) {
                BeanDefinition definition = context.getBeanFactory().getBeanDefinition(name);
                assertThat(definition.isPrimary()).as(name).isTrue();
                assertThat(qualifier(definition)).as(name).isEqualTo("books");
            }
        });
    }

    @Test
    void eachStoreGetsItsOwnInfrastructure() {
        books.withUserConfiguration(LegacyStore.class).withPropertyValues(store("legacy")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(entities(context.getBean("booksJdbcMappingContext", JdbcMappingContext.class)))
                    .containsExactly(Book.class);
            assertThat(entities(context.getBean("legacyJdbcMappingContext", JdbcMappingContext.class)))
                    .containsExactlyInAnyOrder(LegacyItem.class, Shelf.class, Slot.class);
            assertThat(context.getBean("legacyJdbcCustomConversions"))
                    .isNotSameAs(context.getBean("booksJdbcCustomConversions"))
                    .isSameAs(conversions(context.getBean("legacyJdbcConverter", JdbcConverter.class)));
            assertThat(context.getBean("legacyTransactionManager", JdbcTransactionManager.class).getDataSource())
                    .isSameAs(context.getBean("legacyDataSource"));
            assertThat(context.getBean("legacyJdbcAggregateTemplate", JdbcAggregateTemplate.class).getConverter())
                    .isSameAs(context.getBean("legacyJdbcConverter"));
            assertThat(context).hasSingleBean(LegacyItemRepository.class);
            assertThat(qualifier(context.getBeanFactory().getBeanDefinition("legacyItemRepository")))
                    .isEqualTo("legacy");
            for (String name : new String[]{"booksJdbcAggregateTemplate", "legacyTransactionManager",
                    "legacyItemRepository"}) {
                assertThat(context.getBeanFactory().getBeanDefinition(name).isPrimary()).as(name).isFalse();
            }
        });
    }

    @Test
    void bootsJdbcRepositoriesBackOff() {
        books.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean("transactionManager");
            assertThat(context.getBeansOfType(JdbcAggregateTemplate.class).keySet())
                    .containsExactly("booksJdbcAggregateTemplate");
            assertThat(context.getBeansOfType(TransactionManager.class).keySet())
                    .containsExactly("booksTransactionManager");
            assertThat(context.getBeansOfType(JdbcDialect.class).keySet()).containsExactly("booksJdbcDialect");
            assertThat(context.getBeansOfType(JdbcMappingContext.class).keySet())
                    .containsExactly("booksJdbcMappingContext");
            assertThat(context.getBeansOfType(JdbcCustomConversions.class).keySet())
                    .containsExactly("booksJdbcCustomConversions");
            assertThat(context.getBeansOfType(EntityCallback.class).keySet())
                    .as("no callback of Boot's, such as its idGeneratingBeforeSaveCallback")
                    .containsExactly(CALLBACK);
        });
    }

    /**
     * With Boot's JDBC repositories turned back on, the beans of its Spring Data JDBC configuration that it creates only
     * when missing (the dialect, mapping context, conversions, converter and aggregate template) back off to the
     * store's, so its dialect is never resolved over a connection. Boot still creates
     * {@code idGeneratingBeforeSaveCallback}, which it defines unconditionally, on the primary store's beans; with two
     * stores and no primary, startup then fails ({@link #twoStoresFailWhenBootsRepositoriesAreOn()}).
     */
    @Test
    void bootsJdbcConfigurationBacksOffWhenItsRepositoriesAreOn() {
        withBootsRepositoriesOn(books).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context)
                    .as("Boot's configuration is active: it defines this callback unconditionally")
                    .hasBean("idGeneratingBeforeSaveCallback");
            assertThat(context).doesNotHaveBean("jdbcDialect");
            assertThat(context).doesNotHaveBean("jdbcAggregateTemplate");
            assertThat(context).doesNotHaveBean("jdbcMappingContext");
            assertThat(context).doesNotHaveBean("jdbcConverter");
            assertThat(context).doesNotHaveBean("jdbcCustomConversions");
            assertThat(context.getBeansOfType(JdbcDialect.class).keySet())
                    .containsExactly("booksJdbcDialect");
        });
    }

    @Test
    void twoStoresFailWhenBootsRepositoriesAreOn() {
        withBootsRepositoriesOn(books.withUserConfiguration(LegacyStore.class))
                .withPropertyValues(unreachable("legacy"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("idGeneratingBeforeSaveCallback")
                            .hasStackTraceContaining("NoUniqueBeanDefinitionException");
                });
    }

    @Test
    void dialectComesFromTheVendor() {
        runner
                .withUserConfiguration(BooksStore.class)
                .withPropertyValues(unreachable())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("booksJdbcDialect")).isSameAs(JdbcH2Dialect.INSTANCE);
                    assertThat(context).hasBean("booksJdbcAggregateTemplate");
                    assertThat(context).hasSingleBean(BookRepository.class);
                });
        withoutVendor
                .withBean(DataStoreVendor.class, () -> new DialectVendor(JdbcPostgresDialect.class.getName()))
                .withUserConfiguration(BooksStore.class)
                .withPropertyValues(unreachable())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("booksJdbcDialect")).isSameAs(JdbcPostgresDialect.INSTANCE);
                });
    }

    @Test
    void aVendorDialectWithoutAnInstanceFails() {
        for (String dialect : new String[]{"com.example.MissingDialect", String.class.getName(),
                NotADialect.class.getName(), InstanceFieldDialect.class.getName()}) {
            withoutVendor
                    .withBean(DataStoreVendor.class, () -> new DialectVendor(dialect))
                    .withUserConfiguration(BooksStore.class)
                    .withPropertyValues(unreachable())
                    .run(context -> {
                        assertThat(context).as(dialect).hasFailed();
                        assertThat(context.getStartupFailure())
                                .as(dialect)
                                .hasStackTraceContaining("The vendor 'test' names the JDBC dialect " + dialect
                                        + ", which is not a class with a public static INSTANCE field that holds a "
                                        + "org.springframework.data.jdbc.core.dialect.JdbcDialect.");
                    });
        }
    }

    /** Acceptance 17 at the JDBC level: the database is reachable, so any connection asked for would start the pool. */
    @Test
    void buildingTheStoresBeansOpensNoConnection() {
        books.withPropertyValues("synapse.data.stores.books.migrations.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("booksJdbcAggregateTemplate").hasSingleBean(BookRepository.class);
            HikariDataSource dataSource = context.getBean("booksDataSource", HikariDataSource.class);
            assertThat(dataSource.getHikariPoolMXBean()).as("the pool was never started").isNull();
            try (Connection connection = dataSource.getConnection()) {
                assertThat(connection.isValid(1)).as("the database is reachable").isTrue();
            }
        });
    }

    @Test
    void transactionManagerCustomizersApply() {
        books
                .withPropertyValues("spring.transaction.default-timeout=7s")
                .run(context -> assertThat(
                        context.getBean("booksTransactionManager", JdbcTransactionManager.class).getDefaultTimeout())
                        .isEqualTo(7));
    }

    @Test
    void registersTheAuditingCallback() {
        books.run(context -> {
            assertThat(context.getBean(CALLBACK)).isInstanceOf(JdbcAuditingCallback.class);
            assertThat(context.getBeansOfType(BeforeConvertCallback.class).keySet()).containsExactly(CALLBACK);
        });
    }

    @Test
    void aUserBeanNamedLikeTheAuditingCallbackReplacesSynapses() {
        books.withUserConfiguration(UserAuditingCallback.class).run(context -> {
            assertThat(context.getBean(CALLBACK)).isSameAs(UserAuditingCallback.CALLBACK);
            assertThat(context).doesNotHaveBean(JdbcAuditingCallback.class);
        });
    }

    @Test
    void storeClassBeansReplaceSynapses() {
        runner
                .withUserConfiguration(BooksStoreWithOwnBeans.class)
                .withPropertyValues(store("books"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(PlatformTransactionManager.class).keySet())
                            .containsExactly("booksTransactionManager");
                    assertThat(context
                            .getBean("booksTransactionManager", JdbcTransactionManager.class)
                            .getDefaultTimeout()).isEqualTo(BooksStoreWithOwnBeans.TIMEOUT);
                    assertThat(context.getBeansOfType(JdbcDialect.class).keySet())
                            .containsExactly("booksJdbcDialect");
                    assertThat(context.getBean("booksJdbcDialect")).isSameAs(BooksStoreWithOwnBeans.DIALECT);
                    assertThat(context.getBeansOfType(JdbcAggregateTemplate.class).keySet())
                            .containsExactly("booksJdbcAggregateTemplate");
                    assertThat(context.getBean("booksJdbcAggregateTemplate"))
                            .isInstanceOf(BooksStoreWithOwnBeans.OwnAggregateTemplate.class);
                    assertThat(context).hasBean("booksJdbcConverter").hasBean("booksJdbcMappingContext");
                    assertThat(context.getBeansOfType(JdbcCustomConversions.class).keySet())
                            .containsExactly("booksJdbcCustomConversions");
                    assertThat(conversions(context.getBean("booksJdbcConverter", JdbcConverter.class)))
                            .isSameAs(BooksStoreWithOwnBeans.CONVERSIONS);
                });
    }

    @Test
    void anApplicationNamingStrategyNamesEveryStoresTables() {
        books
                .withUserConfiguration(LegacyStore.class, PrefixNaming.class)
                .withPropertyValues(store("legacy"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(tableName(context, "books", Book.class)).isEqualTo("T_book");
                    assertThat(tableName(context, "legacy", LegacyItem.class)).isEqualTo("T_legacy_item");
                });
    }

    /** The books store's own strategy wins for books, and isn't an application strategy for the other stores. */
    @Test
    void aStoresOwnNamingStrategyWins() {
        runner
                .withUserConfiguration(BooksStoreWithOwnNaming.class, LegacyStore.class, PrefixNaming.class)
                .withPropertyValues(store("books"))
                .withPropertyValues(store("legacy"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(tableName(context, "books", Book.class)).isEqualTo("S_book");
                    assertThat(tableName(context, "legacy", LegacyItem.class)).isEqualTo("T_legacy_item");
                });
    }

    @Test
    void aPrimaryApplicationNamingStrategyIsChosen() {
        books.withUserConfiguration(PrefixNaming.class, PrimaryNaming.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(tableName(context, "books", Book.class)).isEqualTo("P_book");
        });
    }

    @Test
    void twoApplicationNamingStrategiesFail() {
        books.withUserConfiguration(PrefixNaming.class, OtherNaming.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(NestedExceptionUtils
                    .getMostSpecificCause(Objects.requireNonNull(context.getStartupFailure())))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class, failure -> {
                        assertThat(failure.description())
                                .isEqualTo("The store 'books' finds more than one NamingStrategy bean: otherNaming, "
                                        + "prefixNaming.");
                        assertThat(failure.action())
                                .isEqualTo("Mark one of them @Primary, or define booksNamingStrategy in the store's "
                                        + "@SynapseDataStore class.");
                    });
        });
    }

    @Test
    void anApplicationJdbcCustomConversionsIsUsedByEveryStore() {
        books
                .withUserConfiguration(LegacyStore.class, ApplicationConversions.class)
                .withPropertyValues(store("legacy"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    for (String store : new String[]{"books", "legacy"}) {
                        assertThat(context.getBean(store + "JdbcCustomConversions"))
                                .as(store)
                                .isSameAs(ApplicationConversions.CONVERSIONS);
                        assertThat(conversions(context.getBean(store + "JdbcConverter", JdbcConverter.class)))
                                .as(store)
                                .isSameAs(ApplicationConversions.CONVERSIONS);
                    }
                });
    }

    /**
     * Minor 13: one unqualified application conversions bean would apply one dialect's store conversions to JDBC
     * stores whose dialects differ, so startup fails instead.
     */
    @Test
    void oneUnqualifiedConversionsForStoresOfDifferentDialectsFails() {
        books
                .withUserConfiguration(LegacyStore.class, ApplicationConversions.class, PostgresDialectVendor.class)
                .withPropertyValues(store("legacy"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(NestedExceptionUtils
                            .getMostSpecificCause(Objects.requireNonNull(context.getStartupFailure())))
                            .isInstanceOfSatisfying(SynapseConfigurationException.class, failure -> {
                                assertThat(failure.description())
                                        .isEqualTo("The JdbcCustomConversions bean applicationConversions would "
                                                + "apply to JDBC stores whose dialects differ: books ("
                                                + JdbcH2Dialect.class.getName() + "), legacy ("
                                                + JdbcPostgresDialect.class.getName() + ").");
                                assertThat(failure.action())
                                        .isEqualTo("Qualify it with the store it is for, such as "
                                                + "@Qualifier(\"books\"), or define each store's "
                                                + "<prefix>JdbcCustomConversions in its @SynapseDataStore class.");
                            });
                });
    }

    /** Minor 13: a dialect the store class defines counts as the store's, read from its type. */
    @Test
    void aStoreClassDialectCountsAsTheStores() {
        runner
                .withUserConfiguration(BooksStoreWithPostgresDialect.class, LegacyStore.class,
                        ApplicationConversions.class)
                .withPropertyValues(store("books"))
                .withPropertyValues(store("legacy"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(NestedExceptionUtils
                            .getMostSpecificCause(Objects.requireNonNull(context.getStartupFailure())))
                            .isInstanceOfSatisfying(SynapseConfigurationException.class,
                                    failure -> assertThat(failure.description())
                                            .isEqualTo("The JdbcCustomConversions bean applicationConversions would "
                                                    + "apply to JDBC stores whose dialects differ: books ("
                                                    + JdbcPostgresDialect.class.getName() + "), legacy ("
                                                    + JdbcH2Dialect.class.getName() + ")."));
                });
    }

    /** Minor 13: conversions qualified with one store don't meet the other store's dialect. */
    @Test
    void storeQualifiedConversionsForStoresOfDifferentDialectsPass() {
        books
                .withUserConfiguration(LegacyStore.class, LegacyConversions.class, PostgresDialectVendor.class)
                .withPropertyValues(store("legacy"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("legacyJdbcCustomConversions")).isSameAs(LegacyConversions.CONVERSIONS);
                    assertThat(context.getBean("legacyJdbcDialect")).isSameAs(JdbcPostgresDialect.INSTANCE);
                });
    }

    @Test
    void twoApplicationJdbcCustomConversionsFail() {
        books.withUserConfiguration(ApplicationConversions.class, OtherConversions.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(NestedExceptionUtils
                    .getMostSpecificCause(Objects.requireNonNull(context.getStartupFailure())))
                    .isInstanceOfSatisfying(SynapseConfigurationException.class,
                            failure -> assertThat(failure.description())
                                    .isEqualTo("The store 'books' finds more than one JdbcCustomConversions bean: "
                                            + "applicationConversions, otherConversions."));
        });
    }

    @Test
    void anApplicationBeanNamedLikeBootsConversionsIsUsed() {
        books.withUserConfiguration(ConversionsNamedLikeBoots.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("booksJdbcCustomConversions")).isSameAs(ConversionsNamedLikeBoots.CONVERSIONS);
        });
    }

    /** A bean qualified with a store's name applies to that store only, and wins over an unqualified one there. */
    @Test
    void aNamingStrategyQualifiedWithAStoreAppliesToThatStoreOnly() {
        books
                .withUserConfiguration(LegacyStore.class, PrefixNaming.class, LegacyNaming.class)
                .withPropertyValues(store("legacy"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(tableName(context, "legacy", LegacyItem.class)).isEqualTo("L_legacy_item");
                    assertThat(tableName(context, "books", Book.class)).isEqualTo("T_book");
                });
    }

    @Test
    void conversionsQualifiedWithAStoreApplyToThatStoreOnly() {
        books
                .withUserConfiguration(LegacyStore.class, LegacyConversions.class)
                .withPropertyValues(store("legacy"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("legacyJdbcCustomConversions")).isSameAs(LegacyConversions.CONVERSIONS);
                    assertThat(context.getBean("booksJdbcCustomConversions"))
                            .isNotSameAs(LegacyConversions.CONVERSIONS);
                });
    }

    @Test
    void aBeanWithAnotherQualifierIsNotUsedForStores() {
        books.withUserConfiguration(ReportsNaming.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(tableName(context, "books", Book.class)).isEqualTo("book");
        });
    }

    /** A qualifier added to the bean definition, and one on the bean's class, count as the factory method's does. */
    @Test
    void qualifiersAreReadFromTheDefinitionAndTheClass() {
        books
                .withBean("definitionQualifiedNaming", NamingStrategy.class, () -> prefixing("D_"),
                        definition -> ((AbstractBeanDefinition) definition)
                                .addQualifier(new AutowireCandidateQualifier(Qualifier.class, "reports")))
                .withUserConfiguration(ClassQualifiedNaming.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(NamingStrategy.class)).hasSize(2);
                    assertThat(tableName(context, "books", Book.class)).isEqualTo("book");
                });
    }

    /** Spring reads the qualifier on the class a {@code @Bean} method returns when the method has none. */
    @Test
    void aBeanMethodReturningAQualifiedClassIsNotUsedForStores() {
        books.withUserConfiguration(ClassQualifiedNamingMethod.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("classQualifiedNaming")).isInstanceOf(ClassQualifiedNaming.class);
            assertThat(tableName(context, "books", Book.class)).isEqualTo("book");
        });
    }

    /** A definition qualifier of a type other than {@code @Qualifier} is another qualifier. */
    @Test
    void aDefinitionQualifierOfAnotherTypeIsNotUsedForStores() {
        books
                .withBean("reportsTypeNaming", NamingStrategy.class, () -> prefixing("D_"),
                        definition -> ((AbstractBeanDefinition) definition)
                                .addQualifier(new AutowireCandidateQualifier(Reports.class)))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(tableName(context, "books", Book.class)).isEqualTo("book");
                });
    }

    @Test
    void aBeanSkippedForAnotherQualifierIsLoggedAtDebug(CapturedOutput output) {
        LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());
        String logger = StoreBeans.class.getName();
        logging.setLogLevel(logger, LogLevel.DEBUG);
        try {
            books.withUserConfiguration(ReportsNaming.class).run(context -> assertThat(context).hasNotFailed());
        } finally {
            logging.setLogLevel(logger, null);
        }
        assertThat(lines(output, "reportsNaming"))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .contains(" DEBUG ")
                        .endsWith("The store 'books' doesn't use the NamingStrategy bean reportsNaming, because it "
                                + "has another qualifier."));
    }

    @Test
    void warnsAboutBootJdbcKeysTheStoresIgnore(CapturedOutput output) {
        books
                .withPropertyValues("spring.data.jdbc.dialect=postgresql",
                        "spring.dao.exceptiontranslation.enabled=false")
                .run(started());
        assertThat(lines(output, "spring.data.jdbc.dialect"))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .contains(" WARN ", "io.americanexpress.synapse.data ")
                        .endsWith("spring.data.jdbc.dialect is set, but Synapse ignores it: each JDBC store takes its "
                                + "dialect from its vendor. To change a store's dialect, define <prefix>JdbcDialect in "
                                + "its @SynapseDataStore class."));
        assertThat(lines(output, "spring.dao.exceptiontranslation.enabled"))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .contains(" WARN ", "io.americanexpress.synapse.data ")
                        .endsWith("spring.dao.exceptiontranslation.enabled is set, but Synapse ignores it: each JDBC "
                                + "store's transaction manager is a JdbcTransactionManager, which translates "
                                + "exceptions. To change it, define <prefix>TransactionManager in the store's "
                                + "@SynapseDataStore class."));
        assertThat(output.getOut()).doesNotContain("postgresql");
    }

    @Test
    void noWarningWhenTheKeysAreUnsetOrThereIsNoJdbcStore(CapturedOutput output) {
        books.run(started());
        runner.withPropertyValues("spring.data.jdbc.dialect=postgresql").run(started());
        assertThat(output.getOut()).doesNotContain("is set, but Synapse ignores it");
    }

    @Test
    void aUserJdbcStoreFactoryReplacesSynapses() {
        books.withUserConfiguration(UserJdbcStoreFactory.class).run(context -> {
            assertThat(context).hasSingleBean(JdbcStoreFactory.class);
            assertThat(context.getBean(FACTORY)).isSameAs(UserJdbcStoreFactory.created);
            assertThat(context).hasBean("booksJdbcAggregateTemplate");
        });
    }

    @Test
    void absentWithoutSpringDataJdbc() {
        runner
                .withClassLoader(new FilteredClassLoader(JdbcAggregateTemplate.class))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(FACTORY)
                        .doesNotHaveBean(CALLBACK));
    }

    @Test
    void absentWithoutTheStoreFactory() {
        runner
                .withClassLoader(new FilteredClassLoader(HikariDataSource.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(FACTORY).doesNotHaveBean(CALLBACK));
    }

    @Test
    void aStoreWithoutSpringDataJdbcHasNoAccess() {
        books.withClassLoader(new FilteredClassLoader(JdbcAggregateTemplate.class)).run(context -> {
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

    private static Set<Class<?>> entities(JdbcMappingContext mappingContext) {
        return mappingContext
                .getPersistentEntities()
                .stream()
                .map(PersistentEntity::getType)
                .collect(Collectors.toSet());
    }

    private static @Nullable Object qualifier(BeanDefinition definition) {
        AutowireCandidateQualifier qualifier = ((AbstractBeanDefinition) definition)
                .getQualifier(Qualifier.class.getName());
        return qualifier == null ? null : qualifier.getAttribute(AutowireCandidateQualifier.VALUE_KEY);
    }

    private static String[] store(String name) {
        return new String[]{"synapse.data.stores." + name + ".url=jdbc:h2:mem:jdbc-" + name + ";DB_CLOSE_DELAY=-1",
                "synapse.data.stores." + name + ".username=sa", "synapse.data.stores." + name + ".password="};
    }

    private static String[] unreachable() {
        return unreachable("books");
    }

    private static String[] unreachable(String name) {
        String prefix = "synapse.data.stores." + name;
        return new String[]{prefix + ".url=" + UNREACHABLE, prefix + ".username=sa", prefix + ".password=",
                prefix + ".migrations.enabled=false"};
    }

    /** Boot's JDBC repositories on, with an unreachable books store: Boot's dialect would need a connection. */
    private static ApplicationContextRunner withBootsRepositoriesOn(ApplicationContextRunner runner) {
        return runner
                .withPropertyValues("spring.data.jdbc.repositories.enabled=true")
                .withPropertyValues(unreachable())
                .withInitializer(context -> AutoConfigurationPackages
                        .register((BeanDefinitionRegistry) context.getBeanFactory(),
                                "io.americanexpress.synapse.data.jdbc.fixture.none"));
    }

    private static CustomConversions conversions(JdbcConverter converter) {
        return ((AbstractRelationalConverter) converter).getConversions();
    }

    private static String tableName(AssertableApplicationContext context, String store, Class<?> type) {
        return context
                .getBean(store + "JdbcMappingContext", JdbcMappingContext.class)
                .getRequiredPersistentEntity(type)
                .getTableName()
                .getReference();
    }

    private static ContextConsumer<AssertableApplicationContext> started() {
        return context -> {
            assertThat(context).hasNotFailed();
            context
                    .publishEvent(new ApplicationStartedEvent(new SpringApplication(), new String[0],
                            context.getSourceApplicationContext(), Duration.ZERO));
        };
    }

    private static List<String> lines(CapturedOutput output, String text) {
        return output.getOut().lines().filter(line -> line.contains(text)).toList();
    }

    /** A naming strategy that prefixes table names. */
    static NamingStrategy prefixing(String prefix) {
        return new DefaultNamingStrategy() {

            @Override
            public String getTableName(Class<?> type) {
                return prefix + super.getTableName(type);
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    static class PrefixNaming {

        @Bean
        NamingStrategy prefixNaming() {
            return prefixing("T_");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OtherNaming {

        @Bean
        NamingStrategy otherNaming() {
            return prefixing("O_");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PrimaryNaming {

        @Bean
        @Primary
        NamingStrategy primaryNaming() {
            return prefixing("P_");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ApplicationConversions {

        static final JdbcCustomConversions CONVERSIONS = JdbcCustomConversions.of(JdbcH2Dialect.INSTANCE, List.of());

        @Bean
        JdbcCustomConversions applicationConversions() {
            return CONVERSIONS;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OtherConversions {

        @Bean
        JdbcCustomConversions otherConversions() {
            return JdbcCustomConversions.of(JdbcH2Dialect.INSTANCE, List.of());
        }
    }

    /** Named as Spring Data's and Boot's own configurations name it. */
    @Configuration(proxyBeanMethods = false)
    static class ConversionsNamedLikeBoots {

        static final JdbcCustomConversions CONVERSIONS = JdbcCustomConversions.of(JdbcH2Dialect.INSTANCE, List.of());

        @Bean
        JdbcCustomConversions jdbcCustomConversions() {
            return CONVERSIONS;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LegacyConversions {

        static final JdbcCustomConversions CONVERSIONS = JdbcCustomConversions.of(JdbcH2Dialect.INSTANCE, List.of());

        @Bean
        @Qualifier("legacy")
        JdbcCustomConversions legacyOnlyConversions() {
            return CONVERSIONS;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LegacyNaming {

        @Bean
        @Qualifier("legacy")
        NamingStrategy legacyOnlyNaming() {
            return prefixing("L_");
        }
    }

    /** A naming strategy whose class carries the qualifier. */
    @Qualifier("reports")
    static class ClassQualifiedNaming extends DefaultNamingStrategy {

        @Override
        public String getTableName(Class<?> type) {
            return "C_" + super.getTableName(type);
        }
    }

    /** A {@code @Bean} method without a qualifier, returning a class that carries one. */
    @Configuration(proxyBeanMethods = false)
    static class ClassQualifiedNamingMethod {

        @Bean
        ClassQualifiedNaming classQualifiedNaming() {
            return new ClassQualifiedNaming();
        }
    }

    /** A qualifier type of its own, as Spring's reference documentation shows custom qualifiers. */
    @Target({ElementType.METHOD, ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER})
    @Retention(RetentionPolicy.RUNTIME)
    @Qualifier
    @interface Reports {
    }

    @Configuration(proxyBeanMethods = false)
    static class ReportsNaming {

        @Bean
        @Qualifier("reports")
        NamingStrategy reportsNaming() {
            return prefixing("R_");
        }
    }

    /** Declares the books store with PostgreSQL's dialect, defined in its store class. */
    @Configuration(proxyBeanMethods = false)
    @SynapseDataStore("books")
    static class BooksStoreWithPostgresDialect {
        @Bean
        JdbcDialect booksJdbcDialect() {
            return JdbcPostgresDialect.INSTANCE;
        }
    }
    /** The legacy store's vendor: an H2 URL, with PostgreSQL's dialect. */
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class PostgresDialectVendor extends H2TestVendor {
        @Override
        public String name() {
            return "postgres-dialect";
        }
        @Override
        public boolean supports(String jdbcUrl) {
            return jdbcUrl.startsWith("jdbc:h2:mem:jdbc-legacy");
        }
        @Override
        public String jdbcDialect() {
            return JdbcPostgresDialect.class.getName();
        }
    }
    /** A vendor for H2 URLs that names the given JDBC dialect class. */
    static class DialectVendor extends H2TestVendor {

        private final String dialect;

        DialectVendor(String dialect) {
            this.dialect = dialect;
        }

        @Override
        public String name() {
            return "test";
        }

        @Override
        public String jdbcDialect() {
            return dialect;
        }
    }

    /** Its {@code INSTANCE} is not a dialect. */
    public static class NotADialect {

        public static final String INSTANCE = "not a dialect";
    }

    /** Its {@code INSTANCE} is not static. */
    public static class InstanceFieldDialect {

        public final JdbcDialect INSTANCE = JdbcH2Dialect.INSTANCE;
    }

    @Configuration(proxyBeanMethods = false)
    static class UserAuditingCallback {

        static final BeforeConvertCallback<Object> CALLBACK = aggregate -> aggregate;

        @Bean
        BeforeConvertCallback<Object> synapseJdbcAuditingCallback() {
            return CALLBACK;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserJdbcStoreFactory {

        static @Nullable JdbcStoreFactory created;

        @Bean
        JdbcStoreFactory synapseJdbcStoreFactory(ConfigurableListableBeanFactory beanFactory,
                ApplicationContext applicationContext, StoreFactory storeFactory) {
            JdbcStoreFactory factory = new JdbcStoreFactory(beanFactory, applicationContext, storeFactory);
            created = factory;
            return factory;
        }
    }
}
