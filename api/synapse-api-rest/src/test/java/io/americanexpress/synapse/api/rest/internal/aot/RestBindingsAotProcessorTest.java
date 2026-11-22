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
package io.americanexpress.synapse.api.rest.internal.aot;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.LambdaHint;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.test.generate.TestGenerationContext;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor;
import org.springframework.beans.factory.aot.BeanFactoryInitializationCode;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.SpringFactoriesLoader;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.RestConventions;
import io.americanexpress.synapse.core.binding.ComponentRef;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RestBindingsAotProcessorTest {

    @Test
    void registersLambdaHintsForBindingsConfiguration() {
        List<LambdaHint> lambdas = lambdaHints(process(BindingsConfiguration.class));

        assertThat(lambdas).singleElement().satisfies(lambda -> {
            assertThat(lambda.getDeclaringClass()).isEqualTo(TypeReference.of(BindingsConfiguration.class));
            assertThat(lambda.getInterfaces()).containsExactly(TypeReference.of(ComponentRef.class));
            assertThat(lambda.getDeclaringMethod()).isNull();
        });
    }

    @Test
    void registersLambdaHintsForConventionsConfiguration() {
        List<LambdaHint> lambdas = lambdaHints(process(ConventionsConfiguration.class));

        assertThat(lambdas)
                .extracting(LambdaHint::getDeclaringClass)
                .containsExactly(TypeReference.of(ConventionsConfiguration.class));
    }

    @Test
    void registersOneLambdaHintPerDeclaringClass() {
        List<LambdaHint> lambdas = lambdaHints(process(BindingsAndConventionsConfiguration.class));

        assertThat(lambdas)
                .extracting(LambdaHint::getDeclaringClass)
                .containsExactly(TypeReference.of(BindingsAndConventionsConfiguration.class));
    }

    @Test
    void registersTheClassThatDeclaresAnInheritedFactoryMethod() {
        List<LambdaHint> lambdas = lambdaHints(process(InheritingConfiguration.class));

        assertThat(lambdas)
                .extracting(LambdaHint::getDeclaringClass)
                .containsExactly(TypeReference.of(BaseBindingsConfiguration.class));
    }

    @Test
    void registersTheConfigurationOfAStaticFactoryMethod() {
        List<LambdaHint> lambdas = lambdaHints(process(StaticBindingsConfiguration.class));

        assertThat(lambdas)
                .extracting(LambdaHint::getDeclaringClass)
                .containsExactly(TypeReference.of(StaticBindingsConfiguration.class));
    }

    @Test
    void contributesNothingForBindingsThatNoFactoryMethodCreates() {
        // A known limit: the processor cannot tell where a supplier's method references are declared.
        assertThat(contribution(context -> context
                .registerBean(RestBindings.class, () -> RestBindings
                        .builder()
                        .get("/books/{key}", new GetBookOperation())
                        .path("key", BookId::id)
                        .build())))
                .isNull();
    }

    @Test
    void contributesNothingWithoutBindingsOrConventions() {
        assertThat(contribution(OtherConfiguration.class)).isNull();
    }

    @Test
    void isRegisteredInAotFactories() {
        assertThat(SpringFactoriesLoader
                .forResourceLocation("META-INF/spring/aot.factories")
                .load(BeanFactoryInitializationAotProcessor.class))
                .hasAtLeastOneElementOfType(RestBindingsAotProcessor.class);
    }

    private static List<LambdaHint> lambdaHints(RuntimeHints hints) {
        return hints.reflection().lambdaHints().toList();
    }

    private static RuntimeHints process(Class<?> configuration) {
        BeanFactoryInitializationAotContribution contribution = Objects
                .requireNonNull(contribution(configuration), "no contribution");
        TestGenerationContext generationContext = new TestGenerationContext();
        contribution.applyTo(generationContext, mock(BeanFactoryInitializationCode.class));
        return generationContext.getRuntimeHints();
    }

    private static @Nullable BeanFactoryInitializationAotContribution contribution(Class<?> configuration) {
        return contribution(context -> context.register(configuration));
    }

    private static @Nullable BeanFactoryInitializationAotContribution contribution(
            Consumer<AnnotationConfigApplicationContext> registrations) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            registrations.accept(context);
            context.refreshForAotProcessing(new RuntimeHints());
            return new RestBindingsAotProcessor().processAheadOfTime(context.getBeanFactory());
        }
    }

    record BookId(String id) {
    }

    record Book(String id, String title) {
    }

    static class GetBookOperation implements QueryOperation<BookId, Book> {

        @Override
        public Book execute(BookId input) {
            return new Book(input.id(), "Dune");
        }
    }

    @Configuration
    static class BindingsConfiguration {

        @Bean
        RestBindings bindings() {
            return RestBindings.builder().get("/books/{key}", new GetBookOperation()).path("key", BookId::id).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ConventionsConfiguration {

        @Bean
        RestConventions conventions() {
            return RestConventions.builder().responseHeader("Book-Title", Book.class, Book::title).build();
        }
    }

    @Configuration
    static class BindingsAndConventionsConfiguration {

        @Bean
        RestBindings bindings() {
            return RestBindings.builder().get("/books/{key}", new GetBookOperation()).path("key", BookId::id).build();
        }

        @Bean
        RestConventions conventions() {
            return RestConventions.builder().responseHeader("Book-Title", Book.class, Book::title).build();
        }
    }

    @Configuration
    static class StaticBindingsConfiguration {

        @Bean
        static RestBindings bindings() {
            return RestBindings.builder().get("/books/{key}", new GetBookOperation()).path("key", BookId::id).build();
        }
    }

    abstract static class BaseBindingsConfiguration {

        @Bean
        RestBindings bindings() {
            return RestBindings.builder().get("/books/{key}", new GetBookOperation()).path("key", BookId::id).build();
        }
    }

    @Configuration
    static class InheritingConfiguration extends BaseBindingsConfiguration {
    }

    @Configuration
    static class OtherConfiguration {

        @Bean
        GetBookOperation getBookOperation() {
            return new GetBookOperation();
        }
    }
}
