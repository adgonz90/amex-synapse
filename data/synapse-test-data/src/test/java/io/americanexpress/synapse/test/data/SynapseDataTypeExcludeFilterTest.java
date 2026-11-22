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

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;
import org.springframework.stereotype.Component;

import io.americanexpress.synapse.data.SynapseDataStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class SynapseDataTypeExcludeFilterTest {

    private final MetadataReaderFactory metadataReaderFactory = new SimpleMetadataReaderFactory();

    private boolean excluded(Class<?> testClass, Class<?> type) throws IOException {
        SynapseDataTypeExcludeFilter filter = new SynapseDataTypeExcludeFilter(testClass);
        filter.setBeanClassLoader(getClass().getClassLoader());
        return filter.match(metadataReaderFactory.getMetadataReader(type.getName()), metadataReaderFactory);
    }

    @Test
    void includesTheTestsStore() throws IOException {
        assertThat(excluded(BooksTest.class, BooksStore.class)).isFalse();
    }

    @Test
    void excludesOtherStores() throws IOException {
        assertThat(excluded(BooksTest.class, LegacyStore.class)).isTrue();
        assertThat(excluded(LegacyTest.class, BooksStore.class)).isTrue();
        assertThat(excluded(LegacyTest.class, LegacyStore.class)).isFalse();
    }

    @Test
    void excludesOtherComponents() throws IOException {
        assertThat(excluded(BooksTest.class, AComponent.class)).isTrue();
        assertThat(excluded(BooksTest.class, AConfiguration.class)).isTrue();
    }

    /** The filter is part of the context cache key: a test of another store gets its own context. */
    @Test
    void filtersOfTheSameStoreAreEqual() {
        SynapseDataTypeExcludeFilter books = new SynapseDataTypeExcludeFilter(BooksTest.class);

        assertThat(books)
                .isEqualTo(new SynapseDataTypeExcludeFilter(MoreBooksTest.class))
                .hasSameHashCodeAs(new SynapseDataTypeExcludeFilter(MoreBooksTest.class))
                .isNotEqualTo(new SynapseDataTypeExcludeFilter(LegacyTest.class));
    }

    @Test
    void readsTheStoreFromASuperclass() throws IOException {
        assertThat(excluded(InheritedBooksTest.class, BooksStore.class)).isFalse();
        assertThat(excluded(InheritedBooksTest.class, LegacyStore.class)).isTrue();
    }

    @Test
    void needsSynapseDataTest() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new SynapseDataTypeExcludeFilter(NotADataTest.class))
                .withMessage("SynapseDataTypeExcludeFilter needs @SynapseDataTest on " + NotADataTest.class.getName()
                        + ".");
    }

    @SynapseDataTest("books")
    static class BooksTest {
    }

    @SynapseDataTest("books")
    static class MoreBooksTest {
    }

    static class InheritedBooksTest extends BooksTest {
    }

    @SynapseDataTest("legacy")
    static class LegacyTest {
    }

    static class NotADataTest {
    }

    @Configuration(proxyBeanMethods = false)
    @SynapseDataStore("books")
    static class BooksStore {
    }

    @Configuration(proxyBeanMethods = false)
    @SynapseDataStore("legacy")
    static class LegacyStore {
    }

    @Component
    static class AComponent {
    }

    @Configuration(proxyBeanMethods = false)
    static class AConfiguration {
    }
}
