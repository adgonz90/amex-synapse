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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.ConstraintViolationException;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.BeanFactoryAnnotationUtils;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.data.jpa.SynapseJpaTransactionManager;
import io.americanexpress.synapse.data.jpa.fixture.H2TestVendor;
import io.americanexpress.synapse.data.jpa.fixture.sized.SizedBook;
import io.americanexpress.synapse.data.jpa.fixture.sized.SizedBooksStore;
import io.americanexpress.synapse.data.jpa.fixture.sized.SizedBooksStoreWithJpaTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Boots an application with one H2 store whose entity fails Bean Validation when Hibernate flushes it, and checks
 * that the operation's caller gets {@code INTERNAL} without violations: the entity is the service's own state, never
 * the caller's input. Once with Synapse's transaction manager, which flushes before the commit and lets the
 * {@code ConstraintViolationException} through as itself, and once with a replaced plain {@link JpaTransactionManager},
 * which flushes inside the commit and wraps it.
 */
class EntityValidationIT {

    /** The violation as the logged cause names it: the path and the message template. */
    private static final String SIZE = "title: {jakarta.validation.constraints.Size.message}";

    @Test
    void entityViolationAtFlushIsInternalWithoutViolations() {
        run(SizedBooksStore.class, "synapse-tm", context -> {
            assertThat(context.getBean("booksTransactionManager")).isInstanceOf(SynapseJpaTransactionManager.class);

            SynapseException failure = save(context, 1, "abcd");

            assertThat(failure.details()).isEqualTo(SynapseErrors.INTERNAL);
            assertThat(failure.violations()).isEmpty();
            // The logged cause names the path and the message template, never the rejected value.
            assertThat(failure.getCause())
                    .isNotInstanceOf(ConstraintViolationException.class)
                    .hasMessage("ConstraintViolationException: " + SIZE);
            assertThat(failure.getMessage()).isEqualTo("An internal error occurred").doesNotContain("title");
            assertThat(rows(context, 1)).as("rolled back").isZero();
            assertThat(context.getBean(SaveSizedBookOperation.class).execute(new SaveSizedBook(2, "abc")))
                    .isEqualTo(2L);
            assertThat(rows(context, 2)).isOne();
        });
    }

    @Test
    void entityViolationAtCommitIsInternalWithoutViolationsWithAPlainJpaTransactionManager() {
        run(SizedBooksStoreWithJpaTransactionManager.class, "plain-tm", context -> {
            assertThat(context.getBean("booksTransactionManager")).isExactlyInstanceOf(JpaTransactionManager.class);

            SynapseException failure = save(context, 1, "abcd");

            assertThat(failure.details()).isEqualTo(SynapseErrors.INTERNAL);
            assertThat(failure.violations()).isEmpty();
            // Found under the commit's wrappers, and replaced like a top-level one: no wrapper message is logged.
            assertThat(causes(failure)).noneMatch(ConstraintViolationException.class::isInstance);
            assertThat(failure.getCause())
                    .hasMessageStartingWith("TransactionSystemException > ")
                    .hasMessageEndingWith("ConstraintViolationException: " + SIZE);
            assertThat(rows(context, 1)).as("rolled back").isZero();
        });
    }

    private static SynapseException save(ConfigurableApplicationContext context, long id, String title) {
        SaveSizedBookOperation operation = context.getBean(SaveSizedBookOperation.class);
        assertThat(AopUtils.isAopProxy(operation)).isTrue();
        return catchThrowableOfType(SynapseException.class, () -> operation.execute(new SaveSizedBook(id, title)));
    }

    private static int rows(ConfigurableApplicationContext context, long id) {
        JdbcClient jdbc = BeanFactoryAnnotationUtils
                .qualifiedBeanOfType(context.getBeanFactory(), JdbcClient.class,
                        "books");
        return jdbc.sql("SELECT COUNT(*) FROM book WHERE id = ?").param(id).query(Integer.class).single();
    }

    private static List<Throwable> causes(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable current = failure; current != null && chain.size() < 20; current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }

    private static void run(Class<?> store, String database, Consumer<ConfigurableApplicationContext> test) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TestApplication.class, store)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .properties("synapse.data.stores.books.url=jdbc:h2:mem:entity-validation-" + database
                        + ";DB_CLOSE_DELAY=-1", "synapse.data.stores.books.username=sa",
                        "synapse.data.stores.books.password=")
                .run()) {
            test.accept(context);
        }
    }

    /** The caller's input: it has no constraints, so the pipeline lets every title through. */
    record SaveSizedBook(long id, String title) {
    }

    /** Persists a book in the store's transaction, which commits when {@code execute} returns. */
    static class SaveSizedBookOperation implements CommandOperation<SaveSizedBook, Long> {

        @PersistenceContext(unitName = "books")
        private @Nullable EntityManager entityManager;

        @Override
        @Transactional("books")
        public Long execute(SaveSizedBook input) {
            if (entityManager == null) {
                throw new IllegalStateException("not injected");
            }
            entityManager.persist(new SizedBook(input.id(), input.title()));
            return input.id();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(H2TestVendor.class)
    static class TestApplication {

        @Bean
        SaveSizedBookOperation saveSizedBookOperation() {
            return new SaveSizedBookOperation();
        }
    }
}
