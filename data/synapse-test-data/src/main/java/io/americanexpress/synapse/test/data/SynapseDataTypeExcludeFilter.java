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

import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.test.context.filter.annotation.AnnotationCustomizableTypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.test.context.TestContextAnnotationUtils;

import io.americanexpress.synapse.data.SynapseDataStore;

/**
 * Keeps component scanning to the store a {@link SynapseDataTest} names: the class annotated
 * {@code @SynapseDataStore} with that value. Every other store and every other component is left out. The store's
 * entities and repositories come with its {@code @SynapseDataStore} class.
 */
public final class SynapseDataTypeExcludeFilter extends AnnotationCustomizableTypeExcludeFilter {

    private static final String STORE_ANNOTATION = SynapseDataStore.class.getName();

    private final String store;

    /**
     * Creates the filter for a test class; Boot calls it with the class that declares {@link SynapseDataTest}.
     *
     * @param testClass the test class
     * @throws IllegalStateException when the test class has no {@link SynapseDataTest}
     */
    SynapseDataTypeExcludeFilter(Class<?> testClass) {
        SynapseDataTest test = TestContextAnnotationUtils.findMergedAnnotation(testClass, SynapseDataTest.class);
        if (test == null) {
            throw new IllegalStateException(
                    "SynapseDataTypeExcludeFilter needs @SynapseDataTest on " + testClass.getName() + ".");
        }
        this.store = test.value();
    }

    @Override
    protected boolean hasAnnotation() {
        return true;
    }

    @Override
    protected ComponentScan.Filter[] getFilters(FilterType type) {
        return new ComponentScan.Filter[0];
    }

    @Override
    protected boolean isUseDefaultFilters() {
        return true;
    }

    @Override
    protected Set<Class<?>> getDefaultIncludes() {
        return Set.of();
    }

    @Override
    protected Set<Class<?>> getComponentIncludes() {
        return Set.of();
    }

    /**
     * Includes only the {@code @SynapseDataStore} class of the test's store.
     *
     * @param metadataReader the class being scanned
     * @param metadataReaderFactory the factory for the metadata of other classes
     * @return whether the class declares the test's store
     */
    @Override
    protected boolean defaultInclude(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory) {
        Map<String, @Nullable Object> attributes = metadataReader
                .getAnnotationMetadata()
                .getAnnotationAttributes(STORE_ANNOTATION);
        return attributes != null && store.equals(attributes.get("value"));
    }

    /**
     * Compares the filters and the store, which are part of the test context's cache key.
     *
     * @param other the other object
     * @return whether it is a filter for the same store
     */
    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof SynapseDataTypeExcludeFilter filter && store.equals(filter.store)
                && super.equals(other);
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + store.hashCode();
    }
}
