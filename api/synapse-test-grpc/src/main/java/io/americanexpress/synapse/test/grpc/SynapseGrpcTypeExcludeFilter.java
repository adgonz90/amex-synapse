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
package io.americanexpress.synapse.test.grpc;

import java.io.IOException;
import java.util.Set;

import io.grpc.BindableService;
import io.grpc.ServerInterceptor;
import org.springframework.boot.test.context.filter.annotation.AnnotationCustomizableTypeExcludeFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.grpc.server.advice.GrpcAdvice;
import org.springframework.grpc.server.exception.GrpcExceptionHandler;

import io.americanexpress.synapse.api.grpc.GrpcBindings;
import io.americanexpress.synapse.core.operation.BusinessOperation;
import io.americanexpress.synapse.core.operation.ServiceOperation;

/**
 * Keeps component scanning to the gRPC layer for {@link SynapseGrpcTest}: hand-written services
 * ({@link BindableService} implementations), {@link GrpcAdvice} classes, {@link GrpcExceptionHandler}s,
 * {@link ServerInterceptor}s, and the configuration classes that declare a {@link GrpcBindings} {@code @Bean}.
 * Operations ({@link ServiceOperation} and {@link BusinessOperation}) are always left out, even when they also have
 * one of those types, and so are business beans and every other component. As in Spring Boot's slices, components
 * are kept by their own type: a configuration class that declares an interceptor, exception handler or service as a
 * {@code @Bean} is left out, and the test brings it in with {@code @Import}.
 */
public final class SynapseGrpcTypeExcludeFilter extends AnnotationCustomizableTypeExcludeFilter {

    private static final Set<Class<?>> DEFAULT_INCLUDES = Set
            .of(BindableService.class, GrpcAdvice.class, GrpcExceptionHandler.class, ServerInterceptor.class);

    private static final String BINDINGS_TYPE = GrpcBindings.class.getName();

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
     * {@link GrpcBindings}.
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

    /**
     * Excludes operations, which the test replaces with test doubles.
     *
     * @param metadataReader the class being scanned
     * @param metadataReaderFactory the factory for the metadata of other classes
     * @return whether the class is left out
     * @throws IOException if the class metadata cannot be read
     */
    @Override
    protected boolean exclude(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory)
            throws IOException {
        return super.exclude(metadataReader, metadataReaderFactory)
                || isTypeOrAnnotated(metadataReader, metadataReaderFactory, ServiceOperation.class)
                || isTypeOrAnnotated(metadataReader, metadataReaderFactory, BusinessOperation.class);
    }

    private static boolean declaresBindings(AnnotationMetadata metadata) {
        return metadata
                .getAnnotatedMethods(Bean.class.getName())
                .stream()
                .anyMatch(method -> BINDINGS_TYPE.equals(method.getReturnTypeName()));
    }
}
