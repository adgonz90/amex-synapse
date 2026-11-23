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
package io.americanexpress.synapse.api.grpc.internal.edge;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.ErrorInfo;
import io.grpc.Context;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.grpc.server.advice.GrpcAdvice;
import org.springframework.grpc.server.advice.GrpcExceptionHandler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import io.americanexpress.synapse.api.grpc.GrpcBindings;
import io.americanexpress.synapse.api.grpc.fixture.proto.CatalogServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.GetItemRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.Item;
import io.americanexpress.synapse.api.grpc.fixture.proto.LabelItemRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.LabelServiceGrpc;
import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.operation.CommandOperation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A hand-written service (the escape hatch) that completes its call from a thread of its own, as a streaming producer
 * or a future's callback does, on a real Netty server with OpenTelemetry tracing. The call's gRPC {@link Context} is
 * not attached on that thread, yet the error the exception handlers write must still be the call's: the correlation id
 * in {@code ErrorInfo}, the trailer and the log line is the caller's, and the detail is in the caller's locale. A
 * status of the service's own keeps only its code unless {@code synapse.errors.include-detail} is set (decision 24),
 * while an exception that isn't a status still reaches the exception handlers, {@code @GrpcAdvice} included, and a
 * bound operation's status, which Synapse wrote, is never rewritten.
 */
@SpringBootTest(classes = GrpcAsyncCompletionIT.TestApplication.class, properties = {"spring.grpc.server.port=0",
        "synapse.errors.include-detail=true", "management.tracing.sampling.probability=1.0",
        "management.logging.export.otlp.enabled=false", "management.tracing.export.otlp.enabled=false",
        "management.otlp.metrics.export.enabled=false"})
@ActiveProfiles("test")
class GrpcAsyncCompletionIT {

