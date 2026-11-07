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
package io.americanexpress.synapse.api.grpc.autoconfigure;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import com.google.rpc.ErrorInfo;
import io.grpc.BindableService;
import io.grpc.CallOptions;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerAutoConfiguration;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerServicesAutoConfiguration;
import org.springframework.boot.grpc.server.autoconfigure.security.GrpcServerSecurityAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.grpc.server.GrpcServerFactory;
import org.springframework.grpc.server.ServerBuilderCustomizer;
import org.springframework.grpc.server.exception.GrpcExceptionHandler;
import org.springframework.grpc.server.exception.GrpcExceptionHandlerInterceptor;
import org.springframework.grpc.server.security.SecurityGrpcExceptionHandler;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

import io.americanexpress.synapse.api.grpc.internal.error.GrpcErrorStatuses;
import io.americanexpress.synapse.api.grpc.internal.error.GrpcStatusWriter;
import io.americanexpress.synapse.api.grpc.internal.error.SynapseGrpcExceptionHandler;
import io.americanexpress.synapse.api.grpc.internal.service.RawBytesMarshaller;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.autoconfigure.SynapseContextAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseCoreAutoConfiguration;
import io.americanexpress.synapse.core.autoconfigure.SynapseErrorProperties;
import io.americanexpress.synapse.core.error.ErrorMessages;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;

import static org.assertj.core.api.Assertions.assertThat;

class SynapseGrpcErrorAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(
                    AutoConfigurations
                            .of(SynapseCoreAutoConfiguration.class, SynapseContextAutoConfiguration.class,
                                    SynapseGrpcErrorAutoConfiguration.class))
            .withBean(ErrorMessages.class, () -> new ErrorMessages(new StaticMessageSource()));

    @Test
    void activatesWithGrpcOnTheClasspath() {
        runner
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(SynapseGrpcErrorAutoConfiguration.class)
                        .hasSingleBean(SynapseGrpcProperties.class)
                        .hasSingleBean(GrpcErrorStatuses.class)
                        .hasSingleBean(GrpcStatusWriter.class)
                        .hasSingleBean(SynapseGrpcExceptionHandler.class)
                        .hasBean("synapseGrpcFallbackHandlers"));
    }

    @Test
    void absentWithoutGrpc() {
        runner
                .withClassLoader(new FilteredClassLoader(GrpcServerFactory.class))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(SynapseGrpcErrorAutoConfiguration.class)
                        .doesNotHaveBean(SynapseGrpcProperties.class)
                        .doesNotHaveBean(SynapseGrpcExceptionHandler.class)
                        .doesNotHaveBean("synapseGrpcFallbackHandlers"));
        runner
                .withClassLoader(new FilteredClassLoader(BindableService.class))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(SynapseGrpcErrorAutoConfiguration.class));
    }

    @Test
    void absentWhenTheGrpcServerIsDisabled() {
        runner
                .withPropertyValues("spring.grpc.server.enabled=false")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(SynapseGrpcErrorAutoConfiguration.class)
                        .doesNotHaveBean(SynapseGrpcProperties.class)
                        .doesNotHaveBean(GrpcStatusWriter.class)
                        .doesNotHaveBean("synapseGrpcFallbackHandlers"));
    }

    @Test
    void bindsTheStatusOverrides() {
        runner
                .withPropertyValues("synapse.api.grpc.errors.status.[NOT_FOUND]=FAILED_PRECONDITION",
                        "synapse.api.grpc.operations.get-book.errors.status.[NOT_FOUND]=OUT_OF_RANGE")
                .run(context -> {
                    GrpcErrorStatuses statuses = context.getBean(GrpcErrorStatuses.class);
                    assertThat(statuses.statusFor(SynapseErrors.NOT_FOUND, "get-book"))
                            .isEqualTo(Status.Code.OUT_OF_RANGE);
                    assertThat(statuses.statusFor(SynapseErrors.NOT_FOUND, "other"))
                            .isEqualTo(Status.Code.FAILED_PRECONDITION);
                });
    }

    @Test
    void okOverrideFailsStartup() {
        runner.withPropertyValues("synapse.api.grpc.errors.status.[NOT_FOUND]=OK").run(context -> {
            assertThat(context).hasFailed();
            Throwable cause = context.getStartupFailure();
            while (cause != null && !(cause instanceof SynapseConfigurationException)) {
                cause = cause.getCause();
            }
            assertThat(cause)
                    .isInstanceOfSatisfying(SynapseConfigurationException.class,
                            ex -> assertThat(ex.description())
                                    .startsWith("synapse.api.grpc.errors.status.[NOT_FOUND]"));
        });
    }

    @Test
    void includeDetailIsCoresSetting() {
        runner
                .withPropertyValues("synapse.errors.include-detail=true")
                .run(context -> assertThat(
                        written(context.getBean(GrpcStatusWriter.class)).getStatus().getDescription())
                        .isEqualTo("The resource was not found"));
        runner
                .run(context -> assertThat(
                        written(context.getBean(GrpcStatusWriter.class)).getStatus().getDescription())
                        .isNullOrEmpty());
    }

    @Test
    void registersTheHandlerLast() {
        List<Throwable> seenByUser = new ArrayList<>();
        GrpcExceptionHandler user = failure -> {
            seenByUser.add(failure);
            return null;
        };
        runner
                .withConfiguration(AutoConfigurations
                        .of(SslAutoConfiguration.class, GrpcServerServicesAutoConfiguration.class,
                                GrpcServerAutoConfiguration.class))
                .withPropertyValues("spring.grpc.server.port=0")
                .withBean("testService", BindableService.class,
                        () -> () -> ServerServiceDefinition.builder("test.TestService").build())
                .withBean("userExceptionHandler", GrpcExceptionHandler.class, () -> user)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GrpcExceptionHandlerInterceptor interceptor = context
                            .getBean(GrpcExceptionHandlerInterceptor.class);
                    SynapseException failure = new SynapseException(SynapseErrors.NOT_FOUND);
                    RecordingCall call = new RecordingCall();
                    ServerCallHandler<String, String> failing = (serverCall, headers) -> {
                        throw failure;
                    };

                    interceptor.interceptCall(call, new Metadata(), failing);

                    // The composite asked the user's handler first; it declined, so Synapse's answered.
                    assertThat(seenByUser).containsExactly(failure);
                    assertThat(call.closed).isNotNull();
                    Status closed = Objects.requireNonNull(call.closed);
                    assertThat(closed.getCode()).isEqualTo(Status.Code.NOT_FOUND);
                    com.google.rpc.Status details = StatusProto.fromStatusAndTrailers(closed, call.trailers);
                    assertThat(details).isNotNull();
                    assertThat(Objects.requireNonNull(details).getDetails(0).unpack(ErrorInfo.class).getReason())
                            .isEqualTo("NOT_FOUND");
                });
    }

    /**
     * Spring gRPC's composite handler takes the first non-null answer. Spring Security's handler is unordered, so it
     * ties with Synapse's (ordered last) and the one defined first answers first: the error auto-configuration runs
     * after Boot's gRPC security auto-configuration, so Security answers its own exceptions and Synapse everything else.
     */
    @Test
    void springSecurityAnswersItsOwnExceptionsBeforeSynapse() {
        runner
                .withConfiguration(AutoConfigurations
                        .of(SslAutoConfiguration.class, GrpcServerServicesAutoConfiguration.class,
                                GrpcServerAutoConfiguration.class, GrpcServerSecurityAutoConfiguration.class))
                .withPropertyValues("spring.grpc.server.port=0")
                .withBean("testService", BindableService.class,
                        () -> () -> ServerServiceDefinition.builder("test.TestService").build())
                .run(context -> {
                    assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(SecurityGrpcExceptionHandler.class)
                            .hasSingleBean(SynapseGrpcExceptionHandler.class);
                    GrpcExceptionHandlerInterceptor interceptor = context
                            .getBean(GrpcExceptionHandlerInterceptor.class);

                    RecordingCall denied = handled(interceptor, new AccessDeniedException("denied"));
                    assertThat(Objects.requireNonNull(denied.closed).getCode())
                            .isEqualTo(Status.Code.PERMISSION_DENIED);
                    assertThat(Objects
                            .requireNonNull(StatusProto.fromStatusAndTrailers(denied.closed, denied.trailers))
                            .getDetailsList())
                            .as("Spring Security's status, without Synapse's details")
                            .isEmpty();
                    RecordingCall unauthenticated = handled(interceptor, new BadCredentialsException("bad"));
                    assertThat(Objects.requireNonNull(unauthenticated.closed).getCode())
                            .isEqualTo(Status.Code.UNAUTHENTICATED);
                    assertThat(Objects
                            .requireNonNull(StatusProto
                                    .fromStatusAndTrailers(unauthenticated.closed, unauthenticated.trailers))
                            .getDetailsList()).isEmpty();

                    RecordingCall notFound = handled(interceptor, new SynapseException(SynapseErrors.NOT_FOUND));
                    assertThat(Objects.requireNonNull(notFound.closed).getCode()).isEqualTo(Status.Code.NOT_FOUND);
                    assertThat(reason(notFound)).isEqualTo("NOT_FOUND");
                    RecordingCall internal = handled(interceptor, new IllegalStateException("boom"));
                    assertThat(Objects.requireNonNull(internal.closed).getCode()).isEqualTo(Status.Code.INTERNAL);
                    assertThat(reason(internal)).isEqualTo("INTERNAL");
                });
    }

    @Test
    @SuppressWarnings("unchecked") // The bean is a customizer of any server builder.
    void fallbackHandlersAnswerUnknownMethods() {
        runner
                .withPropertyValues("synapse.correlation.header=X-Request-Id", "spring.application.name=books")
                .run(context -> {
                    ServerBuilderCustomizer<InProcessServerBuilder> customizer = context
                            .getBean("synapseGrpcFallbackHandlers", ServerBuilderCustomizer.class);
                    String name = InProcessServerBuilder.generateName();
                    InProcessServerBuilder builder = InProcessServerBuilder.forName(name).directExecutor();
                    customizer.customize(builder);
                    Server server = builder.build().start();
                    ManagedChannel channel = InProcessChannelBuilder.forName(name).directExecutor().build();
                    try {
                        Metadata.Key<String> requestId = Metadata.Key
                                .of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);
                        Metadata headers = new Metadata();
                        headers.put(requestId, "r-1");
                        AtomicReference<@Nullable Metadata> trailers = new AtomicReference<>();
                        MethodDescriptor<byte[], byte[]> unknown = MethodDescriptor
                                .newBuilder(RawBytesMarshaller.INSTANCE, RawBytesMarshaller.INSTANCE)
                                .setFullMethodName("test.TestService/Nope")
                                .setType(MethodDescriptor.MethodType.UNARY)
                                .build();
                        StatusRuntimeException failure = null;
                        try {
                            ClientCalls
                                    .blockingUnaryCall(ClientInterceptors
                                            .intercept(channel, MetadataUtils.newAttachHeadersInterceptor(headers),
                                                    MetadataUtils
                                                            .newCaptureMetadataInterceptor(new AtomicReference<>(),
                                                                    trailers)),
                                            unknown, CallOptions.DEFAULT, new byte[0]);
                        } catch (StatusRuntimeException e) {
                            failure = e;
                        }

                        assertThat(failure).isNotNull();
                        assertThat(Objects.requireNonNull(failure).getStatus().getCode())
                                .isEqualTo(Status.Code.UNIMPLEMENTED);
                        ErrorInfo info = Objects
                                .requireNonNull(StatusProto.fromThrowable(failure))
                                .getDetails(0)
                                .unpack(ErrorInfo.class);
                        assertThat(info.getReason()).isEqualTo("METHOD_NOT_FOUND");
                        assertThat(info.getDomain()).isEqualTo("books");
                        assertThat(info.getMetadataMap()).containsEntry("correlationId", "r-1");
                        assertThat(Objects.requireNonNull(trailers.get()).get(requestId)).isEqualTo("r-1");
                    } finally {
                        channel.shutdownNow();
                        server.shutdownNow();
                    }
                });
    }

    @Test
    void fallbackHandlersBackOffToABeanWithTheSameName() {
        ServerBuilderCustomizer<InProcessServerBuilder> user = builder -> {
        };
        runner
                .withBean("synapseGrpcFallbackHandlers", ServerBuilderCustomizer.class, () -> user)
                .run(context -> assertThat(context.getBean("synapseGrpcFallbackHandlers")).isSameAs(user));
    }

    private static RecordingCall handled(GrpcExceptionHandlerInterceptor interceptor, RuntimeException failure) {
        RecordingCall call = new RecordingCall();
        ServerCallHandler<String, String> failing = (serverCall, headers) -> {
            throw failure;
        };
        interceptor.interceptCall(call, new Metadata(), failing);
        return call;
    }

    private static String reason(RecordingCall call) throws Exception {
        com.google.rpc.Status details = StatusProto
                .fromStatusAndTrailers(Objects.requireNonNull(call.closed), call.trailers);
        return Objects.requireNonNull(details).getDetails(0).unpack(ErrorInfo.class).getReason();
    }

    @Test
    void backsOffToUserBeans() {
        GrpcErrorStatuses statuses = new GrpcErrorStatuses(Map.of(), Map.of());
        GrpcStatusWriter writer = new GrpcStatusWriter(statuses, new ErrorMessages(new StaticMessageSource()),
                new SynapseErrorProperties(false, null), "user");
        SynapseGrpcExceptionHandler handler = new SynapseGrpcExceptionHandler(writer);
        runner
                .withBean(GrpcErrorStatuses.class, () -> statuses)
                .withBean(GrpcStatusWriter.class, () -> writer)
                .withBean(SynapseGrpcExceptionHandler.class, () -> handler)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(GrpcErrorStatuses.class)).isSameAs(statuses);
                    assertThat(context.getBean(GrpcStatusWriter.class)).isSameAs(writer);
                    assertThat(context.getBean(SynapseGrpcExceptionHandler.class)).isSameAs(handler);
                });
        // Each bean backs off on its own: a user writer is used by Synapse's handler.
        runner.withBean(GrpcStatusWriter.class, () -> writer).run(context -> {
            assertThat(context).hasSingleBean(GrpcStatusWriter.class).hasSingleBean(SynapseGrpcExceptionHandler.class);
            StatusException handled = context
                    .getBean(SynapseGrpcExceptionHandler.class)
                    .handleException(new SynapseException(SynapseErrors.NOT_FOUND));
            assertThat(errorInfo(handled).getDomain()).isEqualTo("user");
        });
    }

    @Test
    void domainFromApplicationName() {
        runner
                .withPropertyValues("spring.application.name=books")
                .run(context -> assertThat(errorInfo(written(context.getBean(GrpcStatusWriter.class))).getDomain())
                        .isEqualTo("books"));
        runner
                .run(context -> assertThat(errorInfo(written(context.getBean(GrpcStatusWriter.class))).getDomain())
                        .isEqualTo("synapse"));
        runner
                .withPropertyValues("spring.application.name= ")
                .run(context -> assertThat(errorInfo(written(context.getBean(GrpcStatusWriter.class))).getDomain())
                        .isEqualTo("synapse"));
    }

    private static StatusException written(GrpcStatusWriter writer) {
        return writer.write(new SynapseException(SynapseErrors.NOT_FOUND), null);
    }

    private static ErrorInfo errorInfo(StatusException written) throws Exception {
        com.google.rpc.Status status = Objects.requireNonNull(StatusProto.fromThrowable(written));
        return status.getDetails(0).unpack(ErrorInfo.class);
    }

    /** A server call that records how it was closed. */
    private static final class RecordingCall extends ServerCall<String, String> {

        private static final MethodDescriptor.Marshaller<String> MARSHALLER = new MethodDescriptor.Marshaller<>() {

            @Override
            public InputStream stream(String value) {
                return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public String parse(InputStream stream) {
                return "";
            }
        };

        private final MethodDescriptor<String, String> method = MethodDescriptor
                .newBuilder(MARSHALLER, MARSHALLER)
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName("test.TestService/Call")
                .build();

        private @Nullable Status closed;

        private Metadata trailers = new Metadata();

        @Override
        public void request(int numMessages) {
        }

        @Override
        public void sendHeaders(Metadata headers) {
        }

        @Override
        public void sendMessage(String message) {
        }

        @Override
        public void close(Status status, Metadata trailers) {
            this.closed = status;
            this.trailers = trailers;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public MethodDescriptor<String, String> getMethodDescriptor() {
            return method;
        }
    }
}
