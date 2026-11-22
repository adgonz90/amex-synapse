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
package io.americanexpress.synapse.test.data;

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.BeanFactoryAnnotationUtils;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.MergedContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;

class StoreConnectionTest {

    private static final String CONTAINER_TYPE = "org.testcontainers.containers.JdbcDatabaseContainer";

    private static final StubJdbcContainer BOOKS = new StubJdbcContainer("jdbc:h2:mem:stub-books", "reader",
            "s3cr3t");

    @StoreConnection("books")
    static final StubJdbcContainer OUTER_BOOKS = BOOKS;

    private final StoreConnectionContextCustomizerFactory factory = new StoreConnectionContextCustomizerFactory();

    @Test
    void registersQualifiedConnectionDetails() {
        try (GenericApplicationContext context = customized(BooksConnection.class)) {
            AbstractBeanDefinition definition = (AbstractBeanDefinition) context
                    .getBeanDefinition("booksStoreConnectionDetails");
            assertThat(definition.isAutowireCandidate()).isTrue();
            assertThat(definition.isDefaultCandidate()).as("a default candidate").isFalse();

            JdbcConnectionDetails details = BeanFactoryAnnotationUtils
                    .qualifiedBeanOfType(context.getBeanFactory(), JdbcConnectionDetails.class, "books");
            assertThat(details).isSameAs(context.getBean("booksStoreConnectionDetails"));
            assertThat(details.getJdbcUrl()).isEqualTo("jdbc:h2:mem:stub-books");
            assertThat(details.getUsername()).isEqualTo("reader");
            assertThat(details.getPassword()).isEqualTo("s3cr3t");
            assertThat(details.getDriverClassName()).isEqualTo("org.h2.Driver");
            assertThat(details.toString()).contains("books").doesNotContain("s3cr3t", "jdbc:", "reader");
            ByType byType = context.getBean(ByType.class);
            assertThat(byType.available).as("injected by type alone").isNull();
            assertThat(byType.streamed).as("streamed by type alone").isEmpty();
            assertThat(BOOKS.starts()).as("a running container is not started again").isZero();
        }
    }

    @Test
    void startsAContainerThatIsNotRunning() {
        try (GenericApplicationContext context = customized(StoppedConnection.class)) {
            assertThat(StoppedConnection.STOPPED.starts()).isEqualTo(1);
            assertThat(StoppedConnection.STOPPED.isRunning()).isTrue();
            assertThat(context.containsBean("legacyStoreConnectionDetails")).isTrue();
        }
    }

    @Test
    void namesTheBeanAfterADashedStore() {
        try (GenericApplicationContext context = customized(DashedConnection.class)) {
            assertThat(BeanFactoryAnnotationUtils
                    .qualifiedBeanOfType(context.getBeanFactory(), JdbcConnectionDetails.class, "legacy-orders"))
                    .isSameAs(context.getBean("legacyOrdersStoreConnectionDetails"));
        }
    }

    @Test
    void registersEveryStoreOfTheClassAndItsSuperclasses() {
        try (GenericApplicationContext context = customized(TwoConnections.class)) {
            assertThat(context.getBeanFactory().getBeanNamesForType(JdbcConnectionDetails.class))
                    .containsExactlyInAnyOrder("booksStoreConnectionDetails", "archiveStoreConnectionDetails");
        }
    }

