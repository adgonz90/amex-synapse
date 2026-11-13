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
import java.util.Objects;
import java.util.Set;
import jakarta.servlet.DispatcherType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.TypeMismatchException;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.DataBinder;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.accept.InvalidApiVersionException;
import org.springframework.web.accept.MissingApiVersionException;
import org.springframework.web.accept.NotAcceptableApiVersionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.americanexpress.synapse.api.rest.internal.edge.RequestTooLargeException;
import io.americanexpress.synapse.api.rest.internal.edge.RestRequestAttributes;
import io.americanexpress.synapse.core.autoconfigure.SynapseErrorProperties;
import io.americanexpress.synapse.core.error.ErrorMessages;
import io.americanexpress.synapse.core.error.SynapseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class SynapseRestExceptionResolverTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final StaticMessageSource messages = new StaticMessageSource();

    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/books");

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private final SynapseRestExceptionResolver resolver = new SynapseRestExceptionResolver(
            new ProblemWriter(new RestErrorStatuses(Map.of(), Map.of()), new ErrorMessages(messages),
                    new ProblemTitles(messages), new SynapseErrorProperties(false, null), MAPPER,
                    "X-Correlation-Id"));

    private JsonNode resolve(Exception ex) {
        ModelAndView mav = resolver.resolveException(request, response, null, ex);
        assertThat(mav).isNotNull().extracting(ModelAndView::isEmpty).isEqualTo(true);
        return MAPPER.readTree(response.getContentAsByteArray());
    }

    /** The body without its correlation id, which is any caller's or random text and may contain anything. */
    private static String withoutCorrelationId(JsonNode body) {
        ObjectNode copy = (ObjectNode) body.deepCopy();
        assertThat(copy.remove("correlationId")).isNotNull();
        return copy.toString();
    }

    @Test
    void runsBeforeEveryOtherResolver() {
        assertThat(resolver.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    void synapseExceptionMapsToItself() {
        JsonNode body = resolve(new SynapseException(BookErrors.BOOK_NOT_FOUND, 42));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(body.get("code").asString()).isEqualTo("BOOK_NOT_FOUND");
    }

    @Test
    void mapsSpringProtocolExceptions() {
        record Case(Exception ex, String code, int status) {
        }
        List<Case> cases = List
                .of(new Case(
                        new HttpMessageNotReadableException("bad json",
                                new ServletServerHttpRequest(new MockHttpServletRequest())),
                        "MALFORMED_REQUEST", 400),
                        new Case(new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of()),
                                "UNSUPPORTED_MEDIA_TYPE", 415),
                        new Case(new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)),
                                "NOT_ACCEPTABLE", 406),
                        new Case(new NoResourceFoundException(HttpMethod.GET, "/nope", "nope"), "ROUTE_NOT_FOUND", 404),
                        new Case(new NoHandlerFoundException("GET", "/nope", new HttpHeaders()), "ROUTE_NOT_FOUND",
                                404));
        for (Case c : cases) {
            MockHttpServletResponse each = new MockHttpServletResponse();
            ModelAndView mav = resolver.resolveException(request, each, null, c.ex());
            assertThat(mav).as(c.code()).isNotNull();
            assertThat(each.getStatus()).as(c.code()).isEqualTo(c.status());
            assertThat(MAPPER.readTree(each.getContentAsByteArray()).get("code").asString()).isEqualTo(c.code());
            assertThat(each.getContentType()).startsWith("application/problem+json");
        }
    }

    @Test
    void requestTooLargeAnywhereInTheCauseChainIs413() throws Exception {
        // A correlation id that contains the limit: only the problem's other members must not carry it.
        request.setAttribute(RestRequestAttributes.CORRELATION_ID, "c-1024");
        RequestTooLargeException tooLarge = new RequestTooLargeException(1024);
        ServletServerHttpRequest input = new ServletServerHttpRequest(new MockHttpServletRequest());
        List<Exception> cases = List
                .of(new HttpMessageNotReadableException("I/O error while reading input message", tooLarge, input),
                        new HttpMessageNotReadableException("JSON parse error",
                                new IllegalStateException("wrapped", tooLarge), input),
                        tooLarge, new IllegalStateException("wrapped", new IOException("outer", tooLarge)));
        for (Exception ex : cases) {
            MockHttpServletResponse each = new MockHttpServletResponse();
            ModelAndView mav = resolver.resolveException(request, each, null, ex);
            assertThat(mav).as(ex.toString()).isNotNull();
            assertThat(each.getStatus()).as(ex.toString()).isEqualTo(413);
            JsonNode body = MAPPER.readTree(each.getContentAsByteArray());
            assertThat(body.get("code").asString()).isEqualTo("PAYLOAD_TOO_LARGE");
            assertThat(body.get("correlationId").asString()).isEqualTo("c-1024");
            assertThat(withoutCorrelationId(body))
                    .contains("PAYLOAD_TOO_LARGE")
                    .doesNotContain("RequestTooLargeException")
                    .doesNotContain("1024");
        }
    }

    @Test
    void unreadableBodyWithoutTheLimitInItsCausesStaysMalformed() {
        JsonNode body = resolve(new HttpMessageNotReadableException("I/O error",
                new IOException("connection reset"), new ServletServerHttpRequest(new MockHttpServletRequest())));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(body.get("code").asString()).isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    void methodNotAllowedSetsAllow() {
        JsonNode body = resolve(new HttpRequestMethodNotSupportedException("DELETE", Set.of("GET", "PUT")));

        assertThat(response.getStatus()).isEqualTo(405);
        assertThat(body.get("code").asString()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(response.getHeader("Allow")).isEqualTo("GET, PUT");
    }

    @Test
    void methodNotAllowedWithoutSupportedMethodsHasNoAllow() {
        resolve(new HttpRequestMethodNotSupportedException("DELETE"));

        assertThat(response.getStatus()).isEqualTo(405);
        assertThat(response.getHeader("Allow")).isNull();
    }

    @Test
    void unexpectedExceptionIsInternalAndLoggedOnce(CapturedOutput output) {
        JsonNode body = resolve(new IllegalStateException("db password=x"));
        String text = body.toString();

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(body.get("code").asString()).isEqualTo("INTERNAL");
        assertThat(text).doesNotContain("IllegalStateException").doesNotContain("password");
        assertThat(output.getAll().lines().filter(l -> l.contains("ERROR")).count()).isEqualTo(1);
        assertThat(output).contains("db password=x").contains("IllegalStateException");
    }

    @Test
    void unexpectedExceptionOutsideTheContextIsLoggedWithTheEdgeCorrelationId() {
        // An ASYNC dispatch runs after the REQUEST-only context filter closed the context.
        request.setDispatcherType(DispatcherType.ASYNC);
        request.setAttribute(RestRequestAttributes.CORRELATION_ID, "c-async");

        List<ILoggingEvent> events = SynapseErrorControllerTest
                .logged(() -> resolve(new IllegalStateException("boom")));

        assertThat(events)
                .singleElement()
                .satisfies(event -> assertThat(event.getMDCPropertyMap()).containsEntry("correlationId", "c-async"));
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void protocolFailureOutsideTheContextIsLoggedWithTheEdgeCorrelationId() {
        request.setDispatcherType(DispatcherType.ASYNC);
        request.setAttribute(RestRequestAttributes.CORRELATION_ID, "c-async");

        List<ILoggingEvent> events = SynapseErrorControllerTest
                .logged(() -> resolve(new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "secret-text")));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getMDCPropertyMap()).containsEntry("correlationId", "c-async");
        });
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void committedResponseIsLeftAlone() {
        response.setCommitted(true);

        assertThat(resolver.resolveException(request, response, null, new IllegalStateException("x"))).isNull();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    private static MethodParameter parameter(String name) {
        MethodParameter parameter = mock(MethodParameter.class);
        when(parameter.getParameterName()).thenReturn(name);
        return parameter;
    }

    private static MethodParameter realParameter() throws NoSuchMethodException {
        return new MethodParameter(SynapseRestExceptionResolverTest.class.getDeclaredMethod("target", String.class), 0);
    }

    @SuppressWarnings("unused")
    void target(String title) {
    }

    private static long errorLines(CapturedOutput output) {
        return output.getAll().lines().filter(l -> l.contains("ERROR")).count();
    }

    private void assertClientError(Exception ex, int status, String code, CapturedOutput output) {
        JsonNode body = resolve(ex);

        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.toString()).doesNotContain("Exception").doesNotContain("secret-text");
        assertThat(errorLines(output)).isZero();
    }

    private static void assertViolation(JsonNode body, int index, String field, String message) {
        assertThat(body.get("violations").get(index).get("field").asString()).isEqualTo(field);
        assertThat(body.get("violations").get(index).get("message").asString()).isEqualTo(message);
    }

    @Test
    void methodArgumentNotValidIsValidationFailedWithErrorsInOrder(CapturedOutput output) throws Exception {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "book");
        result.addError(new FieldError("book", "title", "must not be blank"));
        result.addError(new FieldError("book", "isbn", "must match pattern"));
        result.addError(new ObjectError("book", "book is inconsistent"));
        JsonNode body = resolve(new MethodArgumentNotValidException(realParameter(), result));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(body.get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.get("violations")).hasSize(3);
        assertViolation(body, 0, "title", "must not be blank");
        assertViolation(body, 1, "isbn", "must match pattern");
        assertViolation(body, 2, "book", "book is inconsistent");
        assertThat(errorLines(output)).isZero();
    }

    @Test
    void handlerMethodValidationIsValidationFailedWithParameterNames(CapturedOutput output) {
        ParameterValidationResult limit = mock(ParameterValidationResult.class);
        MethodParameter limitParameter = parameter("limit");
        when(limit.getMethodParameter()).thenReturn(limitParameter);
        when(limit.getResolvableErrors())
                .thenReturn(List.of(new DefaultMessageSourceResolvable(new String[]{"Max"}, "must be at most 10")));
        MethodValidationResult validation = mock(MethodValidationResult.class);
        when(validation.getParameterValidationResults()).thenReturn(List.of(limit));
        JsonNode body = resolve(new HandlerMethodValidationException(validation));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(body.get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.get("violations")).hasSize(1);
        assertViolation(body, 0, "limit", "must be at most 10");
        assertThat(errorLines(output)).isZero();
    }

    @Test
    void typeMismatchIsValidationFailedNotAValidValue(CapturedOutput output) {
        JsonNode body = resolve(new MethodArgumentTypeMismatchException("abc secret-text", Integer.class, "limit",
                parameter("limit"), new NumberFormatException("secret-text")));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(body.get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertViolation(body, 0, "limit", "is not a valid value");
        assertThat(body.toString()).doesNotContain("secret-text").doesNotContain("Exception");
        assertThat(errorLines(output)).isZero();

        MockHttpServletResponse other = new MockHttpServletResponse();
        TypeMismatchException plain = new TypeMismatchException("x", Integer.class);
        plain.initPropertyName("page");
        resolver.resolveException(request, other, null, plain);
        assertViolation(MAPPER.readTree(other.getContentAsByteArray()), 0, "page", "is not a valid value");
    }

    @Test
    void typeMismatchWithoutNameIsMalformed(CapturedOutput output) {
        assertClientError(new TypeMismatchException("x", Integer.class), 400, "MALFORMED_REQUEST", output);
    }

    @Test
    void missingBindingValuesAreValidationFailedMustNotBeNull(CapturedOutput output) throws Exception {
        record Case(Exception ex, String name) {
        }
        List<Case> cases = List
                .of(new Case(new MissingServletRequestParameterException("limit", "int"), "limit"),
                        new Case(new MissingRequestHeaderException("X-Tenant", realParameter()), "X-Tenant"),
                        new Case(new MissingRequestCookieException("session", realParameter()), "session"),
                        new Case(new MissingPathVariableException("id", realParameter(), true), "id"));
        for (Case c : cases) {
            MockHttpServletResponse each = new MockHttpServletResponse();
            resolver.resolveException(request, each, null, c.ex());
            JsonNode body = MAPPER.readTree(each.getContentAsByteArray());
            assertThat(each.getStatus()).as(c.name()).isEqualTo(400);
            assertThat(body.get("code").asString()).as(c.name()).isEqualTo("VALIDATION_FAILED");
            assertViolation(body, 0, c.name(), "must not be null");
        }
        assertThat(errorLines(output)).isZero();
    }

    @Test
    void missingPathVariableInTheMappingIsInternal(CapturedOutput output) throws Exception {
        JsonNode body = resolve(new MissingPathVariableException("id", realParameter()));

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(body.get("code").asString()).isEqualTo("INTERNAL");
        assertThat(errorLines(output)).isEqualTo(1);
    }

    @Test
    void otherBindingExceptionIsMalformed(CapturedOutput output) {
        assertClientError(new ServletRequestBindingException("secret-text"), 400, "MALFORMED_REQUEST", output);
    }

    @Test
    void conflictResponseIsConflict(CapturedOutput output) {
        assertClientError(new ResponseStatusException(HttpStatus.CONFLICT, "secret-text"), 409, "CONFLICT",
                output);
    }

    @Test
    void notFoundResponseIsNotFound(CapturedOutput output) {
        assertClientError(new ResponseStatusException(HttpStatus.NOT_FOUND, "secret-text"), 404, "NOT_FOUND", output);
    }

    @Test
    void maxUploadSizeIsPayloadTooLarge(CapturedOutput output) {
        assertClientError(new MaxUploadSizeExceededException(1024), 413, "PAYLOAD_TOO_LARGE", output);
    }

    @Test
    void gatewayTimeoutIsDeadlineExceededLoggedAsWarning(CapturedOutput output) {
        JsonNode body = resolve(new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "secret-text"));

        assertThat(response.getStatus()).isEqualTo(504);
        assertThat(body.get("code").asString()).isEqualTo("DEADLINE_EXCEEDED");
        assertThat(errorLines(output)).isZero();
        assertThat(output.getAll()).contains("WARN");
    }

    public static class Target {

        private int count;

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }
    }

    private static BindingResult bindingFailure() {
        DataBinder binder = new DataBinder(new Target(), "target");
        binder.bind(new MutablePropertyValues(Map.of("count", "abc")));
        return binder.getBindingResult();
    }

    @Test
    void bindingFailureMessageDoesNotLeakConversionText() throws Exception {
        BindingResult result = bindingFailure();
        FieldError countError = Objects.requireNonNull(result.getFieldError("count"));
        assertThat(countError.isBindingFailure()).isTrue();
        JsonNode body = resolve(new MethodArgumentNotValidException(realParameter(), result));

        assertViolation(body, 0, "count", "is not a valid value");
        assertThat(body.toString())
                .doesNotContain("java.lang")
                .doesNotContain("Failed to convert")
                .doesNotContain("property value");
    }

    @Test
    void parameterErrorsBranchMapsFieldsAndHidesBindingText() throws Exception {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Target(), "target");
        result.addError(new FieldError("target", "name", "must not be blank"));
        BindingResult binding = bindingFailure();
        result.addError(Objects.requireNonNull(binding.getFieldError("count")));
        ParameterErrors errors = new ParameterErrors(realParameter(), new Target(), result, null, null, null);
        MethodValidationResult validation = mock(MethodValidationResult.class);
        when(validation.getParameterValidationResults()).thenReturn(List.of(errors));
        JsonNode body = resolve(new HandlerMethodValidationException(validation));

        assertThat(body.get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertViolation(body, 0, "name", "must not be blank");
        assertViolation(body, 1, "count", "is not a valid value");
        assertThat(body.toString()).doesNotContain("java.lang").doesNotContain("Failed to convert");
    }

    @SuppressWarnings("unused")
    void search(@RequestParam("max_results") int maxResults) {
    }

    @Test
    void handlerMethodValidationUsesTheWireName() throws Exception {
        ParameterValidationResult result = mock(ParameterValidationResult.class);
        MethodParameter wire = new MethodParameter(
                SynapseRestExceptionResolverTest.class.getDeclaredMethod("search", int.class), 0);
        when(result.getMethodParameter()).thenReturn(wire);
        when(result.getResolvableErrors())
                .thenReturn(List.of(new DefaultMessageSourceResolvable(new String[]{"Max"}, "must be at most 10")));
        MethodValidationResult validation = mock(MethodValidationResult.class);
        when(validation.getParameterValidationResults()).thenReturn(List.of(result));
        JsonNode body = resolve(new HandlerMethodValidationException(validation));

        assertViolation(body, 0, "max_results", "must be at most 10");
    }

    @Test
    void otherServiceUnavailableResponseIsUnavailable(CapturedOutput output) {
        assertClientError(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "secret-text"), 503,
                "UNAVAILABLE", output);
    }

    @Test
    void apiVersionExceptionsHaveTheirCodes(CapturedOutput output) {
        record Case(Exception ex, String code) {
        }
        List<Case> cases = List
                .of(new Case(new MissingApiVersionException(), "MISSING_API_VERSION"),
                        new Case(new InvalidApiVersionException("9"), "UNSUPPORTED_API_VERSION"),
                        new Case(new NotAcceptableApiVersionException("9"), "UNSUPPORTED_API_VERSION"));
        for (Case c : cases) {
            MockHttpServletResponse each = new MockHttpServletResponse();
            ModelAndView mav = resolver.resolveException(request, each, null, c.ex());
            assertThat(mav).as(c.code()).isNotNull();
            assertThat(each.getStatus()).as(c.code()).isEqualTo(400);
            JsonNode body = MAPPER.readTree(each.getContentAsByteArray());
            assertThat(body.get("code").asString()).as(c.ex().toString()).isEqualTo(c.code());
            assertThat(body.toString()).doesNotContain("Exception").doesNotContain("'9'");
        }
        // Client errors: logged at DEBUG, never as an error.
        assertThat(errorLines(output)).isZero();
    }

    @Test
    void otherServerErrorResponseIsInternalAndLogged(CapturedOutput output) {
        JsonNode body = resolve(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "secret-text"));

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(body.get("code").asString()).isEqualTo("INTERNAL");
        assertThat(body.toString()).doesNotContain("secret-text").doesNotContain("Exception");
        assertThat(errorLines(output)).isEqualTo(1);
    }
}
