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
package io.americanexpress.synapse.data.mixed;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;

import org.hibernate.bytecode.internal.bytebuddy.BytecodeProviderImpl;
import org.hibernate.bytecode.spi.BytecodeProvider;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.aot.test.generate.TestGenerationContext;
import org.springframework.beans.factory.annotation.BeanFactoryAnnotationUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizationAutoConfiguration;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.aot.ApplicationContextAotGenerator;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.test.tools.CompileWithForkedClassLoader;
import org.springframework.core.test.tools.TestCompiler;
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions;
import org.springframework.data.jdbc.core.dialect.JdbcH2Dialect;
import org.springframework.data.jdbc.core.dialect.JdbcPostgresDialect;
import org.springframework.javapoet.ClassName;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.data.autoconfigure.SynapseDataAutoConfiguration;
import io.americanexpress.synapse.data.jdbc.autoconfigure.SynapseDataJdbcAutoConfiguration;
import io.americanexpress.synapse.data.jpa.autoconfigure.SynapseDataJpaAutoConfiguration;
import io.americanexpress.synapse.data.mixed.fixture.H2TestVendor;
import io.americanexpress.synapse.data.mixed.fixture.archive.ArchiveStore;
import io.americanexpress.synapse.data.mixed.fixture.archive.Note;
import io.americanexpress.synapse.data.mixed.fixture.archive.NoteRepository;
import io.americanexpress.synapse.data.mixed.fixture.books.Book;
import io.americanexpress.synapse.data.mixed.fixture.books.BookRepository;
import io.americanexpress.synapse.data.mixed.fixture.books.BooksStore;
import io.americanexpress.synapse.data.mixed.fixture.legacy.LegacyStore;
import io.americanexpress.synapse.data.mixed.fixture.legacy.Shelf;
import io.americanexpress.synapse.data.mixed.fixture.legacy.ShelfRepository;
import io.americanexpress.synapse.data.mixed.fixture.notes.NotesStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Runs Spring's AOT processing on applications with data stores (books on JPA and legacy on JDBC, both on H2),
 * compiles the generated code and starts it, as a JVM with {@code -Dspring.aot.enabled=true} would. The started
 * context gets its properties as a service does at run time, so they may differ from the build's.
 */
// Generated code calls package-private members, so it must be defined in the same class loader as the test classes.
@CompileWithForkedClassLoader
class StoreAotProcessingTest {

    private static final String HIBERNATE_SERVICE = "org.hibernate.orm.service";

    private static final String BATCH_SIZE = "hibernate.jdbc.batch_size";

    private static final String FETCH_SIZE = "hibernate.jdbc.fetch_size";

    private static final Map<String, Object> STORES = Map
            .of("synapse.data.stores.books.url", "jdbc:h2:mem:aot-books;DB_CLOSE_DELAY=-1",
                    "synapse.data.stores.books.username", "sa", "synapse.data.stores.books.password", "",
                    "synapse.data.stores.legacy.url", "jdbc:h2:mem:aot-legacy;DB_CLOSE_DELAY=-1",
                    "synapse.data.stores.legacy.username", "sa", "synapse.data.stores.legacy.password", "");

