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
package io.americanexpress.synapse.data.internal.store;

import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.RepositoryDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.TransactionManagementConfigurer;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.data.autoconfigure.SynapseDataAutoConfiguration;
import io.americanexpress.synapse.data.fixture.H2TestVendor;
import io.americanexpress.synapse.data.fixture.books.BooksStore;
import io.americanexpress.synapse.data.fixture.legacy.LegacyStore;
import io.americanexpress.synapse.data.fixture.repositories.Shelf;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The startup check that an unqualified {@code @Transactional} can choose a transaction manager: it fails with several
 * stores and none primary.
 */
class UnqualifiedTransactionCheckTest {

    private static final String CHECK = "synapseUnqualifiedTransactionCheck";

    private static final String DESCRIPTION = "There are 2 stores and none is primary, so an unqualified "
            + "@Transactional can't choose a transaction manager: ";

    private static final String ACTION = "Name the store (@Transactional(\"<store>\")), or set "
            + "synapse.data.stores.<name>.primary: true on one store.";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(
                    AutoConfigurations.of(SynapseCoreAutoConfiguration.class, SynapseDataAutoConfiguration.class))
            .withUserConfiguration(H2TestVendor.class, TransactionManagers.class);

    private final ApplicationContextRunner books = runner
            .withUserConfiguration(BooksStore.class)
            .withPropertyValues("synapse.data.stores.books.url=jdbc:h2:mem:utx-books;DB_CLOSE_DELAY=-1",
                    "synapse.data.stores.books.username=sa", "synapse.data.stores.books.password=");

    private final ApplicationContextRunner stores = books
            .withUserConfiguration(LegacyStore.class)
            .withPropertyValues("synapse.data.stores.legacy.url=jdbc:h2:mem:utx-legacy;DB_CLOSE_DELAY=-1",
                    "synapse.data.stores.legacy.username=sa", "synapse.data.stores.legacy.password=");

    @Test
    void unqualifiedMethodFails() {
        stores
                .withBean("ledger", Ledger.class)
                .run(failsWith(DESCRIPTION + Ledger.class.getName() + "#post."));
    }

    @Test
    void unqualifiedClassLevelFails() {
        stores
                .withBean("journal", Journal.class)
                .run(failsWith(DESCRIPTION + Journal.class.getName() + "#close, " + Journal.class.getName()
                        + "#write."));
    }

    @Test
    void qualifiedPasses() {
        stores
                .withBean("qualified", Qualified.class)
                .withBean("qualifiedJournal", QualifiedJournal.class)
                .run(context -> assertThat(context).hasNotFailed().hasBean(CHECK));
    }

    @Test
    void primaryMakesUnqualifiedValid() {
        stores
                .withBean("ledger", Ledger.class)
                .withPropertyValues("synapse.data.stores.legacy.primary=true")
                .run(context -> assertThat(context).hasNotFailed().hasBean(CHECK));
    }

    @Test
    void oneStoreNeverChecks() {
        books
                .withBean("ledger", Ledger.class)
                .run(context -> assertThat(context).hasNotFailed().hasBean(CHECK));
    }

    /**
     * Spring resolves an unqualified {@code @Transactional} to a primary transaction manager of the application's own.
     * The check reads its definition: the lazy bean is never created.
     */
    @Test
    void aPrimaryTransactionManagerMakesUnqualifiedValid() {
        LazyTransactionManagers.CREATED.set(0);
        stores
                .withUserConfiguration(PrimaryTransactionManager.class)
                .withBean("ledger", Ledger.class)
                .run(context -> assertThat(context).hasNotFailed().hasBean(CHECK));
        assertThat(LazyTransactionManagers.CREATED).as("the primary transaction manager isn't created").hasValue(0);
    }