    @Test
    void sameFieldsShareTheCachedContext() {
        ContextCustomizer first = customizer(BooksConnection.class);
        ContextCustomizer second = customizer(SameBooksConnection.class);

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first).isNotEqualTo(customizer(OtherBooksConnection.class));
        assertThat(first).isNotEqualTo(customizer(BooksAsArchiveConnection.class));
    }

    @Test
    void noFieldsNoCustomizer() {
        assertThat(factory.createContextCustomizer(NoConnection.class, List.of())).isNull();
    }

    @Test
    void fieldMustBeAJdbcDatabaseContainer() {
        assertThatIllegalStateException()
                .isThrownBy(() -> customizer(StringConnection.class))
                .withMessage("@StoreConnection field 'books' in " + StringConnection.class.getName() + " must be a "
                        + CONTAINER_TYPE + ".");
    }

    @Test
    void fieldMustNotBeNull() {
        assertThatIllegalStateException()
                .isThrownBy(() -> customizer(NullConnection.class))
                .withMessage("@StoreConnection field 'books' in " + NullConnection.class.getName() + " must be a "
                        + CONTAINER_TYPE + ".");
    }

    @Test
    void fieldMustBeStatic() {
        assertThatIllegalStateException()
                .isThrownBy(() -> customizer(InstanceConnection.class))
                .withMessage("@StoreConnection field 'books' in " + InstanceConnection.class.getName()
                        + " must be static.");
    }

    @Test
    void storeNameMustBeValid() {
        assertThatIllegalStateException()
                .isThrownBy(() -> customizer(InvalidStoreConnection.class))
                .withMessage("@StoreConnection field 'books' in " + InvalidStoreConnection.class.getName()
                        + " names 'Books', which is not a valid store name.");
    }

    @Test
    void aStoreHasOneConnection() {
        assertThatIllegalStateException()
                .isThrownBy(() -> customizer(TwiceConnection.class))
                .withMessage("The store 'books' has more than one @StoreConnection: field 'first' in "
                        + TwiceConnection.class.getName() + " and field 'second' in "
                        + TwiceConnection.class.getName() + ".");
    }

    @Nested
    class InANestedTest {

        @Test
        void findsTheFieldsOfTheEnclosingClass() {
            assertThat(factory.createContextCustomizer(InANestedTest.class, List.of()))
                    .isEqualTo(customizer(BooksConnection.class));
        }
    }

    private GenericApplicationContext customized(Class<?> testClass) {
        GenericApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(ByType.class);
        customizer(testClass).customizeContext(context, mock(MergedContextConfiguration.class));
        context.refresh();
        return context;
    }

    private ContextCustomizer customizer(Class<?> testClass) {
        return Objects.requireNonNull(factory.createContextCustomizer(testClass, List.of()), "a customizer");
    }

    /** A bean that asks for connection details by type, as an auto-configuration of the application would. */
    static class ByType {

        final @Nullable JdbcConnectionDetails available;

        final List<JdbcConnectionDetails> streamed;

        ByType(ObjectProvider<JdbcConnectionDetails> details) {
            this.available = details.getIfAvailable();
            this.streamed = details.stream().toList();
        }
    }

    static class BooksConnection {

        @StoreConnection("books")
        static final StubJdbcContainer BOOKS_CONNECTION = BOOKS;
    }

    static class SameBooksConnection {

        @StoreConnection("books")
        static final StubJdbcContainer SAME = BOOKS;

        static final StubJdbcContainer NOT_A_CONNECTION = new StubJdbcContainer("jdbc:h2:mem:other", "sa", "");
    }

    static class OtherBooksConnection {

        @StoreConnection("books")
        static final StubJdbcContainer OTHER = new StubJdbcContainer("jdbc:h2:mem:stub-books", "reader", "s3cr3t");
    }

    static class BooksAsArchiveConnection {

        @StoreConnection("archive")
        static final StubJdbcContainer ARCHIVE = BOOKS;
    }

    static class StoppedConnection {

        @StoreConnection("legacy")
        static final StubJdbcContainer STOPPED = new StubJdbcContainer("jdbc:h2:mem:stub-legacy", "sa", "", false);
    }

    static class DashedConnection {

        @StoreConnection("legacy-orders")
        static final StubJdbcContainer LEGACY_ORDERS = new StubJdbcContainer("jdbc:h2:mem:stub-legacy-orders", "sa",
                "");
    }

    static class TwoConnections extends BooksConnection {

        @StoreConnection("archive")
        static final StubJdbcContainer ARCHIVE = new StubJdbcContainer("jdbc:h2:mem:stub-archive", "sa", "");
    }

    static class NoConnection {

        static final StubJdbcContainer NOT_A_CONNECTION = BOOKS;
    }

    static class StringConnection {

        @StoreConnection("books")
        static final String books = "jdbc:h2:mem:books";
    }

    static class NullConnection {

        @StoreConnection("books")
        static @Nullable StubJdbcContainer books;
    }

    static class InstanceConnection {

        @StoreConnection("books")
        final StubJdbcContainer books = BOOKS;
    }

    static class InvalidStoreConnection {

        @StoreConnection("Books")
        static final StubJdbcContainer books = BOOKS;
    }

    static class TwiceConnection {

        @StoreConnection("books")
        static final StubJdbcContainer first = BOOKS;

        @StoreConnection("books")
        static final StubJdbcContainer second = new StubJdbcContainer("jdbc:h2:mem:stub-twice", "sa", "");
    }
}
