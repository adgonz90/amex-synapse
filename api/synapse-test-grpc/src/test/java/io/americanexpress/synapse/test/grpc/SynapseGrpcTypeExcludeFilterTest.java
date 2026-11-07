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

import io.grpc.BindableService;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.stub.StreamObserver;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;
import org.springframework.grpc.server.advice.GrpcAdvice;
import org.springframework.grpc.server.exception.GrpcExceptionHandler;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import io.americanexpress.synapse.api.grpc.GrpcBindings;
import io.americanexpress.synapse.core.operation.BusinessOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.test.grpc.fixture.proto.InventoryServiceGrpc;
import io.americanexpress.synapse.test.grpc.fixture.proto.PingReply;
import io.americanexpress.synapse.test.grpc.fixture.proto.PingRequest;
import io.americanexpress.synapse.test.grpc.fixture.proto.PingServiceGrpc;

import static org.assertj.core.api.Assertions.assertThat;

class SynapseGrpcTypeExcludeFilterTest {

    private final MetadataReaderFactory metadataReaderFactory = new SimpleMetadataReaderFactory();

    private boolean excluded(Class<?> type) throws IOException {
        SynapseGrpcTypeExcludeFilter filter = new SynapseGrpcTypeExcludeFilter();
        filter.setBeanClassLoader(getClass().getClassLoader());
        return filter.match(metadataReaderFactory.getMetadataReader(type.getName()), metadataReaderFactory);
    }

    @ParameterizedTest
    @ValueSource(classes = {AnImplBaseService.class, AGrpcService.class, ABindableService.class,
            AnExceptionHandler.class, AnInterceptor.class, AnAdvice.class, BindingsConfiguration.class,
            StaticBindingsConfiguration.class})
    void keepsTheGrpcLayerAndTheBindings(Class<?> type) throws IOException {
        assertThat(excluded(type)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(classes = {AnOperation.class, ABusinessOperation.class, AnInterceptingOperation.class,
            AService.class, OtherConfiguration.class})
    void leavesOutOperationsAndOtherComponents(Class<?> type) throws IOException {
        assertThat(excluded(type)).isTrue();
    }

    /** An operation is left out even when it also has a type the slice keeps. */
    @Service
    static class AnInterceptingOperation implements QueryOperation<String, String>, ServerInterceptor {
        @Override
        public String execute(String input) {
            return input;
        }

        @Override
        public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                ServerCallHandler<Q, R> next) {
            return next.startCall(call, headers);
        }
    }

    /** A hand-written service on grpc-java's generated base class. */
    @Service
    static class AnImplBaseService extends PingServiceGrpc.PingServiceImplBase {
        @Override
        public void ping(PingRequest request, StreamObserver<PingReply> responseObserver) {
            responseObserver.onCompleted();
        }
    }

    /** A hand-written service with Spring gRPC's stereotype. */
    @GrpcService
    static class AGrpcService extends PingServiceGrpc.PingServiceImplBase {
    }

    @Component
    static class ABindableService implements BindableService {
        @Override
        public ServerServiceDefinition bindService() {
            return ServerServiceDefinition.builder("synapse.slice.v1.Raw").build();
        }
    }

    /** Spring gRPC's counterpart of {@code @ControllerAdvice}. */
    @GrpcAdvice
    static class AnAdvice {
        @org.springframework.grpc.server.advice.GrpcExceptionHandler(IllegalArgumentException.class)
        Status handle(IllegalArgumentException exception) {
            return Status.INVALID_ARGUMENT;
        }
    }

    @Component
    static class AnExceptionHandler implements GrpcExceptionHandler {
        @Override
        public @Nullable StatusException handleException(Throwable exception) {
            return null;
        }
    }

    @Component
    static class AnInterceptor implements ServerInterceptor {
        @Override
        public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                ServerCallHandler<Q, R> next) {
            return next.startCall(call, headers);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class BindingsConfiguration {
        @Bean
        GrpcBindings bindings() {
            return GrpcBindings.service(InventoryServiceGrpc.getServiceDescriptor()).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class StaticBindingsConfiguration {
        @Bean
        static GrpcBindings bindings() {
            return GrpcBindings.service(InventoryServiceGrpc.getServiceDescriptor()).build();
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
