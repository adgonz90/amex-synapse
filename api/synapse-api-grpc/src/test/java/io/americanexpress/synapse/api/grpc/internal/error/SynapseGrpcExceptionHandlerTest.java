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
package io.americanexpress.synapse.api.grpc.internal.error;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.protobuf.Any;
import com.google.rpc.ErrorInfo;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.Ordered;

import io.americanexpress.synapse.api.grpc.internal.conditional.GrpcPreconditions;
import io.americanexpress.synapse.api.grpc.internal.edge.GrpcCallState;
import io.americanexpress.synapse.core.autoconfigure.SynapseErrorProperties;
import io.americanexpress.synapse.core.error.ErrorMessages;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.internal.context.ContextBinder;
import io.americanexpress.synapse.core.internal.context.ContextScope;
import io.americanexpress.synapse.core.internal.context.CorrelationIdPolicy;
import io.americanexpress.synapse.core.internal.context.InboundContext;

import static io.americanexpress.synapse.api.grpc.internal.error.GrpcStatusWriterTest.errorInfo;
import static io.americanexpress.synapse.api.grpc.internal.error.GrpcStatusWriterTest.state;
import static io.americanexpress.synapse.api.grpc.internal.error.GrpcStatusWriterTest.statusOf;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class SynapseGrpcExceptionHandlerTest {

    private static final String LOGGER = "io.americanexpress.synapse.api.grpc";

    private static final Metadata.Key<String> INTERNAL_TRAILER = Metadata.Key
            .of("x-internal", Metadata.ASCII_STRING_MARSHALLER);

    private static final Metadata.Key<byte[]> GRPC_DETAILS = Metadata.Key
            .of("grpc-status-details-bin", Metadata.BINARY_BYTE_MARSHALLER);

    private final SynapseGrpcExceptionHandler handler = handler(false);

    @Test
    void synapseExceptionIsMappedAndNotLogged(CapturedOutput output) {
        AtomicReference<StatusException> handled = new AtomicReference<>();

        List<ILoggingEvent> events = logged(() -> inCall(state("c-1"),
                () -> handled.set(handler.handleException(new SynapseException(BookErrors.BOOK_NOT_FOUND, 42)))));

        assertThat(handled.get().getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        ErrorInfo info = errorInfo(handled.get());
        assertThat(info.getReason()).isEqualTo("BOOK_NOT_FOUND");
        assertThat(info.getMetadataOrThrow("correlationId")).isEqualTo("c-1");
        assertThat(events).isEmpty();
        assertThat(output.getAll()).doesNotContain("ERROR");
    }

    @Test
    void unexpectedExceptionIsInternalAndLoggedOnce(CapturedOutput output) {
        IllegalStateException failure = new IllegalStateException("db password=x");
        AtomicReference<StatusException> handled = new AtomicReference<>();

        List<ILoggingEvent> events = logged(
                () -> inCall(state("c-1"), () -> handled.set(handler.handleException(failure))));

        StatusException status = handled.get();
        assertThat(status.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(status.getStatus().getDescription()).isNullOrEmpty();
        assertThat(status.getStatus().getCause()).isNull();
        ErrorInfo info = errorInfo(status);
        assertThat(info.getReason()).isEqualTo("INTERNAL");
        assertThat(info.getMetadataOrThrow("correlationId")).isEqualTo("c-1");
        assertNothingLeaks(status);

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getLoggerName()).isEqualTo(LOGGER);
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
            assertThat(event.getMDCPropertyMap()).containsEntry("correlationId", "c-1");
        });
        assertThat(output.getAll().lines().filter(line -> line.contains("ERROR")).count()).isEqualTo(1);
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void unexpectedExceptionTextNeverLeaksWithIncludeDetail() {
        StatusException status = handlerWithDetail().handleException(new IllegalStateException("db password=x"));

        // The detail is the INTERNAL template, never the exception's text.
        assertThat(status.getStatus().getDescription()).isEqualTo("An internal error occurred");
        assertNothingLeaks(status);
    }

    private static void assertNothingLeaks(StatusException status) {
        assertThat(String.valueOf(status.getStatus().getDescription()))
                .doesNotContain("IllegalStateException")
                .doesNotContain("password");
        assertThat(statusOf(status).toString()).doesNotContain("IllegalStateException").doesNotContain("password");
        assertThat(String.valueOf(status.getTrailers()))
                .doesNotContain("IllegalStateException")
                .doesNotContain("password");
    }

    @Test
    void unexpectedExceptionOutsideACallIsLoggedWithTheGeneratedCorrelationId() {
        AtomicReference<StatusException> handled = new AtomicReference<>();

        List<ILoggingEvent> events = logged(() -> handled.set(handler.handleException(new IllegalStateException("x"))));

        assertThat(handled.get().getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        String correlationId = errorInfo(handled.get()).getMetadataOrThrow("correlationId");
        assertThat(correlationId).matches(GrpcStatusWriterTest.UUID_PATTERN);
        // The logged line carries the id the caller received.
        assertThat(events)
                .singleElement()
                .satisfies(
                        event -> assertThat(event.getMDCPropertyMap()).containsEntry("correlationId", correlationId));
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void unexpectedExceptionBeforeTheEdgeResolvedAnIdLogsTheIdTheCallerReceives() {
        GrpcCallState state = new GrpcCallState(null, Map.of());
        AtomicReference<StatusException> handled = new AtomicReference<>();

        List<ILoggingEvent> events = logged(
                () -> inCall(state, () -> handled.set(handler.handleException(new IllegalStateException("x")))));

        String correlationId = errorInfo(handled.get()).getMetadataOrThrow("correlationId");
        assertThat(correlationId).matches(GrpcStatusWriterTest.UUID_PATTERN);
        assertThat(state.correlationId()).isEqualTo(correlationId);
        assertThat(events)
                .singleElement()
                .satisfies(
                        event -> assertThat(event.getMDCPropertyMap()).containsEntry("correlationId", correlationId));
    }

    @Test
    @SuppressWarnings("try") // The open context is the resource; the body never needs it.
    void openRequestContextIsLeftToTracing() {
        ContextBinder binder = new ContextBinder(Tracer.NOOP, new CorrelationIdPolicy(128), true, 16, 4096);

        List<ILoggingEvent> events;
        try (ContextScope scope = binder.open(InboundContext.empty())) {
            events = logged(() -> inCall(state("c-1"), () -> handler.handleException(new IllegalStateException("x"))));
        }

        // With a context open, its tracing baggage owns the MDC key; the handler doesn't add its own.
        assertThat(events)
                .singleElement()
                .satisfies(event -> assertThat(event.getMDCPropertyMap()).doesNotContainKey("correlationId"));
    }

    @Test
    void thrownStatusKeepsOnlyItsCode() throws Exception {
        AtomicReference<StatusException> handled = new AtomicReference<>();

        List<ILoggingEvent> events = logged(
                () -> inCall(state("c-1"), () -> handled.set(handler.handleException(downstreamNotFound()))));

        Status status = handled.get().getStatus();
        assertThat(status.getCode()).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(status.getDescription()).isNullOrEmpty();
        assertThat(status.getCause()).isNull();
        Metadata trailers = Objects.requireNonNull(handled.get().getTrailers(), "trailers");
        assertThat(trailers.containsKey(INTERNAL_TRAILER)).isFalse();
        com.google.rpc.Status details = statusOf(handled.get());
        assertThat(details.getMessage()).isEmpty();
        assertThat(details.getDetailsList()).hasSize(1);
        ErrorInfo info = details.getDetails(0).unpack(ErrorInfo.class);
        assertThat(info.getReason()).isEqualTo("NOT_FOUND");
        assertThat(info.getDomain()).isEqualTo("books");
        assertThat(info.getMetadataMap()).isEqualTo(Map.of("correlationId", "c-1"));
        assertThat(handled.get().toString() + trailers).doesNotContain("row 7");
        // C12: one DEBUG line with the code and the original description, never at ERROR.
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getLoggerName()).isEqualTo(LOGGER);
            assertThat(event.getFormattedMessage()).contains("NOT_FOUND").contains("row 7 in table x");
        });
    }

    @Test
    void thrownStatusKeepsItsDescriptionWithIncludeDetail() throws Exception {
        AtomicReference<StatusException> handled = new AtomicReference<>();

        inCall(state("c-1"), () -> handled.set(handlerWithDetail().handleException(downstreamNotFound())));

        Status status = handled.get().getStatus();
        assertThat(status.getCode()).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(status.getDescription()).isEqualTo("row 7 in table x");
        Metadata trailers = Objects.requireNonNull(handled.get().getTrailers(), "trailers");
        assertThat(trailers.get(INTERNAL_TRAILER)).isEqualTo("1");
        // R-10: the foreign details are always replaced by Synapse's ErrorInfo.
        com.google.rpc.Status details = statusOf(handled.get());
        assertThat(details.getMessage()).isEqualTo("row 7 in table x");
        assertThat(details.getDetailsList()).hasSize(1);
        ErrorInfo info = details.getDetails(0).unpack(ErrorInfo.class);
        assertThat(info.getReason()).isEqualTo("NOT_FOUND");
        assertThat(info.getDomain()).isEqualTo("books");
        assertThat(info.getMetadataMap()).isEqualTo(Map.of("correlationId", "c-1"));
        assertThat(trailers.getAll(GRPC_DETAILS)).hasSize(1);
    }

    @Test
    void thrownCheckedStatusKeepsOnlyItsCodeToo() {
        AtomicReference<StatusException> handled = new AtomicReference<>();

        inCall(state("c-1"), () -> handled
                .set(handler.handleException(Status.FAILED_PRECONDITION.withDescription("stock row 9").asException())));

        assertThat(handled.get().getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(handled.get().getStatus().getDescription()).isNullOrEmpty();
        assertThat(errorInfo(handled.get()).getReason()).isEqualTo("PRECONDITION_FAILED");
        assertThat(errorInfo(handled.get()).getMetadataOrThrow("correlationId")).isEqualTo("c-1");
    }

    @Test
    void thrownOkStatusIsAnUnexpectedFailure() {
        AtomicReference<StatusException> handled = new AtomicReference<>();

        List<ILoggingEvent> events = logged(() -> inCall(state("c-1"),
                () -> handled.set(handler.handleException(Status.OK.withDescription("fine").asRuntimeException()))));

        // A status exception that says OK is no error the caller can get: it is the service's bug.
        assertThat(handled.get().getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(errorInfo(handled.get()).getReason()).isEqualTo("INTERNAL");
        assertThat(events).singleElement().satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
    }

    /**
     * A downstream call's failure, as a stub throws it: {@code NOT_FOUND} with an internal description, a trailer of
     * its own and the downstream service's {@code ErrorInfo}.
     */
    private static StatusRuntimeException downstreamNotFound() {
        Metadata trailers = new Metadata();
        trailers.put(INTERNAL_TRAILER, "1");
        com.google.rpc.Status downstream = com.google.rpc.Status
                .newBuilder()
                .setCode(Status.Code.NOT_FOUND.value())
                .setMessage("row 7 in table x")
                .addDetails(Any
                        .pack(ErrorInfo
                                .newBuilder()
                                .setReason("ROW_MISSING")
                                .setDomain("other")
                                .putMetadata("correlationId", "c-downstream")
                                .build()))
                .build();
        return StatusProto.toStatusRuntimeException(downstream, trailers);
    }

    @Test
    void staleWriteFromAnOperationIsAborted() {
        // What the unary handler throws when an operation reports STALE_VERSION on a request that sent an etag.
        SynapseException stale = GrpcPreconditions.translate(true, new SynapseException(SynapseErrors.STALE_VERSION));
        GrpcCallState state = state("c-1");
        state.operationName("update-book");
        AtomicReference<StatusException> handled = new AtomicReference<>();

        List<ILoggingEvent> events = logged(() -> inCall(state, () -> handled.set(handler.handleException(stale))));

        // AIP-154: a stale etag is ABORTED. The code is REST's 412 code, the same on both protocols.
        assertThat(handled.get().getStatus().getCode()).isEqualTo(Status.Code.ABORTED);
        assertThat(errorInfo(handled.get()).getReason()).isEqualTo("PRECONDITION_FAILED");
        assertThat(events).isEmpty();
    }

    @Test
    void isOrderedLast() {
        assertThat(handler.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }

    private static SynapseGrpcExceptionHandler handler(boolean includeDetail) {
        return new SynapseGrpcExceptionHandler(new GrpcStatusWriter(new GrpcErrorStatuses(Map.of(), Map.of()),
                new ErrorMessages(new StaticMessageSource()), new SynapseErrorProperties(includeDetail, null),
                "books"));
    }

    private static SynapseGrpcExceptionHandler handlerWithDetail() {
        return handler(true);
    }

    private static void inCall(GrpcCallState state, Runnable action) {
        Context.current().withValue(GrpcCallState.KEY, state).run(action);
    }

    /** Runs the action and returns the events logged on the gRPC logger meanwhile. */
    private static List<ILoggingEvent> logged(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(LOGGER);
        Level level = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            action.run();
        } finally {
            logger.setLevel(level);
            logger.detachAppender(appender);
        }
        return appender.list;
    }
}
