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
package io.americanexpress.synapse.api.grpc.internal.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.ErrorInfo;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.reflection.v1.ServerReflectionGrpc;
import io.grpc.reflection.v1.ServerReflectionRequest;
import io.grpc.reflection.v1.ServerReflectionResponse;
import io.grpc.reflection.v1.ServiceResponse;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * gRPC server reflection: off by default, turned on by an environment file. The enclosing class starts nothing; each
 * nested class starts the one context and server it needs.
 */
class GrpcReflectionIT {

    private static final String CATALOG = "synapse.fixture.v1.CatalogService";

    /** Sends the requests on one reflection stream and returns the responses. */
    static GrpcBindingsIT.Result<List<ServerReflectionResponse>> reflect(int port,
            ServerReflectionRequest... requests) throws InterruptedException {
        CompletableFuture<List<ServerReflectionResponse>> done = new CompletableFuture<>();
        return GrpcBindingsIT.call(port, channel -> {
            List<ServerReflectionResponse> responses = new ArrayList<>();
            StreamObserver<ServerReflectionRequest> stream = ServerReflectionGrpc
                    .newStub(channel)
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .serverReflectionInfo(new StreamObserver<>() {

                        @Override
                        public void onNext(ServerReflectionResponse value) {
                            responses.add(value);
                        }

                        @Override
                        public void onError(Throwable t) {
                            done.completeExceptionally(t);
                        }

                        @Override
                        public void onCompleted() {
                            done.complete(responses);
                        }
                    });
            for (ServerReflectionRequest request : requests) {
                stream.onNext(request);
            }
            stream.onCompleted();
            try {
                return done.get(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof StatusRuntimeException status) {
                    throw status;
                }
                throw new IllegalStateException(e);
            } catch (TimeoutException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /**
     * Runs in the environment {@code test}, which leaves reflection at Synapse's default: off, so the reflection
     * service is an unknown method.
     */
    @Nested
    @SpringBootTest(classes = GrpcBindingsIT.TestApplication.class, properties = {"spring.grpc.server.port=0",
            "management.logging.export.otlp.enabled=false", "management.tracing.export.otlp.enabled=false",
            "management.otlp.metrics.export.enabled=false"})
    @ActiveProfiles("test")
    class OffByDefault {

        @LocalGrpcServerPort
        private int port;

        @Test
        @Tag("smoke")
        void reflectionIsOffByDefault() throws InterruptedException {
            GrpcBindingsIT.Result<List<ServerReflectionResponse>> result = reflect(port,
                    ServerReflectionRequest.newBuilder().setListServices("*").build());

            StatusRuntimeException e = result.error();
            assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNIMPLEMENTED);
            ErrorInfo info = GrpcBindingsIT.detail(e, ErrorInfo.class);
            assertThat(info.getReason()).isEqualTo("METHOD_NOT_FOUND");
            assertThat(result.echoed()).isNotBlank().isEqualTo(info.getMetadataMap().get("correlationId"));
        }
    }

    /**
     * Runs in the environment {@code reflection} instead of {@code test}, still with exactly one active profile: it
     * proves that an environment file ({@code application-reflection.yaml}) turns reflection on.
     */
    @Nested
    @SpringBootTest(classes = GrpcBindingsIT.TestApplication.class, properties = {"spring.grpc.server.port=0",
            "management.logging.export.otlp.enabled=false", "management.tracing.export.otlp.enabled=false",
            "management.otlp.metrics.export.enabled=false"})
    @ActiveProfiles("reflection")
    class EnvironmentFile {

        @LocalGrpcServerPort
        private int reflectionPort;

        @Test
        @Tag("smoke")
        void anEnvironmentFileTurnsReflectionOn() throws InterruptedException, InvalidProtocolBufferException {
            List<ServerReflectionResponse> responses = reflect(reflectionPort,
                    ServerReflectionRequest.newBuilder().setListServices("*").build(),
                    ServerReflectionRequest.newBuilder().setFileContainingSymbol(CATALOG).build()).ok();

            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).getListServicesResponse().getServiceList())
                    .extracting(ServiceResponse::getName)
                    .contains(CATALOG);
            List<String> files = new ArrayList<>();
            for (ByteString file : responses.get(1).getFileDescriptorResponse().getFileDescriptorProtoList()) {
                files.add(FileDescriptorProto.parseFrom(file).getName());
            }
            assertThat(files).contains("synapse/fixture/v1/catalog.proto");
        }
    }
}
