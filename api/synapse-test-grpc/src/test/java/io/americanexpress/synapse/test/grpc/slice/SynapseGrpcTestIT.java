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
package io.americanexpress.synapse.test.grpc.slice;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.ErrorInfo;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.MetadataUtils;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.grpc.client.ImportGrpcClients;
import org.springframework.grpc.server.DefaultGrpcServerFactory;
import org.springframework.grpc.server.GrpcServerFactory;
import org.springframework.grpc.server.NettyGrpcServerFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.test.grpc.SynapseGrpcTest;
import io.americanexpress.synapse.test.grpc.fixture.proto.CatalogServiceGrpc;
import io.americanexpress.synapse.test.grpc.fixture.proto.GetItemRequest;
import io.americanexpress.synapse.test.grpc.fixture.proto.Item;
import io.americanexpress.synapse.test.grpc.fixture.proto.PingRequest;
import io.americanexpress.synapse.test.grpc.fixture.proto.PingServiceGrpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mockingDetails;

@SynapseGrpcTest
@ImportGrpcClients(types = {CatalogServiceGrpc.CatalogServiceBlockingStub.class,
        PingServiceGrpc.PingServiceBlockingStub.class})
@Tag("smoke")
class SynapseGrpcTestIT {

    private static final Metadata.Key<String> CORRELATION = Metadata.Key
            .of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);

    @Autowired
    CatalogServiceGrpc.CatalogServiceBlockingStub stub;

    @Autowired
    PingServiceGrpc.PingServiceBlockingStub ping;

    @Autowired
    ApplicationContext context;

    @Autowired
    Environment environment;

    @MockitoBean
    GetItemOperation getItem;

    private static GetItemRequest request(String id) {
        return GetItemRequest.newBuilder().setId(id).build();
    }

    private CatalogServiceGrpc.CatalogServiceBlockingStub catalog() {
        return stub.withDeadlineAfter(10, TimeUnit.SECONDS);
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

    @Test
    void slicesWithMockedOperationsStart() {
        assertThat(mockingDetails(getItem).isMock()).isTrue();
        assertThat(context.getBean(GetItemOperation.class)).isSameAs(getItem);
    }

    @Test
    void servesBindingsInProcess() {
        given(getItem.execute(any())).willReturn(Optional.of(new ItemView("i-1", "Dune", 4)));

        Item item = catalog().getItem(request("i-1"));

        assertThat(item.getId()).isEqualTo("i-1");
        assertThat(item.getName()).isEqualTo("Dune");
        assertThat(item.getRating()).isEqualTo(4);
        then(getItem).should().execute(new ItemKey("i-1"));
    }

    @Test
    void errorsCarryErrorInfo() {
        willThrow(new SynapseException(ItemErrors.ITEM_NOT_FOUND, 1)).given(getItem).execute(any());

        StatusRuntimeException e = catchThrowableOfType(StatusRuntimeException.class,
                () -> catalog().getItem(request("1")));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        // application-test.yaml turns synapse.errors.include-detail on.
        assertThat(e.getStatus().getDescription()).isEqualTo("Item 1 was not found");
        ErrorInfo info = errorInfo(e);
        assertThat(info.getReason()).isEqualTo("ITEM_NOT_FOUND");
        assertThat(info.getMetadataMap()).containsKey("correlationId");
    }

    @Test
    void edgeInterceptorsRun() {
        given(getItem.execute(any())).willReturn(Optional.of(new ItemView("i-1", "Dune", 4)));
        Metadata sent = new Metadata();
        sent.put(CORRELATION, "c-1");
        AtomicReference<@Nullable Metadata> headers = new AtomicReference<>();
        AtomicReference<@Nullable Metadata> trailers = new AtomicReference<>();

        catalog()
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(sent),
                        MetadataUtils.newCaptureMetadataInterceptor(headers, trailers))
                .getItem(request("i-1"));

        assertThat(Objects.requireNonNull(headers.get()).get(CORRELATION)).isEqualTo("c-1");
    }

    @Test
    void servesHandWrittenServices() {
        String reply = ping
                .withDeadlineAfter(10, TimeUnit.SECONDS)
                .ping(PingRequest.newBuilder().setMessage("hi").build())
                .getMessage();

        assertThat(reply).isEqualTo("pong hi");
    }

    @Test
    void appliesTheApplicationsGrpcAdvice() {
        willThrow(new IllegalArgumentException("bad id")).given(getItem).execute(any());

        StatusRuntimeException e = catchThrowableOfType(StatusRuntimeException.class,
                () -> catalog().getItem(request("1")));

        // As in production: the application's advice answers before Synapse's handler, which would say INTERNAL.
        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(e.getStatus().getDescription()).isEqualTo("rejected by the advice");
    }

    @Test
    void excludesOtherComponents() {
        assertThat(context.getBeansOfType(UnrelatedService.class)).isEmpty();
        assertThat(context.getBeansOfType(GetItemOperation.class).values()).containsExactly(getItem);
        assertThat(context.containsBeanDefinition("getItemOperation")).isFalse();
    }

    @Test
    void runsInTheTestEnvironment() {
        assertThat(environment.getActiveProfiles()).containsExactly("test");
    }

    @Test
    void noNetworkServer() {
        assertThat(context.getBeansOfType(NettyGrpcServerFactory.class)).isEmpty();
        assertThat(context.getBeansOfType(GrpcServerFactory.class).values())
                .singleElement()
                .isInstanceOf(DefaultGrpcServerFactory.class);
    }
}