    /**
     * Spring resolves an unqualified {@code @Transactional} to the transaction manager a
     * {@code TransactionManagementConfigurer} names. The check reads its definition: the lazy bean is never created.
     */
    @Test
    void aTransactionManagementConfigurerMakesUnqualifiedValid() {
        LazyTransactionManagers.CREATED.set(0);
        stores
                .withUserConfiguration(Configurer.class)
                .withBean("ledger", Ledger.class)
                .run(context -> assertThat(context).hasNotFailed().hasBean(CHECK));
        assertThat(LazyTransactionManagers.CREATED).as("the configurer isn't created").hasValue(0);
    }

    /** Spring (6.2 and later) takes a type-level {@code @Qualifier} as the qualifier of the class's transactions. */
    @Test
    void aTypeLevelQualifierCountsAsQualified() {
        stores
                .withBean("qualifiedLedger", QualifiedLedger.class)
                .withBean("beanNamedLedger", BeanNamedLedger.class)
                .withBean("ledger", Ledger.class)
                .run(failsWith(DESCRIPTION + Ledger.class.getName() + "#post."));
    }

    /**
     * Spring ignores a type-level {@code @Qualifier} that names no transaction manager, such as one that only chooses
     * between injected beans, and falls back to the default transaction manager, which several stores don't have.
     */
    @Test
    void aTypeLevelQualifierNamingNoTransactionManagerIsUnqualified() {
        stores
                .withBean("fastLedger", FastLedger.class)
                .run(failsWith(DESCRIPTION + FastLedger.class.getName() + "#post."));
    }

    /** A {@code @Transactional} that the class has only from an interface it implements. */
    @Test
    void anInterfaceTransactionalIsChecked() {
        stores
                .withBean("settlements", LedgerSettlements.class)
                .run(failsWith(DESCRIPTION + LedgerSettlements.class.getName() + "#settle."));
    }

    @Test
    void listsAtMostFiveMethods() {
        String busy = Busy.class.getName();
        stores
                .withBean("busy", Busy.class)
                .run(failsWith(DESCRIPTION + busy + "#archive, " + busy + "#balance, " + busy + "#close, " + busy
                        + "#credit, " + busy + "#debit and 2 more."));
    }

