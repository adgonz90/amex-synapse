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
package io.americanexpress.synapse.data.jpa;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.test.util.ReflectionTestUtils;

import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.data.autoconfigure.SynapseDataAutoConfiguration;
import io.americanexpress.synapse.data.jpa.autoconfigure.SynapseDataJpaAutoConfiguration;
import io.americanexpress.synapse.data.jpa.fixture.H2TestVendor;
import io.americanexpress.synapse.data.jpa.fixture.audited.AuditedStore;
import io.americanexpress.synapse.data.jpa.fixture.audited.Book;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which auditor stamps a store's entities, and that Synapse's auditor never gets in the way of the application's own
 * Spring Data auditing ({@code @EnableJpaAuditing}). Synapse's auditor is not a default candidate: the stores use the
 * application's {@code AuditorAware<String>} when there is one, else Synapse's; any other auditor is left to Spring
 * Data.
 */
class AuditorResolutionTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations
                    .of(SynapseCoreAutoConfiguration.class, SynapseDataAutoConfiguration.class,
                            SynapseDataJpaAutoConfiguration.class, DataSourceAutoConfiguration.class,
                            HibernateJpaAutoConfiguration.class,
                            TransactionManagerCustomizationAutoConfiguration.class,
                            TransactionAutoConfiguration.class))
            .withPropertyValues("spring.data.jpa.repositories.enabled=false",
                    "synapse.data.stores.audited.username=sa", "synapse.data.stores.audited.password=")
            .withUserConfiguration(H2TestVendor.class, AuditedStore.class);

    @Test
    void withoutAnApplicationAuditorSynapsesStamps() {
        audited().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(createdBy(context)).isEqualTo("system");
        });
    }

    @Test
    void springDataAuditingWithOnlySynapsesAuditorStarts() {
        audited().withUserConfiguration(SpringDataAuditing.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(createdBy(context)).isEqualTo("system");
            assertThat(springDataAuditor(context)).as("as without Synapse").isEmpty();
        });
    }

    @Test
    void aLongAuditorIsLeftToSpringData() {
        audited().withUserConfiguration(LongAuditor.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(createdBy(context)).isEqualTo("system");
        });
    }

    @Test
    void aLongAuditorStillReachesSpringDataAuditing() {
        audited().withUserConfiguration(LongAuditor.class, SpringDataAuditing.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(createdBy(context)).isEqualTo("system");
            assertThat(springDataAuditor(context)).containsSame(LongAuditor.AUDITOR);
        });
    }

    @Test
    void theApplicationsStringAuditorStamps() {
        audited().withUserConfiguration(StringAuditor.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(createdBy(context)).isEqualTo("user");
        });
    }

    @Test
    void theApplicationsStringAuditorStampsWithSpringDataAuditing() {
        audited().withUserConfiguration(StringAuditor.class, SpringDataAuditing.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(createdBy(context)).isEqualTo("user");
            assertThat(springDataAuditor(context)).containsSame(StringAuditor.AUDITOR);
        });
    }

    @Test
    void aRawAuditorStamps() {
        audited().withUserConfiguration(RawAuditor.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(createdBy(context)).isEqualTo("raw");
        });
    }

    @Test
    void aRawAuditorStampsWithSpringDataAuditing() {
        audited().withUserConfiguration(RawAuditor.class, SpringDataAuditing.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(createdBy(context)).isEqualTo("raw");
            assertThat(springDataAuditor(context)).containsSame(RawAuditor.AUDITOR);
        });
    }

    @Test
    void twoApplicationStringAuditorsFailStartup() {
        audited().withUserConfiguration(StringAuditor.class, OtherStringAuditor.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(NestedExceptionUtils.getMostSpecificCause(Objects.requireNonNull(context.getStartupFailure())))
                    .isInstanceOf(NoUniqueBeanDefinitionException.class);
        });
    }

    /** An auditor that needs the store itself (as one reading a user table would) is looked up after the store. */
    @Test
    void anAuditorThatDependsOnTheStoreStarts() {
        audited().withUserConfiguration(StoreDependentAuditor.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(createdBy(context)).isEqualTo("from the store");
        });
    }

    private ApplicationContextRunner audited() {
        return runner
                .withPropertyValues("synapse.data.stores.audited.url=jdbc:h2:mem:jpa-auditor-"
                        + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
    }

    /** Persists a book and returns who the store recorded as its creator. */
    private static @Nullable String createdBy(AssertableApplicationContext context) {
        EntityManagerFactory factory = context.getBean("auditedEntityManagerFactory", EntityManagerFactory.class);
        Book book = new Book("Dune");
        try (EntityManager entityManager = factory.createEntityManager()) {
            entityManager.getTransaction().begin();
            entityManager.persist(book);
            entityManager.getTransaction().commit();
        }
        return book.getCreatedBy();
    }

    @SuppressWarnings("unchecked")
    private static Optional<AuditorAware<?>> springDataAuditor(AssertableApplicationContext context) {
        return (Optional<AuditorAware<?>>) Objects
                .requireNonNull(ReflectionTestUtils
                        .getField(context.getBean("jpaAuditingHandler", AuditingHandler.class), "auditorAware"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableJpaAuditing
    static class SpringDataAuditing {
    }

    @Configuration(proxyBeanMethods = false)
    static class LongAuditor {

        static final AuditorAware<Long> AUDITOR = () -> Optional.of(42L);

        @Bean
        AuditorAware<Long> userIdAuditor() {
            return AUDITOR;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class StringAuditor {

        static final AuditorAware<String> AUDITOR = () -> Optional.of("user");

        @Bean
        AuditorAware<String> userAuditor() {
            return AUDITOR;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OtherStringAuditor {

        @Bean
        AuditorAware<String> otherAuditor() {
            return () -> Optional.of("other");
        }
    }

    /** An auditor registered without its generic type. */
    @Configuration(proxyBeanMethods = false)
    static class RawAuditor {

        static final AuditorAware<String> AUDITOR = () -> Optional.of("raw");

        @Bean
        static BeanDefinitionRegistryPostProcessor rawAuditorRegistrar() {
            return (BeanDefinitionRegistry registry) -> registry
                    .registerBeanDefinition("rawAuditor", new RootBeanDefinition(AuditorAware.class, () -> AUDITOR));
        }
    }

    /** An auditor built on the store's EntityManagerFactory. */
    @Configuration(proxyBeanMethods = false)
    static class StoreDependentAuditor {

        @Bean
        AuditorAware<String> storeAuditor(@Qualifier("audited") EntityManagerFactory factory) {
            return () -> Optional.of(factory.isOpen() ? "from the store" : "closed");
        }
    }
}
