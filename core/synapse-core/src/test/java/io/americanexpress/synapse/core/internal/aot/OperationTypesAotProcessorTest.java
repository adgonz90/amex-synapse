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
package io.americanexpress.synapse.core.internal.aot;

import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.ReflectionHintsPredicates;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.aot.test.generate.TestGenerationContext;
import org.springframework.beans.factory.aot.BeanRegistrationAotContribution;
import org.springframework.beans.factory.aot.BeanRegistrationAotProcessor;
import org.springframework.beans.factory.aot.BeanRegistrationCode;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RegisteredBean;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.support.SpringFactoriesLoader;

import io.americanexpress.synapse.core.internal.aot.AotFixtures.Book;
import io.americanexpress.synapse.core.internal.aot.AotFixtures.ChecksumBusiness;
import io.americanexpress.synapse.core.internal.aot.AotFixtures.CountBooksBusiness;
import io.americanexpress.synapse.core.internal.aot.AotFixtures.GenericOperation;
import io.americanexpress.synapse.core.internal.aot.AotFixtures.GetBookOperation;
import io.americanexpress.synapse.core.internal.aot.AotFixtures.GetBookRequest;
import io.americanexpress.synapse.core.internal.aot.AotFixtures.MaxOperation;
import io.americanexpress.synapse.core.internal.aot.AotFixtures.SearchBooksOperation;
import io.americanexpress.synapse.core.internal.aot.AotFixtures.SearchBooksRequest;
import io.americanexpress.synapse.core.internal.aot.AotFixtures.Shelf;
import io.americanexpress.synapse.core.internal.aot.AotFixtures.ShelveBooksBusiness;
import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OperationTypesAotProcessorTest {

    private static final ReflectionHintsPredicates REFLECTION = RuntimeHintsPredicates.reflection();

    @Test
    void registersOperationInputAndOutputTypes() {
        RuntimeHints hints = process(new RootBeanDefinition(GetBookOperation.class));

        assertThat(REFLECTION.onType(GetBookRequest.class)).accepts(hints);
        assertThat(REFLECTION.onMethodInvocation(GetBookRequest.class, "id")).accepts(hints);
        assertThat(REFLECTION.onType(Book.class)).accepts(hints);
        assertThat(REFLECTION.onMethodInvocation(Book.class, "author")).accepts(hints);
        assertThat(REFLECTION.onType(Book.Author.class)).accepts(hints);
        assertThat(REFLECTION.onType(Optional.class)).rejects(hints);
    }

    @Test
    void unwrapsPageResultItems() {
        RuntimeHints hints = process(new RootBeanDefinition(SearchBooksOperation.class));

        assertThat(REFLECTION.onType(SearchBooksRequest.class)).accepts(hints);
        assertThat(REFLECTION.onType(PageQuery.class)).accepts(hints);
        assertThat(REFLECTION.onType(PageResult.class)).accepts(hints);
        assertThat(REFLECTION.onType(Book.class)).accepts(hints);
        assertThat(REFLECTION.onType(Book.Author.class)).accepts(hints);
    }

    @Test
    void unwrapsListElementsOfBusinessOperations() {
        RuntimeHints hints = process(new RootBeanDefinition(ShelveBooksBusiness.class));

        assertThat(REFLECTION.onType(Shelf.class)).accepts(hints);
        assertThat(REFLECTION.onType(Book.class)).accepts(hints);
    }

    @Test
    void readsTypesFromTheDeclaredBeanType() {
        RootBeanDefinition definition = new RootBeanDefinition();
        definition
                .setTargetType(ResolvableType
                        .forClassWithGenerics(QueryOperation.class, ResolvableType.forClass(GetBookRequest.class),
                                ResolvableType.forClassWithGenerics(Optional.class, Book.class)));

        RuntimeHints hints = process(definition);

        assertThat(REFLECTION.onType(GetBookRequest.class)).accepts(hints);
        assertThat(REFLECTION.onType(Book.Author.class)).accepts(hints);
    }

    @Test
    void unwrapsArrayComponents() {
        RuntimeHints hints = process(new RootBeanDefinition(CountBooksBusiness.class));

        assertThat(REFLECTION.onType(Book.class)).accepts(hints);
        assertThat(REFLECTION.onType(Book[].class)).rejects(hints);
        assertThat(REFLECTION.onType(int[].class)).rejects(hints);
    }

    @Test
    void skipsOperationsWithoutResolvableTypes() {
        assertThat(contribution(new RootBeanDefinition(GenericOperation.class))).isNull();
    }

    @Test
    void skipsPrimitiveArrays() {
        assertThat(contribution(new RootBeanDefinition(ChecksumBusiness.class))).isNull();
    }

    @Test
    void skipsSelfReferencingTypeVariables() {
        assertThat(contribution(new RootBeanDefinition(MaxOperation.class))).isNull();
    }

    @Test
    void skipsOtherBeans() {
        assertThat(contribution(new RootBeanDefinition(StringBuilder.class))).isNull();
    }

    @Test
    void isRegisteredInAotFactories() {
        assertThat(SpringFactoriesLoader
                .forResourceLocation("META-INF/spring/aot.factories")
                .load(BeanRegistrationAotProcessor.class))
                .hasAtLeastOneElementOfType(OperationTypesAotProcessor.class);
    }

    private static RuntimeHints process(RootBeanDefinition definition) {
        BeanRegistrationAotContribution contribution = Objects
                .requireNonNull(contribution(definition),
                        "no contribution");
        TestGenerationContext generationContext = new TestGenerationContext();
        contribution.applyTo(generationContext, mock(BeanRegistrationCode.class));
        return generationContext.getRuntimeHints();
    }

    private static @Nullable BeanRegistrationAotContribution contribution(RootBeanDefinition definition) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("bean", definition);
        return new OperationTypesAotProcessor().processAheadOfTime(RegisteredBean.of(beanFactory, "bean"));
    }
}
