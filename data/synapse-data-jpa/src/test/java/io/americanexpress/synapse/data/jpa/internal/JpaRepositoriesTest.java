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

import java.sql.Connection;
import java.util.Objects;
import jakarta.persistence.EntityManagerFactory;

import com.zaxxer.hikari.HikariDataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues.ValueHolder;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.AutowireCandidateQualifier;
import org.springframework.beans.factory.support.BeanDefinitionOverrideException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.data.repository.Repository;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.data.autoconfigure.SynapseDataAutoConfiguration;
import io.americanexpress.synapse.data.jpa.autoconfigure.SynapseDataJpaAutoConfiguration;
import io.americanexpress.synapse.data.jpa.fixture.H2TestVendor;
import io.americanexpress.synapse.data.jpa.fixture.archive.ArchiveStore;
import io.americanexpress.synapse.data.jpa.fixture.books.Book;
import io.americanexpress.synapse.data.jpa.fixture.books.BookRepository;
import io.americanexpress.synapse.data.jpa.fixture.books.BookRepositoryCustomImpl;
import io.americanexpress.synapse.data.jpa.fixture.books.BooksStore;
import io.americanexpress.synapse.data.jpa.fixture.legacy.LegacyItemRepository;
import io.americanexpress.synapse.data.jpa.fixture.legacy.LegacyStore;
import io.americanexpress.synapse.data.jpa.fixture.legacyorders.LegacyOrdersStore;
import io.americanexpress.synapse.data.jpa.fixture.ownrepository.BooksStoreWithOwnRepository;
import io.americanexpress.synapse.data.jpa.fixture.ownrepository.ShelfRepository;

import static org.assertj.core.api.Assertions.assertThat;

class JpaRepositoriesTest {

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
    void repositoriesBindToTheirStore() {
        books.withUserConfiguration(LegacyStore.class).withPropertyValues(store("legacy")).run(context -> {
            assertThat(context).hasNotFailed();
            assertBoundTo(context.getBeanFactory(), "bookRepository", "books");
            assertBoundTo(context.getBeanFactory(), "legacyItemRepository", "legacy");

            BookRepository repository = context.getBean(BookRepository.class);
            new TransactionTemplate(context.getBean("legacyTransactionManager", PlatformTransactionManager.class))
                    .executeWithoutResult(status -> {
                        repository.save(new Book(1, "Dune"));
                        status.setRollbackOnly();
                    });
            assertThat(repository.existsById(1L))
                    .as("saved in a books transaction of its own, not the legacy one that rolled back")
                    .isTrue();
            new TransactionTemplate(context.getBean("booksTransactionManager", PlatformTransactionManager.class))
                    .executeWithoutResult(status -> {
                        repository.save(new Book(2, "Emma"));
                        status.setRollbackOnly();
                    });
            assertThat(repository.existsById(2L)).as("joined the books transaction, which rolled back").isFalse();
        });
    }

