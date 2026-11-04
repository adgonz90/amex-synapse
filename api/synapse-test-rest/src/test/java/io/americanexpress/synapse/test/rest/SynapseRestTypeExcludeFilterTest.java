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
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.GenericConverter;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.RestConventions;
import io.americanexpress.synapse.core.operation.BusinessOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static org.assertj.core.api.Assertions.assertThat;

class SynapseRestTypeExcludeFilterTest {

    private final MetadataReaderFactory metadataReaderFactory = new SimpleMetadataReaderFactory();

    private boolean excluded(Class<?> type) throws IOException {
        SynapseRestTypeExcludeFilter filter = new SynapseRestTypeExcludeFilter();
        filter.setBeanClassLoader(getClass().getClassLoader());
        return filter.match(metadataReaderFactory.getMetadataReader(type.getName()), metadataReaderFactory);
    }

    @ParameterizedTest
    @ValueSource(classes = {AController.class, ARestController.class, AnAdvice.class, ARestAdvice.class,
            AConfigurer.class, AConverter.class, AGenericConverter.class, AFilter.class, BindingsConfiguration.class,
            ConventionsConfiguration.class})
    void keepsTheWebLayerAndTheBindings(Class<?> type) throws IOException {
        assertThat(excluded(type)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(classes = {AnOperation.class, ABusinessOperation.class, AService.class, OtherConfiguration.class})
    void leavesOutOperationsAndOtherComponents(Class<?> type) throws IOException {
        assertThat(excluded(type)).isTrue();
    }

    @Controller
    static class AController {
    }

    @RestController
    static class ARestController {
    }

    @ControllerAdvice
    static class AnAdvice {
    }

    @RestControllerAdvice
    static class ARestAdvice {
    }

    static class AConfigurer implements WebMvcConfigurer {
    }

    static class AConverter implements Converter<String, Integer> {
        @Override
        public Integer convert(String source) {
            return source.length();
        }
    }

    static class AGenericConverter implements GenericConverter {
        @Override
        public @Nullable Set<ConvertiblePair> getConvertibleTypes() {
            return null;
        }

        @Override
        public @Nullable Object convert(@Nullable Object source, TypeDescriptor sourceType,
                TypeDescriptor targetType) {
            return source;
        }
    }

    static class AFilter implements jakarta.servlet.Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class BindingsConfiguration {
        @Bean
        RestBindings bindings() {
            return RestBindings.builder().build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ConventionsConfiguration {
        @Bean
        RestConventions conventions() {
            return RestConventions.builder().build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OtherConfiguration {
        @Bean
        String notBindings() {
            return "not bindings";
        }
    }

    @Service
    static class AnOperation implements QueryOperation<String, String> {
        @Override
        public String execute(String input) {
            return input;
        }
    }

    @Service
    static class ABusinessOperation implements BusinessOperation<String, String> {
        @Override
        public String execute(String input) {
            return input;
        }
    }

    @Service
    static class AService {
    }
}