    private static final Map<String, Object> NOTES = Map
            .of("synapse.data.stores.notes.url", "jdbc:h2:mem:aot-notes;DB_CLOSE_DELAY=-1",
                    "synapse.data.stores.notes.username", "sa", "synapse.data.stores.notes.password", "");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations
                    .of(SynapseCoreAutoConfiguration.class, SynapseDataAutoConfiguration.class,
                            SynapseDataJpaAutoConfiguration.class, SynapseDataJdbcAutoConfiguration.class,
                            TransactionManagerCustomizationAutoConfiguration.class))
            .withUserConfiguration(H2TestVendor.class);

    private final ApplicationContextRunner booksAndLegacy = runner
            .withUserConfiguration(BooksStore.class, LegacyStore.class);

    /**
     * Silences Hibernate's HHH010505 WARN (with its stack trace) while this class runs. The forked class loader makes
     * Hibernate's ServiceLoader see its ByteBuddy {@code BytecodeProvider} twice, and the copy from the other loader
     * fails the subtype check, which Hibernate ignores. {@code twoJpaStoresKeepTheirRepositoriesAndAuditing} checks
     * that the provider is still ByteBuddy's. Only the {@code org.hibernate.orm.service} logger is turned down, to
     * ERROR, and only for this class.
     */
    @BeforeAll
    static void silenceTheForkedLoadersServiceWarning() {
        LoggingSystem.get(StoreAotProcessingTest.class.getClassLoader()).setLogLevel(HIBERNATE_SERVICE, LogLevel.ERROR);
    }

    @AfterAll
    static void restoreTheServiceLogger() {
        LoggingSystem.get(StoreAotProcessingTest.class.getClassLoader()).setLogLevel(HIBERNATE_SERVICE, null);
    }

    @Test
    void storeBeansAreGeneratedAheadOfTime() {
        aot(booksAndLegacy, STORES, STORES, context -> assertThat(context.getBeanDefinitionNames())
                .contains("booksDataSource", "booksEntityManagerFactory", "booksTransactionManager",
                        "legacyDataSource", "legacyJdbcAggregateTemplate", "legacyTransactionManager", "bookRepository",
                        "legacyItemRepository", "shelfRepository"));
    }

    @Test
    void generatedContextCreatesTheBeans() {
        aot(booksAndLegacy, STORES, STORES, context -> {
            context.refresh();

            assertThat(context.getBeansOfType(DataSource.class).keySet())
                    .containsExactlyInAnyOrder("booksDataSource", "legacyDataSource");
            assertThat(BeanFactoryAnnotationUtils
                    .qualifiedBeanOfType(context.getBeanFactory(), TransactionManager.class, "books"))
                    .isSameAs(context.getBean("booksTransactionManager"));
            assertThat(BeanFactoryAnnotationUtils
                    .qualifiedBeanOfType(context.getBeanFactory(), TransactionManager.class, "legacy"))
                    .isSameAs(context.getBean("legacyTransactionManager"));
            BookRepository books = context.getBean(BookRepository.class);
            new TransactionTemplate(context.getBean("booksTransactionManager", PlatformTransactionManager.class))
                    .executeWithoutResult(status -> books.save(new Book(1, "Dune")));
            assertThat(books.findById(1L)).map(Book::getTitle).hasValue("Dune");
            // The @Sequence callback is on the legacy store's own aggregate template. The in-memory database lives as
            // long as the JVM, so the ids are compared with each other and with the sequence's start (100), not with
            // a fixed value.
            ShelfRepository shelves = context.getBean(ShelfRepository.class);
            long first = Objects.requireNonNull(shelves.save(new Shelf("fiction")).getId());
            Long second = shelves.save(new Shelf("poetry")).getId();
            assertThat(first).isGreaterThanOrEqualTo(100L);
            assertThat(second).isEqualTo(first + 1);
        });
    }

    /**
     * Two JPA stores keep their own repositories and EntityManagerFactories in generated code, and Synapse's entity
     * listener assigns ids and audit stamps there.
     */
    @Test
    void twoJpaStoresKeepTheirRepositoriesAndAuditing() {
        Map<String, Object> stores = new HashMap<>(STORES);
        stores.put("synapse.data.stores.archive.url", "jdbc:h2:mem:aot-archive;DB_CLOSE_DELAY=-1");
        stores.put("synapse.data.stores.archive.username", "sa");
        stores.put("synapse.data.stores.archive.password", "");

        aot(booksAndLegacy.withUserConfiguration(ArchiveStore.class), stores, stores, context -> {
            context.refresh();

            assertThat(context.getBeansOfType(EntityManagerFactory.class).keySet())
                    .containsExactlyInAnyOrder("booksEntityManagerFactory", "archiveEntityManagerFactory");
            assertThat(BeanFactoryAnnotationUtils
                    .qualifiedBeanOfType(context.getBeanFactory(), EntityManagerFactory.class, "archive"))
                    .isSameAs(context.getBean("archiveEntityManagerFactory"));
            Note note = context.getBean(NoteRepository.class).save(new Note("kept"));
            assertThat(note.getId()).isNotNull().satisfies(id -> assertThat(id.version()).isEqualTo(7));
            assertThat(note.getCreatedBy()).isEqualTo("system");
            // The HHH010505 warning this class silences is harmless: Hibernate still has its ByteBuddy provider.
            assertThat(context
                    .getBean("archiveEntityManagerFactory", EntityManagerFactory.class)
                    .unwrap(SessionFactoryImplementor.class)
                    .getServiceRegistry()
                    .requireService(BytecodeProvider.class)).isInstanceOf(BytecodeProviderImpl.class);
            assertThat(context.getBean(NoteRepository.class).findAll()).extracting(Note::getBody).contains("kept");
        });
    }

    /** A single store is the primary one, and a bean its store class replaces gets the store's qualifier. */
    @Test
    void aSingleStoresReplacedBeanIsQualifiedAndPrimary() {
        aot(runner.withUserConfiguration(NotesStore.class), NOTES, NOTES, context -> {
            context.refresh();

            assertThat(context.getBean(DataSource.class)).isSameAs(context.getBean("notesDataSource"));
            Object transactionManager = context.getBean("notesTransactionManager");
            assertThat(transactionManager).isInstanceOf(JdbcTransactionManager.class);
            assertThat(context.getBean(TransactionManager.class)).isSameAs(transactionManager);
            assertThat(BeanFactoryAnnotationUtils
                    .qualifiedBeanOfType(context.getBeanFactory(), TransactionManager.class, "notes"))
                    .isSameAs(transactionManager);
        });
    }

    /** The build fixes which store is primary, so a different value at run time fails startup. */
    @Test
    void aPrimaryChangedAfterTheBuildFails() {
        Map<String, Object> runtime = new HashMap<>(STORES);
        runtime.put("synapse.data.stores.books.primary", "true");

        aot(booksAndLegacy, STORES, runtime, context -> assertPrimaryChanged(startupFailure(context)));
    }

    /** The check binds as Boot does, so it sees a primary set by an environment variable too. */
    @Test
    void aPrimaryFromAnEnvironmentVariableIsChecked() {
        aot(booksAndLegacy, STORES, STORES, context -> {
            // Boot maps the variable only in a source named systemEnvironment.
            context
                    .getEnvironment()
                    .getPropertySources()
                    .addLast(new SystemEnvironmentPropertySource(
                            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            Map.of("SYNAPSE_DATA_STORES_BOOKS_PRIMARY", "true")));

            assertPrimaryChanged(startupFailure(context));
        });
    }

    private static void assertPrimaryChanged(SynapseConfigurationException failure) {
        assertThat(failure.description())
                .isEqualTo("The store 'books' sets synapse.data.stores.books.primary differently from when its beans "
                        + "were registered (in AOT mode, at build time).");
        assertThat(failure.action())
                .isEqualTo("Set synapse.data.stores.books.primary in config.yaml or application.yaml, the same in "
                        + "every environment and not in a @PropertySource, and in AOT mode build again.");
    }

    /** The store checks run again when the generated context starts, with its own properties. */
    @Test
    void aStoreAddedAfterTheBuildFails() {
        Map<String, Object> runtime = new HashMap<>(STORES);
        runtime.put("synapse.data.stores.orders.url", "jdbc:h2:mem:aot-orders");

        aot(booksAndLegacy, STORES, runtime, context -> assertThat(startupFailure(context).description())
                .isEqualTo("synapse.data.stores.orders is configured, but no @SynapseDataStore(\"orders\") declares "
                        + "the store."));
    }

    /**
     * Customizers qualified with the archive store change only that store, in the {@code @Order} of their
     * {@code @Bean} methods, as on the JVM: generated bean definitions carry no factory method metadata.
     */
    @Test
    void aStoreQualifiedCustomizerChangesOnlyItsStoreInOrder() {
        Map<String, Object> stores = new HashMap<>(STORES);
        stores.put("synapse.data.stores.archive.url", "jdbc:h2:mem:aot-archive-customized;DB_CLOSE_DELAY=-1");
        stores.put("synapse.data.stores.archive.username", "sa");
        stores.put("synapse.data.stores.archive.password", "");

        aot(booksAndLegacy.withUserConfiguration(ArchiveStore.class, ArchiveCustomizers.class), stores, stores,
                context -> {
                    context.refresh();

                    assertThat(jpaProperties(context, "booksEntityManagerFactory"))
                            .doesNotContainKeys(BATCH_SIZE, FETCH_SIZE);
                    assertThat(jpaProperties(context, "archiveEntityManagerFactory"))
                            .containsEntry(BATCH_SIZE, "77")
                            .containsEntry(FETCH_SIZE, "20");
                });
    }

    /** {@code ddl-auto: update} runs on the EntityManagerFactory Synapse builds, read from generated definitions. */
    @Test
    void ddlAutoUpdateStartsOnSynapsesEntityManagerFactory() {
        Map<String, Object> stores = new HashMap<>(STORES);
        stores.put("synapse.data.stores.books.url", "jdbc:h2:mem:aot-books-update;DB_CLOSE_DELAY=-1");
        stores.put("synapse.data.stores.books.jpa.hibernate.ddl-auto", "update");
        stores.put("synapse.data.stores.books.allow-ddl-auto", "true");
        stores.put("synapse.data.stores.books.migrations.enabled", "false");

        aot(booksAndLegacy, stores, stores, context -> {
            context.refresh();

            BookRepository books = context.getBean(BookRepository.class);
            new TransactionTemplate(context.getBean("booksTransactionManager", PlatformTransactionManager.class))
                    .executeWithoutResult(status -> books.save(new Book(1, "Dune")));
            assertThat(books.findById(1L)).map(Book::getTitle).hasValue("Dune");
        });
    }

    /**
     * One unqualified JdbcCustomConversions for JDBC stores whose dialects differ fails in generated code too. The
     * build has two H2 stores and passes; at run time the notes store's URL picks a vendor with PostgreSQL's dialect.
     */
    @Test
    void oneUnqualifiedConversionsForStoresOfDifferentDialectsFails() {
        Map<String, Object> build = new HashMap<>(NOTES);
        build.put("synapse.data.stores.legacy.url", "jdbc:h2:mem:aot-legacy;DB_CLOSE_DELAY=-1");
        build.put("synapse.data.stores.legacy.username", "sa");
        build.put("synapse.data.stores.legacy.password", "");
        // Spring Data JDBC's AOT repository processing looks up one JdbcDialect.
        build.put("synapse.data.stores.legacy.primary", "true");
        Map<String, Object> runtime = new HashMap<>(build);
        runtime.put("synapse.data.stores.notes.url", PostgresDialectVendor.URL_PREFIX + ";DB_CLOSE_DELAY=-1");

        aot(runner
                .withUserConfiguration(LegacyStore.class, NotesStore.class, PostgresDialectVendor.class,
                        ApplicationConversions.class),
                build, runtime,
                context -> assertThat(startupFailure(context).description())
                        .isEqualTo("The JdbcCustomConversions bean applicationConversions would apply to JDBC stores "
                                + "whose dialects differ: legacy (" + JdbcH2Dialect.class.getName() + "), notes ("
                                + JdbcPostgresDialect.class.getName() + ")."));
    }

    @Test
    void migrationsAreResourceHints() {
        booksAndLegacy.withPropertyValues(values(STORES)).prepare(context -> {
            TestGenerationContext generationContext = new TestGenerationContext();
            new ApplicationContextAotGenerator()
                    .processAheadOfTime((GenericApplicationContext) context.getSourceApplicationContext(),
                            generationContext);

            assertThat(RuntimeHintsPredicates.resource().forResource("db/migration/legacy/V2__shelf.sql"))
                    .accepts(generationContext.getRuntimeHints());
        });
    }

    private static Map<String, Object> jpaProperties(GenericApplicationContext context, String entityManagerFactory) {
        return context.getBean(entityManagerFactory, EntityManagerFactory.class).getProperties();
    }

    /** Two customizers qualified with the archive store, whose names sort opposite to their {@code @Order}. */
    @Configuration(proxyBeanMethods = false)
    static class ArchiveCustomizers {

        @Bean
        @Qualifier("archive")
        @Order(2)
        HibernatePropertiesCustomizer archiveFirst() {
            return properties -> {
                properties.put(BATCH_SIZE, "77");
                properties.put(FETCH_SIZE, "20");
            };
        }

        @Bean
        @Qualifier("archive")
        @Order(1)
        HibernatePropertiesCustomizer archiveSecond() {
            return properties -> properties.put(FETCH_SIZE, "10");
        }
    }

    /** A vendor for H2 URLs with a prefix of their own, with PostgreSQL's dialect. */
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class PostgresDialectVendor extends H2TestVendor {

        static final String URL_PREFIX = "jdbc:h2:mem:aot-postgres-dialect";

        @Override
        public String name() {
            return "postgres-dialect";
        }

        @Override
        public boolean supports(String jdbcUrl) {
            return jdbcUrl.startsWith(URL_PREFIX);
        }

        @Override
        public String jdbcDialect() {
            return JdbcPostgresDialect.class.getName();
        }
    }

    /** One unqualified conversions bean, which holds H2's store conversions. */
    @Configuration(proxyBeanMethods = false)
    static class ApplicationConversions {

        @Bean
        JdbcCustomConversions applicationConversions() {
            return JdbcCustomConversions.of(JdbcH2Dialect.INSTANCE, List.of());
        }
    }

    private static SynapseConfigurationException startupFailure(GenericApplicationContext context) {
        Throwable failure = catchThrowable(context::refresh);
        assertThat(failure).isNotNull();
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(failure);
        assertThat(cause).isInstanceOf(SynapseConfigurationException.class);
        return (SynapseConfigurationException) cause;
    }

    /**
     * Processes the runner's context ahead of time with the {@code build} properties, compiles the generated code,
     * and hands a context initialized from it, with the {@code runtime} properties and not yet refreshed, to
     * {@code assertions}.
     */
    private static void aot(ApplicationContextRunner runner, Map<String, Object> build, Map<String, Object> runtime,
            Consumer<GenericApplicationContext> assertions) {
        runner.withPropertyValues(values(build)).prepare(context -> {
            TestGenerationContext generationContext = new TestGenerationContext();
            ClassName initializerName = new ApplicationContextAotGenerator()
                    .processAheadOfTime((GenericApplicationContext) context.getSourceApplicationContext(),
                            generationContext);
            generationContext.writeGeneratedContent();
            TestCompiler.forSystem().with(generationContext).compile(compiled -> {
                try (GenericApplicationContext aotContext = new GenericApplicationContext()) {
                    aotContext.getEnvironment().getPropertySources().addFirst(new MapPropertySource("run", runtime));
                    @SuppressWarnings("unchecked")
                    ApplicationContextInitializer<GenericApplicationContext> initializer = compiled
                            .getInstance(ApplicationContextInitializer.class, initializerName.toString());
                    initializer.initialize(aotContext);
                    assertions.accept(aotContext);
                }
            });
        });
    }

    private static String[] values(Map<String, Object> properties) {
        return properties
                .entrySet()
                .stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
    }
}
