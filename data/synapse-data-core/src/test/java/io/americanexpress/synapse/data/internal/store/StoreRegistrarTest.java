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

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.ResolvableType;

import io.americanexpress.synapse.data.DataAccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * {@link StoreRegistrar#registerStoreBean}: the type of a store bean can be resolved before its factory bean is
 * defined, which conditions of earlier auto-configurations need.
 */
class StoreRegistrarTest {

    private static final StoreDefinition BOOKS = new StoreDefinition("books", "books", "com.example.books",
            DataAccess.JPA, false, "com.example.books.BooksStore");

    @Test
    void aFactoryBeansObjectTypeResolvesWithoutItsFactoryBean() {
        DefaultListableBeanFactory registry = new DefaultListableBeanFactory();

        StoreRegistrar.registerStoreBean(registry, BOOKS, "Text", TextFactoryBean.class, "missingFactory", "text");

        // No bean named missingFactory: Spring would look its definition up for the FactoryBean's object type.
        assertThat(registry.getBeanDefinition("booksText").getAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE))
                .isInstanceOfSatisfying(ResolvableType.class,
                        objectType -> assertThat(objectType.resolve()).isEqualTo(CharSequence.class));
        assertThat(registry.getType("booksText", false)).isEqualTo(CharSequence.class);
        assertThat(registry.getBeanNamesForAnnotation(Deprecated.class)).isEmpty();
    }

    /** The object type keeps its own generics, as Spring would resolve them from the factory method. */
    @Test
    void aFactoryBeansObjectTypeKeepsItsGenerics() {
        DefaultListableBeanFactory registry = new DefaultListableBeanFactory();

        StoreRegistrar.registerStoreBean(registry, BOOKS, "Names", NamesFactoryBean.class, "missingFactory", "names");

        assertThat(registry.getBeanDefinition("booksNames").getAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE))
                .isInstanceOfSatisfying(ResolvableType.class, objectType -> {
                    assertThat(objectType.resolve()).isEqualTo(List.class);
                    assertThat(objectType.resolveGeneric(0)).isEqualTo(String.class);
                });
        assertThat(registry.getType("booksNames", false)).isEqualTo(List.class);
    }

    @Test
    void anOtherBeanHasOnlyItsTargetType() {
        DefaultListableBeanFactory registry = new DefaultListableBeanFactory();

        StoreRegistrar.registerStoreBean(registry, BOOKS, "Name", String.class, "missingFactory", "name");

        assertThat(registry.getBeanDefinition("booksName").hasAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE)).isFalse();
        assertThat(registry.getType("booksName", false)).isEqualTo(String.class);
    }

    /**
     * The documented limit: a lookup by type without eager initialization, as Boot's type-based
     * {@code @ConditionalOnBean} and {@code @ConditionalOnMissingBean} make, skips a store bean while its factory bean
     * isn't defined, a FactoryBean with its object type included. It finds them once the factory bean is defined, which
     * is why such a condition belongs in an auto-configuration ordered after the access module's.
     */
    @Test
    void aLookupByTypeSeesStoreBeansOnlyOnceTheirFactoryBeanIsDefined() {
        DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
        StoreRegistrar.registerStoreBean(registry, BOOKS, "Name", String.class, "storeFactory", "name");
        StoreRegistrar.registerStoreBean(registry, BOOKS, "Text", TextFactoryBean.class, "storeFactory", "text");

        assertThat(registry.getBeanNamesForType(String.class, true, false)).isEmpty();
        assertThat(registry.getBeanNamesForType(CharSequence.class, true, false)).isEmpty();

        registry.registerBeanDefinition("storeFactory", new RootBeanDefinition(Object.class));

        assertThat(registry.getBeanNamesForType(String.class, true, false)).containsExactly("booksName");
        assertThat(registry.getBeanNamesForType(CharSequence.class, true, false))
                .containsExactlyInAnyOrder("booksName", "booksText");
    }

    @Test
    void aFactoryBeanWithoutAnObjectTypeFails() {
        DefaultListableBeanFactory registry = new DefaultListableBeanFactory();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> StoreRegistrar
                        .registerStoreBean(registry, BOOKS, "Raw", RawFactoryBean.class, "missingFactory", "raw"))
                .withMessage("The FactoryBean " + RawFactoryBean.class.getName()
                        + " declares no object type for the store bean booksRaw");
    }

    /** A FactoryBean whose object type has a generic of its own. */
    static class NamesFactoryBean implements FactoryBean<List<String>> {

        @Override
        public List<String> getObject() {
            return List.of("Dune");
        }

        @Override
        public Class<?> getObjectType() {
            return List.class;
        }
    }

    /** A FactoryBean that declares its object type. */
    static class TextFactoryBean implements FactoryBean<CharSequence> {

        @Override
        public CharSequence getObject() {
            return "text";
        }

        @Override
        public Class<?> getObjectType() {
            return CharSequence.class;
        }
    }

    /** A FactoryBean whose object type its declaration doesn't say. */
    @SuppressWarnings("rawtypes")
    static class RawFactoryBean implements FactoryBean {

        @Override
        public @Nullable Object getObject() {
            return null;
        }

        @Override
        public @Nullable Class<?> getObjectType() {
            return null;
        }
    }
}
