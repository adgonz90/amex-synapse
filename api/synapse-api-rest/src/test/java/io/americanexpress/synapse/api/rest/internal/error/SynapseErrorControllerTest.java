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
package io.americanexpress.synapse.api.rest.internal.error;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.tracing.Tracer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.internal.edge.RequestTooLargeException;
import io.americanexpress.synapse.api.rest.internal.edge.RestRequestAttributes;
import io.americanexpress.synapse.core.autoconfigure.SynapseErrorProperties;
import io.americanexpress.synapse.core.error.ErrorMessages;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.internal.context.ContextBinder;
import io.americanexpress.synapse.core.internal.context.ContextScope;
import io.americanexpress.synapse.core.internal.context.CorrelationIdPolicy;
import io.americanexpress.synapse.core.internal.context.InboundContext;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class SynapseErrorControllerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final StaticMessageSource messages = new StaticMessageSource();

    private final SynapseErrorController controller = new SynapseErrorController(
            new ProblemWriter(new RestErrorStatuses(Map.of(), Map.of()), new ErrorMessages(messages),
                    new ProblemTitles(messages), new SynapseErrorProperties(false, null), MAPPER,
                    "X-Correlation-Id"));

    private JsonNode errorWithStatus(@Nullable Object status, MockHttpServletResponse response) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        if (status != null) {
            request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
        }
        controller.error(request, response);
        return MAPPER.readTree(response.getContentAsByteArray());
    }

    private void assertMaps(int incoming, int status, String code) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        JsonNode body = errorWithStatus(incoming, response);

        assertThat(response.getStatus()).as("status for " + incoming).isEqualTo(status);
        assertThat(body.get("code").asString()).as("code for " + incoming).isEqualTo(code);
        assertThat(response.getContentType()).startsWith("application/problem+json");
    }

    @Test
    void mapsContainerStatusesToSynapseErrors() {
        assertMaps(404, 404, "ROUTE_NOT_FOUND");
        assertMaps(405, 405, "METHOD_NOT_ALLOWED");
        assertMaps(406, 406, "NOT_ACCEPTABLE");
        assertMaps(413, 413, "PAYLOAD_TOO_LARGE");
        assertMaps(415, 415, "UNSUPPORTED_MEDIA_TYPE");
        assertMaps(400, 400, "MALFORMED_REQUEST");
        assertMaps(503, 503, "UNAVAILABLE");
        assertMaps(500, 500, "INTERNAL");
        assertMaps(401, 401, "UNAUTHENTICATED");
        assertMaps(403, 403, "FORBIDDEN");
        assertMaps(409, 409, "CONFLICT");
        assertMaps(412, 412, "PRECONDITION_FAILED");
        assertMaps(429, 429, "RATE_LIMITED");
        assertMaps(504, 504, "DEADLINE_EXCEEDED");
        assertMaps(418, 400, "MALFORMED_REQUEST");
        assertMaps(502, 500, "INTERNAL");
    }

    @Test
    void unauthorizedCarriesBearerChallenge() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        errorWithStatus(401, response);

        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
    }

    @Test
    void directRequestOutsideAnErrorDispatchIsRouteNotFound() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        JsonNode body = errorWithStatus(null, response);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(body.get("code").asString()).isEqualTo("ROUTE_NOT_FOUND");
    }

    @Test
    void errorDispatchWithoutStatusIsInternal() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setDispatcherType(DispatcherType.ERROR);
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.error(request, response);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(MAPPER.readTree(response.getContentAsByteArray()).get("code").asString()).isEqualTo("INTERNAL");
    }

    @Test
    void synapseExceptionOfTheDispatchIsWrittenAsItself(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new SynapseException(BookErrors.BOOK_NOT_FOUND, 42));
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.error(request, response);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(MAPPER.readTree(response.getContentAsByteArray()).get("code").asString())
                .isEqualTo("BOOK_NOT_FOUND");
        assertThat(output.getAll().lines().filter(l -> l.contains("ERROR")).count()).isZero();
    }

    @Test
    void serverSideSynapseExceptionOfTheDispatchIsLogged(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new SynapseException(SynapseErrors.INTERNAL));
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.error(request, response);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(output.getAll().lines().filter(l -> l.contains("ERROR")).count()).isEqualTo(1);
    }

    @Test
    void bodyOverTheLimitReadBeforeSpringMvcIs413AndNotAnError() {
        for (Throwable failure : List
                .<Throwable>of(new RequestTooLargeException(10),
                        new IllegalStateException("wrapped", new IOException(new RequestTooLargeException(10))))) {
            MockHttpServletResponse response = new MockHttpServletResponse();

            List<ILoggingEvent> events = logged(() -> controller.error(errorDispatch(failure), response));

            assertThat(response.getStatus()).isEqualTo(413);
            JsonNode body = MAPPER.readTree(response.getContentAsByteArray());
            assertThat(body.get("code").asString()).isEqualTo("PAYLOAD_TOO_LARGE");
            assertThat(body.toString()).doesNotContain("RequestTooLargeException");
            assertThat(events).noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));
        }
    }

    @Test
    void unreadableBodyOfTheDispatchIsMalformedRequestAndNotAnError() {
        // Boot's FormContentFilter fails this way on a form body it can't decode, before Spring MVC.
        Throwable failure = new HttpMessageNotReadableException("Could not decode HTTP form payload",
                new IllegalArgumentException("URLDecoder: Illegal hex characters"),
                new MockHttpInputMessage(new byte[0]));
        MockHttpServletResponse response = new MockHttpServletResponse();

        List<ILoggingEvent> events = logged(() -> controller.error(errorDispatch(failure), response));

        assertThat(response.getStatus()).isEqualTo(400);
        JsonNode body = MAPPER.readTree(response.getContentAsByteArray());
        assertThat(body.get("code").asString()).isEqualTo("MALFORMED_REQUEST");
        assertThat(body.toString()).doesNotContain("decode").doesNotContain("Illegal");
        assertThat(events).singleElement().satisfies(event -> assertThat(event.getLevel()).isEqualTo(Level.DEBUG));
    }

    @Test
    void dispatchExceptionGetsTheResolversCodeStatusAndLogLevel() throws Exception {
        SynapseRestExceptionResolver resolver = new SynapseRestExceptionResolver(new ProblemWriter(
                new RestErrorStatuses(Map.of(), Map.of()), new ErrorMessages(messages), new ProblemTitles(messages),
                new SynapseErrorProperties(false, null), MAPPER, "X-Correlation-Id"));
        List<Exception> failures = List
                .of(new HttpMessageNotReadableException("unreadable", new MockHttpInputMessage(new byte[0])),
                        new HttpMediaTypeNotSupportedException("text/plain"),
                        new MissingServletRequestParameterException("limit", "int"),
                        new ResponseStatusException(HttpStatus.CONFLICT, "secret-text"),
                        new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "secret-text"),
                        new ResponseStatusException(HttpStatus.BAD_GATEWAY, "secret-text"),
                        new IllegalStateException("boom"));
        for (Exception failure : failures) {
            MockHttpServletResponse resolved = new MockHttpServletResponse();
            List<ILoggingEvent> resolverEvents = logged(() -> resolver
                    .resolveException(new MockHttpServletRequest("GET", "/books"), resolved, null, failure));
            MockHttpServletResponse dispatched = new MockHttpServletResponse();
            List<ILoggingEvent> controllerEvents = logged(() -> controller.error(errorDispatch(failure), dispatched));

            assertThat(dispatched.getStatus()).as(failure.toString()).isEqualTo(resolved.getStatus());
            assertThat(MAPPER.readTree(dispatched.getContentAsByteArray()).get("code"))
                    .as(failure.toString())
                    .isEqualTo(MAPPER.readTree(resolved.getContentAsByteArray()).get("code"));
            assertThat(controllerEvents)
                    .as(failure.toString())
                    .extracting(ILoggingEvent::getLevel)
                    .isEqualTo(resolverEvents.stream().map(ILoggingEvent::getLevel).toList());
        }
    }

    @Test
    void otherExceptionOfTheDispatchIsInternalAndLogged(CapturedOutput output) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("boom"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        controller.error(request, response);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(MAPPER.readTree(response.getContentAsByteArray()).toString()).doesNotContain("boom");
        assertThat(output.getAll().lines().filter(l -> l.contains("ERROR")).count()).isEqualTo(1);
    }

    @Test
    void errorDispatchFailureIsLoggedWithTheEdgeCorrelationId() {
        MockHttpServletRequest request = errorDispatch(new IllegalStateException("boom"));
        request.setAttribute(RestRequestAttributes.CORRELATION_ID, "c-err");

        List<ILoggingEvent> events = logged(() -> controller.error(request, new MockHttpServletResponse()));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getMDCPropertyMap()).containsEntry("correlationId", "c-err");
        });
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void serverSideSynapseExceptionOfTheDispatchIsLoggedWithTheEdgeCorrelationId() {
        MockHttpServletRequest request = errorDispatch(new SynapseException(SynapseErrors.INTERNAL));
        request.setAttribute(RestRequestAttributes.CORRELATION_ID, "c-err");

        List<ILoggingEvent> events = logged(() -> controller.error(request, new MockHttpServletResponse()));

        assertThat(events)
                .singleElement()
                .satisfies(event -> assertThat(event.getMDCPropertyMap()).containsEntry("correlationId", "c-err"));
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void errorDispatchFailureWithoutEdgeCorrelationIdLogsWithoutOne() {
        MockHttpServletRequest request = errorDispatch(new IllegalStateException("boom"));

        List<ILoggingEvent> events = logged(() -> controller.error(request, new MockHttpServletResponse()));

        assertThat(events)
                .singleElement()
                .satisfies(event -> assertThat(event.getMDCPropertyMap()).doesNotContainKey("correlationId"));
    }

    @Test
    void openRequestContextIsLeftToTracing() {
        MockHttpServletRequest request = errorDispatch(new IllegalStateException("boom"));
        request.setAttribute(RestRequestAttributes.CORRELATION_ID, "c-err");
        ContextBinder binder = new ContextBinder(Tracer.NOOP, new CorrelationIdPolicy(128), true, 16, 4096);

        List<ILoggingEvent> events;
        try (ContextScope scope = binder.open(InboundContext.empty())) {
            events = logged(() -> controller.error(request, new MockHttpServletResponse()));
        }

        // With a context open, its tracing baggage owns the MDC key; the controller doesn't add its own.
        assertThat(events)
                .singleElement()
                .satisfies(event -> assertThat(event.getMDCPropertyMap()).doesNotContainKey("correlationId"));
    }

    private static MockHttpServletRequest errorDispatch(Throwable failure) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, failure);
        return request;
    }

    /** Runs the action and returns the events, DEBUG and above, logged on the REST logger meanwhile. */
    static List<ILoggingEvent> logged(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger("io.americanexpress.synapse.api.rest");
        Level level = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        // DEBUG, so the tests see the lines of rejected requests too.
        logger.setLevel(Level.DEBUG);
        try {
            action.run();
        } finally {
            logger.setLevel(level);
            logger.detachAppender(appender);
        }
        return appender.list;
    }

    @Test
    void nonNumericStatusIsInternal() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        JsonNode body = errorWithStatus("oops", response);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(body.get("code").asString()).isEqualTo("INTERNAL");
    }
}
