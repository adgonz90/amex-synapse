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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.ErrorInfo;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import org.jspecify.annotations.Nullable;
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
import org.springframework.test.context.ActiveProfiles;

import io.americanexpress.synapse.api.grpc.fixture.proto.CatalogServiceGrpc;
import io.americanexpress.synapse.api.grpc.fixture.proto.GetItemRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.Item;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A hand-written service (the escape hatch) that completes its call from a thread of its own, as a streaming producer
 * or a future's callback does, on a real Netty server with OpenTelemetry tracing. The call's gRPC {@link Context} is
 * not attached on that thread, yet the error the exception handlers write must still be the call's: the correlation id
 * in {@code ErrorInfo}, the trailer and the log line is the caller's, and the detail is in the caller's locale.
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
        Logger logger = (Logger) LoggerFactory.getLogger("io.americanexpress.synapse.api.grpc");
        AppenderBase<ILoggingEvent> appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                logged.add(new Logged(event.getFormattedMessage(), new HashMap<>(event.getMDCPropertyMap())));
            }
        };
        appender.start();
        logger.addAppender(appender);
        StatusRuntimeException failure;
        try {
            failure = call("async-boom", "x-correlation-id", "c-boom");
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(errorInfo(failure).getMetadataMap()).containsEntry("correlationId", "c-boom");
        assertThat(trailer(failure)).isEqualTo("c-boom");
        assertThat(logged)
                .filteredOn(line -> line.message().startsWith("Unhandled error in gRPC call"))
                .singleElement()
                .satisfies(line -> assertThat(line.mdc()).containsEntry("correlationId", "c-boom"));
    }

    private StatusRuntimeException call(String id, String... pairs) throws InterruptedException {
        GrpcEdgeIT.Response response = GrpcEdgeIT.call(port, id, pairs);
        return response.error();
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

    /** A logged line and the MDC it was logged with. */
    record Logged(String message, Map<String, String> mdc) {
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
    }

    /**
     * Completes {@code GetItem} with {@code onError} from its own thread, where no gRPC context is attached:
     * {@code async-not-found} with a {@link SynapseException}, {@code async-boom} with an exception Synapse doesn't
     * know.
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