    @Test
    void repositoriesAndSpringClassesAreSkipped() {
        stores
                .withBean("transactionTemplate", TransactionTemplate.class,
                        () -> new TransactionTemplate(new StubTransactionManager()))
                .withBean("shelfRepository", RepositoryFactory.class,
                        () -> new RepositoryFactory(LedgerRepository.class))
                .withBean("definedRepository", RepositoryFactory.class,
                        () -> new RepositoryFactory(DefinedRepository.class))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasBean(CHECK);
                    assertThat(context.getBean("shelfRepository")).isInstanceOf(LedgerRepository.class);
                    assertThat(context.getBean("definedRepository")).isInstanceOf(DefinedRepository.class);
                });
    }

    /**
     * Each skip rule on its own: Spring's package (a Spring class with an unqualified {@code @Transactional} that isn't
     * a repository, such as one from Spring Batch, would be skipped by it alone), a {@code Repository} subtype, and a
     * {@code @RepositoryDefinition}.
     */
    @Test
    void skipsSpringPackagesAndRepositoryTypes() {
        assertThat(UnqualifiedTransactionCheck.isSkipped(TransactionTemplate.class)).isTrue();
        assertThat(UnqualifiedTransactionCheck.isSkipped(LedgerRepository.class)).isTrue();
        assertThat(UnqualifiedTransactionCheck.isSkipped(DefinedRepository.class)).isTrue();
        assertThat(UnqualifiedTransactionCheck.isSkipped(Ledger.class)).isFalse();
        assertThat(UnqualifiedTransactionCheck.isSkipped(Postings.class)).isFalse();
    }

    /**
     * With transaction management on, the beans are proxies: the check reads the class behind a class-based proxy and
     * behind an interface-based one.
     */
    @Test
    void proxiedBeansAreReadThroughTheProxy() {
        stores
                .withUserConfiguration(TransactionManagement.class)
                .withBean("postings", Postings.class, LedgerPostings::new)
                .run(context -> {
                    assertThat(failure(context).description())
                            .isEqualTo(DESCRIPTION + LedgerPostings.class.getName() + "#post.");
                });
        stores
                .withUserConfiguration(TransactionManagement.class)
                .withBean("ledger", Ledger.class)
                .withPropertyValues("synapse.data.stores.legacy.primary=true")
                .run(context -> assertThat(AopUtils.isCglibProxy(context.getBean("ledger"))).isTrue());
    }

    /**
     * With lazy initialization, the check reads the types of the beans that don't exist yet, and creates none of them.
     */
    @Test
    void lazyAndPrototypeBeansAreCheckedWithoutBeingCreated() {
        Created.COUNT.set(0);
        stores
                .withInitializer(context -> context
                        .addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()))
                .withBean("ledger", Ledger.class)
                .run(failsWith(DESCRIPTION + Ledger.class.getName() + "#post."));
        stores
                .withUserConfiguration(PrototypeConfiguration.class)
                .run(failsWith(DESCRIPTION + Created.class.getName() + "#post."));
        assertThat(Created.COUNT).hasValue(0);
    }

    @Test
    void activatesWithTransactionSupport() {
        runner.run(context -> assertThat(context).hasSingleBean(UnqualifiedTransactionCheck.class).hasBean(CHECK));
    }

    @Test
    void backsOffToABeanWithItsName() {
        stores
                .withBean(CHECK, String.class, () -> "mine")
                .withBean("ledger", Ledger.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(UnqualifiedTransactionCheck.class);
                    assertThat(context.getBean(CHECK)).isEqualTo("mine");
                });
    }

    @Test
    void absentWithoutTransactionSupport() {
        runner
                .withClassLoader(new FilteredClassLoader(TransactionAttributeSource.class))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(CHECK)
                        .doesNotHaveBean(UnqualifiedTransactionCheck.class));
    }

    private static ContextConsumer<AssertableApplicationContext> failsWith(String description) {
        return context -> {
            SynapseConfigurationException failure = failure(context);
            assertThat(failure.description()).isEqualTo(description);
            assertThat(failure.action()).isEqualTo(ACTION);
        };
    }

    private static SynapseConfigurationException failure(AssertableApplicationContext context) {
        assertThat(context).hasFailed();
        Throwable root = NestedExceptionUtils
                .getMostSpecificCause(Objects.requireNonNull(context.getStartupFailure()));
        assertThat(root).isInstanceOf(SynapseConfigurationException.class);
        return (SynapseConfigurationException) root;
    }

    /** A transaction manager per store, qualified with its name. */
    @Configuration(proxyBeanMethods = false)
    static class TransactionManagers {

        @Bean
        @Qualifier("books")
        PlatformTransactionManager booksTransactionManager() {
            return new StubTransactionManager();
        }

        @Bean
        @Qualifier("legacy")
        PlatformTransactionManager legacyTransactionManager() {
            return new StubTransactionManager();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionManagement {
    }

    @Configuration(proxyBeanMethods = false)
    static class PrototypeConfiguration {

        @Bean
        @Scope("prototype")
        Created created() {
            return new Created();
        }
    }

    /** Counts the lazy beans the check must not create. */
    static final class LazyTransactionManagers {

        static final AtomicInteger CREATED = new AtomicInteger();

        private LazyTransactionManagers() {
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PrimaryTransactionManager {

        @Bean
        @Primary
        @Lazy
        PlatformTransactionManager applicationTransactionManager() {
            LazyTransactionManagers.CREATED.incrementAndGet();
            return new StubTransactionManager();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Configurer {

        @Bean
        @Lazy
        TransactionManagementConfigurer transactionManagementConfigurer() {
            LazyTransactionManagers.CREATED.incrementAndGet();
            StubTransactionManager transactionManager = new StubTransactionManager();
            return () -> transactionManager;
        }
    }

    @Qualifier("books")
    static class QualifiedLedger {

        @Transactional
        public void post() {
            // On the books store, through the class's qualifier.
        }
    }

    /** Names the legacy store's transaction manager by its bean name. */
    @Qualifier("legacyTransactionManager")
    static class BeanNamedLedger {

        @Transactional
        public void post() {
            // On the legacy store, through the class's qualifier.
        }
    }

    /** A qualifier that names no transaction manager. */
    @Qualifier("fast")
    static class FastLedger {

        @Transactional
        public void post() {
            // On the default transaction manager.
        }
    }

    interface Settlements {

        @Transactional
        void settle();
    }

    static class LedgerSettlements implements Settlements {

        @Override
        public void settle() {
            // Unqualified, from the interface.
        }
    }

    static class StubTransactionManager implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(@Nullable TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            // Nothing to commit.
        }

        @Override
        public void rollback(TransactionStatus status) {
            // Nothing to roll back.
        }
    }

    static class Ledger {

        @Transactional
        public void post() {
            // A transaction on the default transaction manager.
        }

        @Transactional("books")
        public void audit() {
            // A transaction on the books store.
        }

        public void read() {
            // No transaction.
        }

        @Transactional
        private void hidden() {
            // A proxy can't intercept a private method, so its annotation does nothing.
        }

        @Transactional
        public static void utility() {
            // Nor a static one.
        }

        void callHidden() {
            hidden();
        }
    }

    @Transactional
    static class Journal {

        public void write() {
            // Unqualified, from the class.
        }

        protected void close() {
            // Unqualified too: class-based proxies intercept protected methods.
        }

        @Transactional("legacy")
        public void archive() {
            // The method's own qualifier wins.
        }
    }

    @Transactional("legacy")
    static class QualifiedJournal {

        public void write() {
            // On the legacy store.
        }
    }

    static class Qualified {

        @Transactional("books")
        public void post() {
            // On the books store.
        }

        @Transactional(transactionManager = "legacy")
        public void archive() {
            // On the legacy store.
        }
    }

    static class Busy {

        @Transactional
        public void post() {
        }

        @Transactional
        public void open() {
        }

        @Transactional
        public void debit() {
        }

        @Transactional
        public void credit() {
        }

        @Transactional
        public void close() {
        }

        @Transactional
        public void balance() {
        }

        @Transactional
        public void archive() {
        }

        @Transactional
        public void archive(String reason) {
            // An overload is listed once.
        }
    }

    interface Postings {

        void post();
    }

    static class LedgerPostings implements Postings {

        @Override
        @Transactional
        public void post() {
            // Unqualified, behind an interface-based proxy.
        }
    }

    static class Created {

        static final AtomicInteger COUNT = new AtomicInteger();

        Created() {
            COUNT.incrementAndGet();
        }

        @Transactional
        public void post() {
            // Unqualified.
        }
    }

    /** A repository whose transactions Spring Data runs on its store's transaction manager. */
    @Transactional
    interface LedgerRepository extends Repository<Shelf, Long> {

        @Transactional
        void archive();
    }

    @RepositoryDefinition(domainClass = Shelf.class, idClass = Long.class)
    @Transactional
    interface DefinedRepository {

        void archive();
    }

    /** Stands in for Spring Data's repository factory beans, which expose the repository interface as their type. */
    static class RepositoryFactory implements FactoryBean<Object> {

        private final Class<?> repository;

        RepositoryFactory(Class<?> repository) {
            this.repository = repository;
        }

        @Override
        public Object getObject() {
            return Proxy
                    .newProxyInstance(repository.getClassLoader(), new Class<?>[]{repository},
                            (proxy, method, arguments) -> null);
        }

        @Override
        public Class<?> getObjectType() {
            return repository;
        }
    }
}
