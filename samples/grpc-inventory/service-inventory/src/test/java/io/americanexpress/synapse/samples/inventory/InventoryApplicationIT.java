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
package io.americanexpress.synapse.samples.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.ErrorInfo;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Deadline;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.grpc.reflection.v1.ServerReflectionGrpc;
import io.grpc.reflection.v1.ServerReflectionRequest;
import io.grpc.reflection.v1.ServerReflectionResponse;
import io.grpc.reflection.v1.ServiceResponse;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import io.americanexpress.synapse.samples.inventory.contract.proto.CreateItemRequest;
import io.americanexpress.synapse.samples.inventory.contract.proto.GetItemRequest;
import io.americanexpress.synapse.samples.inventory.contract.proto.InventoryServiceGrpc;
import io.americanexpress.synapse.samples.inventory.contract.proto.Item;
import io.americanexpress.synapse.samples.inventory.contract.proto.ListItemsRequest;
import io.americanexpress.synapse.samples.inventory.contract.proto.Location;
import io.americanexpress.synapse.samples.inventory.data.InMemoryItems;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The whole service on Boot's Netty gRPC server, on a free port, in the {@code test} environment: the edge (correlation
 * id, deadline) and server reflection, which only a real server has.
 */
@SpringBootTest(properties = "spring.grpc.server.port=0")
@ActiveProfiles("test")
class InventoryApplicationIT {