    private static final Metadata.Key<String> CORRELATION = Metadata.Key
            .of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);

    private static final String COMPLETER = "escape-completer";

    @LocalGrpcServerPort
    private int port;

    @Autowired
    private CompletingService service;

    @Test
    @Tag("smoke")
    void synapseErrorFromAnotherThreadKeepsTheCallsIdAndLocale() throws InterruptedException {
        StatusRuntimeException failure = call("async-not-found", "x-correlation-id", "c-async", "accept-language",
                "fr");

        assertThat(service.completedOn).containsOnly(COMPLETER);
        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        ErrorInfo info = errorInfo(failure);
        assertThat(info.getReason()).isEqualTo("NOT_FOUND");
        assertThat(info.getMetadataMap()).containsEntry("correlationId", "c-async");
        assertThat(trailer(failure)).isEqualTo("c-async");
        assertThat(failure.getStatus().getDescription()).isEqualTo("Ressource introuvable");
    }

    @Test
    void unhandledFailureFromAnotherThreadIsLoggedUnderTheCallsId() throws InterruptedException {
        List<Logged> logged = new CopyOnWriteArrayList<>();
        StatusRuntimeException failure = withLog(logged, () -> call("async-boom", "x-correlation-id", "c-boom"));

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(errorInfo(failure).getMetadataMap()).containsEntry("correlationId", "c-boom");
        assertThat(trailer(failure)).isEqualTo("c-boom");
        assertThat(logged)
                .filteredOn(line -> line.message().startsWith("Unhandled error in gRPC call"))
                .singleElement()
                .satisfies(line -> assertThat(line.mdc()).containsEntry("correlationId", "c-boom"));
    }

    @Test
    void onErrorStatusKeepsItsDescriptionWithIncludeDetail() throws InterruptedException {
        StatusRuntimeException failure = call("async-status", "x-correlation-id", "c-status");

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
        assertThat(failure.getStatus().getDescription()).isEqualTo("secret");
        assertThat(errorInfo(failure).getReason()).isEqualTo("FORBIDDEN");
        assertThat(errorInfo(failure).getMetadataMap()).containsEntry("correlationId", "c-status");
    }

    @Test
    void onErrorWithAPlainExceptionStillReachesTheHandlers() throws InterruptedException {
        // Pin (R-11): the close() rewrite leaves UNKNOWN with a cause to Spring gRPC's exception handlers.
        List<Logged> logged = new CopyOnWriteArrayList<>();
        StatusRuntimeException failure = withLog(logged, () -> call("async-boom", "x-correlation-id", "c-plain"));

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(failure.getStatus().getDescription()).isEqualTo("An internal error occurred");
        assertThat(errorInfo(failure).getReason()).isEqualTo("INTERNAL");
        assertThat(logged)
                .filteredOn(line -> line.message().startsWith("Unhandled error in gRPC call"))
                .singleElement()
                .satisfies(line -> assertThat(line.level()).isEqualTo(Level.ERROR));
    }

    @Test
    void boundOperationsStatusIsNotRewritten() throws InterruptedException {
        // Pin (R-11): a bound operation's failure is Synapse's own status, which never reaches the close() rewrite.
        StatusRuntimeException failure = label(port, "x-correlation-id", "c-bound");

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(failure.getStatus().getDescription()).isEqualTo("Book 7 was not found");
        assertThat(errorInfo(failure).getReason()).isEqualTo("BOOK_NOT_FOUND");
        assertThat(errorInfo(failure).getMetadataMap()).containsEntry("correlationId", "c-bound");
    }

    /** The same service with {@code synapse.errors.include-detail} at its default, {@code false}. */
    @Nested
    @TestPropertySource(properties = "synapse.errors.include-detail=false")
    class WithoutDetail {

        @LocalGrpcServerPort
        private int withoutDetailPort;

        @Test
        @Tag("smoke")
        void onErrorStatusKeepsOnlyItsCode() throws InterruptedException {
            StatusRuntimeException failure = GrpcEdgeIT
                    .call(withoutDetailPort, "async-status", "x-correlation-id", "c-status")
                    .error();

            assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
            assertThat(failure.getStatus().getDescription()).isNullOrEmpty();
            ErrorInfo info = errorInfo(failure);
            assertThat(info.getReason()).isEqualTo("FORBIDDEN");
            assertThat(info.getMetadataMap()).containsEntry("correlationId", "c-status");
            assertThat(trailer(failure)).isEqualTo("c-status");
            assertThat(failure + " " + failure.getTrailers()).doesNotContain("secret");
        }

        @Test
        void grpcAdviceStatusIsLeftAlone() throws InterruptedException {
            // Pin (R-11): the advice's answer is the application's own, even without include-detail.
            StatusRuntimeException failure = GrpcEdgeIT.call(withoutDetailPort, "async-advised").error();

            assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
            assertThat(failure.getStatus().getDescription()).isEqualTo("advice");
        }
    }

    private StatusRuntimeException call(String id, String... pairs) throws InterruptedException {
        GrpcEdgeIT.Response response = GrpcEdgeIT.call(port, id, pairs);
        return response.error();
    }

    /** Runs the call and collects the lines logged on the gRPC logger meanwhile. */
    private static StatusRuntimeException withLog(List<Logged> logged, Call call) throws InterruptedException {
        Logger logger = (Logger) LoggerFactory.getLogger("io.americanexpress.synapse.api.grpc");
        AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                logged
                        .add(new Logged(event.getFormattedMessage(), event.getLevel(),
                                new HashMap<>(event.getMDCPropertyMap())));
            }
        };
        appender.start();
        logger.addAppender(appender);
        try {
            return call.run();
        } finally {
            logger.detachAppender(appender);
        }
    }

    /** Calls the bound {@code LabelItem}, whose operation fails with {@code BOOK_NOT_FOUND}. */
    private static StatusRuntimeException label(int port, String... pairs) throws InterruptedException {
        Metadata metadata = new Metadata();
        for (int i = 0; i < pairs.length; i += 2) {
            metadata.put(Metadata.Key.of(pairs[i], Metadata.ASCII_STRING_MARSHALLER), pairs[i + 1]);
        }
        ManagedChannel channel = Grpc
                .newChannelBuilderForAddress("localhost", port, InsecureChannelCredentials.create())
                .build();
        try {
            LabelServiceGrpc
                    .newBlockingStub(channel)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                    .labelItem(LabelItemRequest.newBuilder().addTags("7").build());
            throw new AssertionError("the call succeeded");
        } catch (StatusRuntimeException e) {
            return e;
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static @Nullable String trailer(StatusRuntimeException failure) {
        Metadata trailers = failure.getTrailers();
        return trailers == null ? null : trailers.get(CORRELATION);
    }

    private static ErrorInfo errorInfo(StatusRuntimeException e) {
        com.google.rpc.Status status = Objects.requireNonNull(StatusProto.fromThrowable(e), "status details");
        for (Any any : status.getDetailsList()) {
            if (any.is(ErrorInfo.class)) {
                try {
                    return any.unpack(ErrorInfo.class);
                } catch (InvalidProtocolBufferException ex) {
                    throw new AssertionError(ex);
                }
            }
        }
        throw new AssertionError("no ErrorInfo in " + status);
    }

    /** A call to make while the log is collected. */
    @FunctionalInterface
    interface Call {
        StatusRuntimeException run() throws InterruptedException;
    }

    /** A logged line, its level and the MDC it was logged with. */
    record Logged(String message, Level level, Map<String, String> mdc) {
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {

        @Bean(destroyMethod = "close")
        CompletingService completingService() {
            return new CompletingService();
        }

        @Bean
        MessageSource messageSource() {
            StaticMessageSource messages = new StaticMessageSource();
            messages.addMessage("NOT_FOUND", Locale.FRENCH, "Ressource introuvable");
            return messages;
        }

        @Bean
        LabelOperation labelOperation() {
            return new LabelOperation();
        }

        @Bean
        GrpcBindings labelBindings(LabelOperation labelOperation) {
            return GrpcBindings
                    .service(LabelServiceGrpc.getServiceDescriptor())
                    .unary(LabelServiceGrpc.getLabelItemMethod(), labelOperation)
                    .build();
        }

        @Bean
        Advice advice() {
            return new Advice();
        }
    }

    /** The fixture's error code. */
    enum ShelfErrors implements ErrorDetails {

        /** No book with the id. */
        BOOK_NOT_FOUND;

        @Override
        public String code() {
            return name();
        }

        @Override
        public String messageTemplate() {
            return "Book {0} was not found";
        }

        @Override
        public ErrorCategory category() {
            return ErrorCategory.NOT_FOUND;
        }
    }

    /** The input of the bound {@code LabelItem}. */
    record LabelInput(List<String> tags) {
    }

    /** A bound operation that never finds the book its first tag names. */
    static class LabelOperation implements CommandOperation<LabelInput, Void> {

        @Override
        public Void execute(LabelInput input) {
            throw new SynapseException(ShelfErrors.BOOK_NOT_FOUND, input.tags().getFirst());
        }
    }

    /** An exception that only the application's advice answers. */
    static class AdvisedException extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }

    /**
     * The application's advice, which answers {@link AdvisedException} before Synapse's handler would. It is public
     * because Spring gRPC calls its handler methods without making them accessible.
     */
    @GrpcAdvice
    public static class Advice {

        @GrpcExceptionHandler(AdvisedException.class)
        public Status advised(AdvisedException exception) {
            return Status.FAILED_PRECONDITION.withDescription("advice");
        }
    }

    /**
     * Completes {@code GetItem} with {@code onError} from its own thread, where no gRPC context is attached:
     * {@code async-not-found} with a {@link SynapseException}, {@code async-boom} with an exception Synapse doesn't
     * know, {@code async-status} with a status of its own and {@code async-advised} with an exception the
     * application's advice answers.
     */
    static class CompletingService extends CatalogServiceGrpc.CatalogServiceImplBase implements AutoCloseable {

        private final ExecutorService completer = Executors
                .newSingleThreadExecutor(runnable -> Thread.ofPlatform().name(COMPLETER).unstarted(runnable));

        final List<String> completedOn = new CopyOnWriteArrayList<>();

        @Override
        public void getItem(GetItemRequest request, StreamObserver<Item> responseObserver) {
            Throwable failure = switch (request.getId()) {
                case "async-not-found" -> new SynapseException(SynapseErrors.NOT_FOUND);
                case "async-boom" -> new IllegalStateException("boom");
                case "async-status" -> Status.PERMISSION_DENIED.withDescription("secret").asRuntimeException();
                case "async-advised" -> new AdvisedException();
                default -> new IllegalArgumentException("unknown probe " + request.getId());
            };
            completer.execute(() -> {
                completedOn.add(Thread.currentThread().getName());
                // The proof that this thread is outside the call: no state is reachable here.
                if (GrpcCallState.current() != null) {
                    throw new AssertionError("the call's gRPC context is attached on the completing thread");
                }
                responseObserver.onError(failure);
            });
        }

        @Override
        public void close() {
            completer.shutdownNow();
        }
    }
}
