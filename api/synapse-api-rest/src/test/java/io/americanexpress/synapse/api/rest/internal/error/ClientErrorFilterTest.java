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
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.internal.edge.RequestTooLargeException;
import io.americanexpress.synapse.api.rest.internal.edge.RestRequestAttributes;
import io.americanexpress.synapse.core.autoconfigure.SynapseErrorProperties;
import io.americanexpress.synapse.core.error.ErrorMessages;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.core.internal.context.ContextBinder;
import io.americanexpress.synapse.core.internal.context.ContextScope;
import io.americanexpress.synapse.core.internal.context.CorrelationIdPolicy;
import io.americanexpress.synapse.core.internal.context.InboundContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientErrorFilterTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final ClientErrorFilter filter = new ClientErrorFilter(writer(Map.of()));

    private static ProblemWriter writer(Map<String, Integer> statuses) {
        StaticMessageSource messages = new StaticMessageSource();
        return new ProblemWriter(new RestErrorStatuses(statuses, Map.of()), new ErrorMessages(messages),
                new ProblemTitles(messages), new SynapseErrorProperties(null, null), () -> false, MAPPER,
                "X-Correlation-Id");
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/books");
        request.setAttribute(RestRequestAttributes.CORRELATION_ID, "c-edge");
        return request;
    }

    private static FilterChain throwing(Exception failure) {
        return (request, response) -> {
            if (failure instanceof IOException io) {
                throw io;
            }
            if (failure instanceof ServletException servlet) {
                throw servlet;
            }
            throw (RuntimeException) failure;
        };
    }

    private static HttpMessageNotReadableException undecodableForm() {
        // Boot's FormContentFilter fails this way on a form body it can't decode, before Spring MVC.
        return new HttpMessageNotReadableException("Could not decode HTTP form payload",
                new IllegalArgumentException("URLDecoder: Illegal hex characters"),
                new MockHttpInputMessage(new byte[0]));
    }

    @Test
    void runsInsideTheOtherEdgeFiltersAndOutsideBootsFormContentFilter() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 5);
        // Boot's FormContentFilter is ordered at -9900.
        assertThat(filter.getOrder()).isLessThan(-9900);
    }

    @Test
    void requestThatDoesNotFailIsLeftAlone() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(), response, (request, out) -> out.getWriter().write("ok"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("ok");
    }

    @Test
    void clientErrorEscapingTheChainIsWrittenAsAProblemAndLoggedAtDebug() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        List<ILoggingEvent> events = logged(() -> filter.doFilter(request(), response, throwing(undecodableForm())));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.isCommitted()).isTrue();
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("c-edge");
        JsonNode body = MAPPER.readTree(response.getContentAsByteArray());
        assertThat(body.get("code").asString()).isEqualTo("MALFORMED_REQUEST");
        assertThat(body.get("correlationId").asString()).isEqualTo("c-edge");
        assertThat(body.toString())
                .doesNotContain("decode")
                .doesNotContain("Illegal")
                .doesNotContain("Exception");
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage())
                    .startsWith("Answered PUT /books with 400 MALFORMED_REQUEST: ")
                    .contains("Could not decode HTTP form payload");
            assertThat(event.getMDCPropertyMap()).containsEntry("correlationId", "c-edge");
        });
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void bodyOverTheLimitReadByAnotherFilterIs413() throws Exception {
        // As Boot's FormContentFilter does: it reads the body before the DispatcherServlet and lets the failure out,
        // as it is or wrapped.
        for (Exception failure : List
                .<Exception>of(new RequestTooLargeException(10), new IOException(new RequestTooLargeException(10)),
                        new ServletException("wrapped", new IOException(new RequestTooLargeException(10))))) {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request(), response, throwing(failure));

            assertThat(response.getStatus()).as(failure.toString()).isEqualTo(413);
            JsonNode body = MAPPER.readTree(response.getContentAsByteArray());
            assertThat(body.get("code").asString()).isEqualTo("PAYLOAD_TOO_LARGE");
            assertThat(body.toString()).doesNotContain("RequestTooLargeException").doesNotContain("bytes");
        }
    }

    @Test
    void synapseClientErrorKeepsItsCodeAndViolations() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        SynapseException invalid = SynapseException
                .ofViolations(SynapseErrors.VALIDATION_FAILED, List.of(new Violation("isbn", "must not be null")));

        filter.doFilter(request(), response, throwing(invalid));

        assertThat(response.getStatus()).isEqualTo(400);
        JsonNode body = MAPPER.readTree(response.getContentAsByteArray());
        assertThat(body.get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.get("violations").get(0).get("field").asString()).isEqualTo("isbn");
        assertThat(body.get("violations").get(0).get("message").asString()).isEqualTo("must not be null");
    }

    @Test
    void servletExceptionIsClassifiedByItsRootCauseAsTheContainerDoes() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(), response, throwing(new ServletException("wrapped", undecodableForm())));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(MAPPER.readTree(response.getContentAsByteArray()).get("code").asString())
                .isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    void serverErrorsAreRethrownUnchangedWithoutALog() throws Exception {
        List<Exception> failures = List
                .of(new IllegalStateException("boom"), new IOException("connection reset"),
                        new ServletException("no cause"), new ServletException("wrapped", new IllegalStateException()),
                        new SynapseException(SynapseErrors.INTERNAL), new SynapseException(SynapseErrors.UNAVAILABLE),
                        new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "secret-text"));
        for (Exception failure : failures) {
            MockHttpServletResponse response = new MockHttpServletResponse();

            List<ILoggingEvent> events = logged(() -> assertThatThrownBy(
                    () -> filter.doFilter(request(), response, throwing(failure))).isSameAs(failure));

            // The container's error dispatch and the error controller handle and log it, as before.
            assertThat(events).as(failure.toString()).isEmpty();
            assertThat(response.getStatus()).as(failure.toString()).isEqualTo(200);
            assertThat(response.isCommitted()).as(failure.toString()).isFalse();
            assertThat(response.getContentLength()).as(failure.toString()).isZero();
        }
    }

    @Test
    void clientErrorWhoseStatusIsSetTo5xxIsRethrown() {
        ClientErrorFilter overridden = new ClientErrorFilter(writer(Map.of("MALFORMED_REQUEST", 502)));
        HttpMessageNotReadableException failure = undecodableForm();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> overridden.doFilter(request(), response, throwing(failure))).isSameAs(failure);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void clientErrorOnACommittedResponseIsRethrownUnchanged() throws Exception {
        HttpMessageNotReadableException failure = undecodableForm();
        MockHttpServletResponse response = new MockHttpServletResponse();

        List<ILoggingEvent> events = logged(() -> assertThatThrownBy(() -> filter
                .doFilter(request(), response,
                        (request, out) -> {
                            out.getWriter().write("partial");
                            out.flushBuffer();
                            throw failure;
                        }))
                .isSameAs(failure));

        assertThat(events).isEmpty();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("partial");
    }

    @Test
    void clientErrorAfterAsyncStartedIsRethrownUnchanged() {
        HttpMessageNotReadableException failure = undecodableForm();
        MockHttpServletRequest request = request();
        request.setAsyncSupported(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response, (in, out) -> {
            in.startAsync();
            throw failure;
        })).isSameAs(failure);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentLength()).isZero();
    }

    @Test
    void errorsAreNotCaught() {
        StackOverflowError error = new StackOverflowError();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request(), response, (request, out) -> {
            throw error;
        })).isSameAs(error);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void problemThatCantBeWrittenLeavesTheOriginalToTheContainer() throws Exception {
        HttpMessageNotReadableException failure = undecodableForm();
        ExclusiveBodyResponse response = new ExclusiveBodyResponse();

        // The chain took the writer without committing; the problem writer needs the output stream.
        List<ILoggingEvent> events = logged(() -> assertThatThrownBy(() -> filter
                .doFilter(request(), response,
                        (request, out) -> {
                            out.getWriter();
                            throw failure;
                        }))
                .isSameAs(failure));

        assertThat(failure.getSuppressed())
                .singleElement()
                .satisfies(suppressed -> assertThat(suppressed)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("getWriter() has already been called for this response"));
        assertThat(events).isEmpty();
        assertThat(response.isCommitted()).isFalse();
    }

    /** A response that, as Tomcat's does, refuses the output stream once the writer is in use. */
    private static final class ExclusiveBodyResponse extends MockHttpServletResponse {

        private boolean writerUsed;

        @Override
        public PrintWriter getWriter() throws UnsupportedEncodingException {
            writerUsed = true;
            return super.getWriter();
        }

        @Override
        public ServletOutputStream getOutputStream() {
            if (writerUsed) {
                throw new IllegalStateException("getWriter() has already been called for this response");
            }
            return super.getOutputStream();
        }
    }

    @Test
    void openRequestContextIsLeftToTracing() throws Exception {
        ContextBinder binder = new ContextBinder(Tracer.NOOP, new CorrelationIdPolicy(128), true, 16, 4096);
        MockHttpServletResponse response = new MockHttpServletResponse();

        List<ILoggingEvent> events;
        try (ContextScope scope = binder.open(InboundContext.empty())) {
            events = logged(() -> filter.doFilter(request(), response, throwing(undecodableForm())));
        }

        // With a context open, its tracing baggage owns the MDC key; the filter doesn't add its own.
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(events)
                .singleElement()
                .satisfies(event -> assertThat(event.getMDCPropertyMap()).doesNotContainKey("correlationId"));
    }

    /** An action that may throw what a filter throws. */
    @FunctionalInterface
    private interface FilterAction {

        void run() throws Exception;
    }

    /** Runs the action and returns the events, DEBUG and above, logged on the REST logger meanwhile. */
    private static List<ILoggingEvent> logged(FilterAction action) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("io.americanexpress.synapse.api.rest");
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
        return new ArrayList<>(appender.list);
    }
}
