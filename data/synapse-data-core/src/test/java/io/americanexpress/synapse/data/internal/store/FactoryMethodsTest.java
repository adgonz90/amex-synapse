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

import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.aot.BeanInstanceSupplier;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.Order;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FactoryMethods} reads a definition shaped as AOT processing generates it: a plain {@link RootBeanDefinition}
 * with a factory bean name and an instance supplier, without a factory method name or metadata.
 */
class FactoryMethodsTest {

    @Test
    void aGeneratedDefinitionsMethodComesFromItsInstanceSupplier() {
        RootBeanDefinition definition = generated();

        assertThat(definition.getFactoryMethodName()).isNull();
        assertThat(FactoryMethods.name(definition)).isEqualTo("archiveText");
        MergedAnnotations annotations = Objects.requireNonNull(FactoryMethods.annotations(definition));
        assertThat(annotations.get(Qualifier.class).getString("value")).isEqualTo("archive");
        assertThat(annotations.get(Order.class).getInt("value")).isEqualTo(2);
    }

    @Test
    void aGeneratedDefinitionsQualifierIsRead() {
        assertThat(BeanQualifiers.declaredQualifier(generated())).isEqualTo("archive");
    }

    @Test
    void aDefinitionWithoutAFactoryMethodHasNone() {
        RootBeanDefinition definition = new RootBeanDefinition(String.class);

        assertThat(FactoryMethods.name(definition)).isNull();
        assertThat(FactoryMethods.annotations(definition)).isNull();
        assertThat(BeanQualifiers.declaredQualifier(definition)).isNull();
    }

    @Test
    void aDefinitionWithAFactoryMethodNameKeepsIt() {
        RootBeanDefinition definition = new RootBeanDefinition();
        definition.setFactoryBeanName("texts");
        definition.setFactoryMethodName("archiveText");

        assertThat(FactoryMethods.name(definition)).isEqualTo("archiveText");
    }

    @Test
    void isMadeByComparesTheFactoryBeanAndMethod() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("archiveText", generated());

        assertThat(FactoryMethods.isMadeBy(beanFactory, "archiveText", "texts", "archiveText")).isTrue();
        assertThat(FactoryMethods.isMadeBy(beanFactory, "archiveText", "texts", "otherText")).isFalse();
        assertThat(FactoryMethods.isMadeBy(beanFactory, "archiveText", "others", "archiveText")).isFalse();
        assertThat(FactoryMethods.isMadeBy(beanFactory, "missing", "texts", "archiveText")).isFalse();
    }

    private static RootBeanDefinition generated() {
        RootBeanDefinition definition = new RootBeanDefinition(String.class);
        definition.setFactoryBeanName("texts");
        definition
                .setInstanceSupplier(BeanInstanceSupplier
                        .<String>forFactoryMethod(Texts.class, "archiveText")
                        .withGenerator(registeredBean -> "archived"));
        return definition;
    }

    static class Texts {

        @Bean
        @Qualifier("archive")
        @Order(2)
        String archiveText() {
            return "archived";
        }
    }
}