    @Test
    void bootsRepositoriesBackOff() {
        books.withPropertyValues("spring.data.jpa.repositories.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(BookRepository.class)).containsExactly("bookRepository");
            assertThat(context.getBeanNamesForType(JpaRepositoryFactoryBean.class)).containsExactly("&bookRepository");
        });
    }

    @Test
    void aStoreWithoutRepositoriesStarts() {
        runner
                .withUserConfiguration(LegacyOrdersStore.class)
                .withPropertyValues(store("legacy-orders"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(Repository.class)).isEmpty();
                });
    }

    @Test
    void aRepositoryIsPrimaryExactlyWhenItsStoreIs() {
        books
                .withUserConfiguration(LegacyStore.class)
                .withPropertyValues(store("legacy"))
                .withPropertyValues("synapse.data.stores.books.primary=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanFactory().getBeanDefinition("bookRepository").isPrimary()).isTrue();
                    assertThat(context.getBeanFactory().getBeanDefinition("legacyItemRepository").isPrimary())
                            .isFalse();
                });
    }

    @Test
    void aSingleStoresRepositoriesArePrimary() {
        books
                .run(context -> assertThat(context.getBeanFactory().getBeanDefinition("bookRepository").isPrimary())
                        .isTrue());
    }

    /**
     * Acceptance 17 for repositories: Spring Data prepares derived, count, JPQL and native queries at startup without
     * a connection. The database is reachable, so any connection asked for would succeed and start the pool.
     */
    @Test
    void preparingTheRepositoriesQueriesOpensNoConnection() {
        books
                .withPropertyValues("synapse.data.stores.books.migrations.enabled=false",
                        "synapse.data.stores.books.jpa.hibernate.ddl-auto=none")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(BookRepository.class)).isNotNull();
                    HikariDataSource dataSource = context.getBean("booksDataSource", HikariDataSource.class);
                    assertThat(dataSource.getHikariPoolMXBean()).as("the pool was never started").isNull();
                    try (Connection connection = dataSource.getConnection()) {
                        assertThat(connection.isValid(1)).as("the database is reachable").isTrue();
                    }
                });
    }

    @Test
    void customFragmentsWork() {
        books
                .run(context -> assertThat(context.getBean(BookRepository.class).customMethod())
                        .isEqualTo(BookRepositoryCustomImpl.class.getSimpleName()));
    }

    @Test
    void aRepositoryTheStoresClassDefinesReplacesSynapses() {
        runner
                .withUserConfiguration(BooksStoreWithOwnRepository.class)
                .withPropertyValues(store("books"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(ShelfRepository.class)).containsExactly("shelfRepository");
                    assertThat(context.getBean(ShelfRepository.class).owner())
                            .isEqualTo(BooksStoreWithOwnRepository.OWNER);
                });
    }

    @Test
    void twoStoresWithTheSameRepositoryNameFailStartup() {
        books.withUserConfiguration(ArchiveStore.class).withPropertyValues(store("archive")).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .isInstanceOf(BeanDefinitionOverrideException.class)
                    .hasMessageContaining("'bookRepository'")
                    .hasMessageContaining("@SynapseDataStore(\"archive\") on ArchiveStore");
        });
    }

    /**
     * With two stores and no primary, the unqualified {@code @Transactional} check passes the repositories and Spring
     * Data JPA's own {@code SimpleJpaRepository} (a Spring class, and a {@code Repository}), whose {@code @Transactional}
     * has no qualifier.
     */
    @Test
    void theUnqualifiedTransactionCheckSkipsRepositoriesAndSpringDataJpa() {
        books
                .withUserConfiguration(LegacyStore.class, RawRepositoryConfiguration.class)
                .withPropertyValues(store("legacy"))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasBean("synapseUnqualifiedTransactionCheck");
                    assertThat(context.getBean("rawBooks")).isInstanceOf(SimpleJpaRepository.class);
                    assertThat(context).hasSingleBean(BookRepository.class).hasSingleBean(LegacyItemRepository.class);
                });
    }

    /**
     * A type-level {@code @Qualifier} with a store name names that store's transaction manager, which carries the
     * store name as its qualifier: Spring runs the class's transactions on it, and the check passes.
     */
    @Test
    void aTypeLevelStoreQualifierPassesTheUnqualifiedTransactionCheck() {
        books
                .withUserConfiguration(LegacyStore.class)
                .withPropertyValues(store("legacy"))
                .withBean("legacyLedger", LegacyLedger.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(LegacyLedger.class).post()).as("ran in a transaction").isTrue();
                });
    }

    /** The check reads {@code jakarta.transaction.Transactional}, which has no qualifier, as Spring does. */
    @Test
    void theUnqualifiedTransactionCheckReadsJakartaTransactional() {
        books
                .withUserConfiguration(LegacyStore.class)
                .withPropertyValues(store("legacy"))
                .withBean("jakartaLedger", JakartaLedger.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(NestedExceptionUtils
                            .getMostSpecificCause(Objects.requireNonNull(context.getStartupFailure())))
                            .isInstanceOfSatisfying(SynapseConfigurationException.class,
                                    failure -> assertThat(failure.description())
                                            .isEqualTo("There are 2 stores and none is primary, so an unqualified "
                                                    + "@Transactional can't choose a transaction manager: "
                                                    + JakartaLedger.class.getName() + "#post."));
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class RawRepositoryConfiguration {

        @Bean
        SimpleJpaRepository<Book, Long> rawBooks(@Qualifier("books") EntityManagerFactory books) {
            return new SimpleJpaRepository<>(Book.class, SharedEntityManagerCreator.createSharedEntityManager(books));
        }
    }

    @Qualifier("legacy")
    static class LegacyLedger {

        @Transactional
        public boolean post() {
            return TransactionSynchronizationManager.isActualTransactionActive();
        }
    }

    static class JakartaLedger {

        @jakarta.transaction.Transactional
        public void post() {
            // A JTA-style transaction, which can't name a transaction manager.
        }
    }

    private static void assertBoundTo(ConfigurableListableBeanFactory beanFactory, String repository, String store) {
        BeanDefinition definition = beanFactory.getBeanDefinition(repository);
        assertThat(definition.getPropertyValues().get("transactionManager")).isEqualTo(store + "TransactionManager");
        assertThat(definition.getPropertyValues().get("entityManager"))
                .isInstanceOfSatisfying(RuntimeBeanReference.class, entityManager -> {
                    BeanDefinition shared = beanFactory.getBeanDefinition(entityManager.getBeanName());
                    ValueHolder factory = shared.getConstructorArgumentValues().getIndexedArgumentValue(0, null);
                    assertThat(factory == null ? null : factory.getValue())
                            .as("the shared EntityManager's factory")
                            .isInstanceOfSatisfying(RuntimeBeanReference.class, reference -> assertThat(
                                    reference.getBeanName()).isEqualTo(store + "EntityManagerFactory"));
                });
        assertThat(qualifier(definition)).isEqualTo(store);
    }

    private static @Nullable Object qualifier(BeanDefinition definition) {
        AutowireCandidateQualifier qualifier = ((AbstractBeanDefinition) definition)
                .getQualifier(Qualifier.class.getName());
        return qualifier == null ? null : qualifier.getAttribute("value");
    }

    private static String[] store(String name) {
        return new String[]{
                "synapse.data.stores." + name + ".url=jdbc:h2:mem:jpa-repositories-" + name + ";DB_CLOSE_DELAY=-1",
                "synapse.data.stores." + name + ".username=sa", "synapse.data.stores." + name + ".password="};
    }
}