    private static final Metadata.Key<String> CORRELATION = Metadata.Key
            .of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);

    /** Metadata that makes {@link ExpiredDeadlines} give the call an expired deadline on the server. */
    private static final String EXPIRE_KEY = "x-test-expire";

    private static final Metadata.Key<String> EXPIRE = Metadata.Key.of(EXPIRE_KEY, Metadata.ASCII_STRING_MARSHALLER);

    /** The expired deadlines' scheduler: a non-daemon thread, so it is stopped after the tests. */
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor();

    @AfterAll
    static void stopScheduler() {
        SCHEDULER.shutdownNow();
    }

    @LocalGrpcServerPort
    int port;

    @Test
    @Tag("smoke")
    void correlationIdIsEchoed() throws InterruptedException {
        Metadata sent = new Metadata();
        sent.put(CORRELATION, "inventory-it-1");
        AtomicReference<@Nullable Metadata> headers = new AtomicReference<>();
        AtomicReference<@Nullable Metadata> trailers = new AtomicReference<>();
        ManagedChannel channel = channel(port);
        try {
            Item widget = InventoryServiceGrpc
                    .newBlockingStub(channel)
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(sent),
                            MetadataUtils.newCaptureMetadataInterceptor(headers, trailers))
                    .getItem(GetItemRequest.newBuilder().setId(InMemoryItems.WIDGET.toString()).build());

            assertThat(widget.getSkuCode()).isEqualTo("WID-001");
            Metadata received = headers.get();
            assertThat(received).isNotNull();
            assertThat(received.get(CORRELATION)).isEqualTo("inventory-it-1");
        } finally {
            close(channel);
        }
    }

    /**
     * A call whose deadline has passed when it reaches the service. A client-side deadline that short (1 ns) never
     * leaves the client: grpc-java fails the call itself, so the service would not be tested. {@link ExpiringInterceptor}
     * gives the call an expired gRPC deadline on the server instead, as a deadline that expired in transit would be,
     * and Synapse's pipeline then fails it before the operation runs.
     */
    @Test
    void expiredDeadlineIsDeadlineExceeded() throws InterruptedException {
        ManagedChannel channel = channel(port);
        try {
            InventoryServiceGrpc.InventoryServiceBlockingStub inventory = InventoryServiceGrpc
                    .newBlockingStub(channel)
                    .withDeadlineAfter(10, TimeUnit.SECONDS);
            CreateItemRequest late = CreateItemRequest
                    .newBuilder()
                    .setSkuCode("LATE-001")
                    .setLocation(Location.newBuilder().setWarehouse("east").setAisle("A1").setBin(1))
                    .build();
            Metadata expired = new Metadata();
            expired.put(EXPIRE, "1");
            expired.put(CORRELATION, "inventory-it-late");

            StatusRuntimeException e = catchThrowableOfType(StatusRuntimeException.class, () -> inventory
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(expired))
                    .createItem(late));

            assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED);
            // Synapse's answer, not the client's: a client-side failure has no ErrorInfo.
            ErrorInfo info = errorInfo(e);
            assertThat(info.getReason()).isEqualTo("DEADLINE_EXCEEDED");
            assertThat(info.getMetadataMap()).containsEntry("correlationId", "inventory-it-late");
            // The operation didn't run: no item was created.
            assertThat(skus(inventory)).doesNotContain("LATE-001");
            // Without the expired deadline the same request creates the item.
            assertThat(inventory.createItem(late).getSkuCode()).isEqualTo("LATE-001");
            assertThat(skus(inventory)).contains("LATE-001");
        } finally {
            close(channel);
        }
    }

    @Test
    void reflectionIsOffByDefault() throws InterruptedException {
        // Synapse sets spring.grpc.server.reflection.enabled to false in every environment.
        StatusRuntimeException e = catchThrowableOfType(StatusRuntimeException.class, () -> listServices(port));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNIMPLEMENTED);
    }

    /** The service with what application-local.yaml turns on: server reflection, which grpcurl uses. */
    @Nested
    @TestPropertySource(properties = "spring.grpc.server.reflection.enabled=true")
    class Local {

        @LocalGrpcServerPort
        int localPort;

        @Test
        void reflectionListsTheService() throws InterruptedException {
            assertThat(listServices(localPort)).contains("synapse.samples.inventory.v1.InventoryService");
        }
    }

    /**
     * Gives calls that carry {@value #EXPIRE_KEY} an expired gRPC deadline on the server. It runs after Boot's
     * exception handling (order 1) and before Synapse's context interceptor (order 100), which turns the deadline into
     * the request's, as a caller's deadline that expired in transit would arrive. A test fixture: a service has no
     * such interceptor.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ExpiredDeadlines {

        @Bean
        @Order(50)
        @GlobalServerInterceptor
        ServerInterceptor expiringInterceptor() {
            return new ServerInterceptor() {

                @Override
                public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                        Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                    if (!headers.containsKey(EXPIRE)) {
                        return next.startCall(call, headers);
                    }
                    Context expired = Context.current().withDeadline(Deadline.after(-1, TimeUnit.SECONDS), SCHEDULER);
                    return Contexts.interceptCall(expired, call, headers, next);
                }
            };
        }
    }

    private static List<String> skus(InventoryServiceGrpc.InventoryServiceBlockingStub inventory) {
        return inventory
                .listItems(ListItemsRequest.newBuilder().setPageSize(100).build())
                .getItemsList()
                .stream()
                .map(Item::getSkuCode)
                .toList();
    }

    private static ErrorInfo errorInfo(StatusRuntimeException e) {
        com.google.rpc.Status status = Objects.requireNonNull(StatusProto.fromThrowable(e), "status details");
        for (Any detail : status.getDetailsList()) {
            if (detail.is(ErrorInfo.class)) {
                try {
                    return detail.unpack(ErrorInfo.class);
                } catch (InvalidProtocolBufferException ex) {
                    throw new AssertionError(ex);
                }
            }
        }
        throw new AssertionError("no ErrorInfo in " + status);
    }

    private static ManagedChannel channel(int port) {
        return Grpc.newChannelBuilderForAddress("localhost", port, InsecureChannelCredentials.create()).build();
    }

    private static void close(ManagedChannel channel) throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    /** Lists the services through server reflection, or throws its status. */
    private static List<String> listServices(int port) throws InterruptedException {
        ManagedChannel channel = channel(port);
        CompletableFuture<List<String>> done = new CompletableFuture<>();
        try {
            List<String> services = new ArrayList<>();
            StreamObserver<ServerReflectionRequest> requests = ServerReflectionGrpc
                    .newStub(channel)
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .serverReflectionInfo(new StreamObserver<>() {

                        @Override
                        public void onNext(ServerReflectionResponse response) {
                            response
                                    .getListServicesResponse()
                                    .getServiceList()
                                    .stream()
                                    .map(ServiceResponse::getName)
                                    .forEach(services::add);
                        }

                        @Override
                        public void onError(Throwable t) {
                            done.completeExceptionally(t);
                        }

                        @Override
                        public void onCompleted() {
                            done.complete(services);
                        }
                    });
            requests.onNext(ServerReflectionRequest.newBuilder().setListServices("*").build());
            requests.onCompleted();
            return done.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof StatusRuntimeException status) {
                throw status;
            }
            throw new IllegalStateException(e);
        } catch (TimeoutException e) {
            throw new IllegalStateException(e);
        } finally {
            close(channel);
        }
    }
}
