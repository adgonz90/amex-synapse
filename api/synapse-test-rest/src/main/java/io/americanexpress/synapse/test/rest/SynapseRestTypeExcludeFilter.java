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
package io.americanexpress.synapse.test.rest;

import java.io.IOException;
import java.util.Set;
import jakarta.servlet.Filter;

import org.springframework.boot.test.context.filter.annotation.AnnotationCustomizableTypeExcludeFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.GenericConverter;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.RestConventions;

/**
 * Keeps component scanning to the web layer for {@link SynapseRestTest}: controllers, controller advice,
 * {@link WebMvcConfigurer}s, converters, servlet {@link Filter}s, and the configuration classes that declare a
 * {@link RestBindings} or {@link RestConventions} {@code @Bean}. Operations, business beans and every other component
 * are left out.
 */
public final class SynapseRestTypeExcludeFilter extends AnnotationCustomizableTypeExcludeFilter {

    private static final Set<Class<?>> DEFAULT_INCLUDES = Set
            .of(Controller.class, RestController.class,
                    ControllerAdvice.class, WebMvcConfigurer.class, Converter.class, GenericConverter.class,
                    Filter.class);

    private static final Set<String> BINDING_TYPES = Set
            .of(RestBindings.class.getName(),
                    RestConventions.class.getName());

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
        return DEFAULT_INCLUDES;
    }

    @Override
    protected Set<Class<?>> getComponentIncludes() {
        return Set.of();
    }

    /**
     * Includes the default types and, in addition, the classes with a {@code @Bean} method that returns
     * {@link RestBindings} or {@link RestConventions}.
     *
     * @param metadataReader the class being scanned
     * @param metadataReaderFactory the factory for the metadata of other classes
     * @return whether the class is part of the slice
     * @throws IOException if the class metadata cannot be read
     */
    @Override
    protected boolean defaultInclude(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory)
            throws IOException {
        return super.defaultInclude(metadataReader, metadataReaderFactory)
                || declaresBindings(metadataReader.getAnnotationMetadata());
    }

    private static boolean declaresBindings(AnnotationMetadata metadata) {
        return metadata
                .getAnnotatedMethods(Bean.class.getName())
                .stream()
                .anyMatch(method -> BINDING_TYPES.contains(method.getReturnTypeName()));
    }
}
