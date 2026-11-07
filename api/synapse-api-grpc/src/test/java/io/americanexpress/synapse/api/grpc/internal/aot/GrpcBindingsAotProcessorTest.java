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
package io.americanexpress.synapse.api.grpc.internal.aot;

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

import io.americanexpress.synapse.api.grpc.GrpcBindings;
import io.americanexpress.synapse.api.grpc.fixture.proto.CatalogServiceGrpc;
import io.americanexpress.synapse.api.grpc.internal.binding.fixture.BindingFixtures;
import io.americanexpress.synapse.core.binding.ComponentRef;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GrpcBindingsAotProcessorTest {

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
    void registersOneLambdaHintPerDeclaringClass() {
        List<LambdaHint> lambdas = lambdaHints(process(TwoBindingsConfiguration.class));

        assertThat(lambdas)
                .extracting(LambdaHint::getDeclaringClass)
                .containsExactly(TypeReference.of(TwoBindingsConfiguration.class));
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
                .registerBean(GrpcBindings.class, () -> bindings(BindingFixtures.ItemRecord::name))))
                .isNull();
    }

    @Test
    void contributesNothingWithoutBindings() {
        assertThat(contribution(OtherConfiguration.class)).isNull();
    }

    @Test
    void isRegisteredInAotFactories() {
        assertThat(SpringFactoriesLoader
                .forResourceLocation("META-INF/spring/aot.factories")
                .load(BeanFactoryInitializationAotProcessor.class))
                .hasAtLeastOneElementOfType(GrpcBindingsAotProcessor.class);
    }

    /** Bindings whose method reference is created by the caller, so it belongs to the caller's class. */
    static GrpcBindings bindings(ComponentRef<BindingFixtures.ItemRecord, ?> name) {
        return GrpcBindings
                .service(CatalogServiceGrpc.getServiceDescriptor())
                .unary(CatalogServiceGrpc.getGetItemMethod(), new BindingFixtures.GetItemOperation())
                .map("name", name)
                .build();
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
            return new GrpcBindingsAotProcessor().processAheadOfTime(context.getBeanFactory());
        }
    }

    @Configuration
    static class BindingsConfiguration {

        @Bean
        GrpcBindings catalogBindings() {
            return GrpcBindings
                    .service(CatalogServiceGrpc.getServiceDescriptor())
                    .unary(CatalogServiceGrpc.getGetItemMethod(), new BindingFixtures.GetItemOperation())
                    .map("name", BindingFixtures.ItemRecord::name)
                    .build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoBindingsConfiguration {

        @Bean
        GrpcBindings catalogBindings() {
            return bindings(BindingFixtures.ItemRecord::name);
        }

        @Bean
        GrpcBindings otherCatalogBindings() {
            return bindings(BindingFixtures.ItemRecord::name);
        }
    }

    @Configuration
    static class StaticBindingsConfiguration {

        @Bean
        static GrpcBindings catalogBindings() {
            return bindings(BindingFixtures.ItemRecord::name);
        }
    }

    abstract static class BaseBindingsConfiguration {

        @Bean
        GrpcBindings catalogBindings() {
            return bindings(BindingFixtures.ItemRecord::name);
        }
    }

    @Configuration
    static class InheritingConfiguration extends BaseBindingsConfiguration {
    }

    @Configuration
    static class OtherConfiguration {

        @Bean
        BindingFixtures.GetItemOperation getItemOperation() {
            return new BindingFixtures.GetItemOperation();
        }
    }
}
