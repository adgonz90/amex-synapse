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
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Size;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.jspecify.annotations.Nullable;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.DataBinder;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponseException;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.americanexpress.synapse.api.rest.internal.edge.RequestTooLargeException;
import io.americanexpress.synapse.api.rest.internal.edge.RestRequestAttributes;
import io.americanexpress.synapse.api.rest.internal.route.MethodNotAllowedHandler;
import io.americanexpress.synapse.core.autoconfigure.SynapseErrorProperties;
import io.americanexpress.synapse.core.error.ErrorMessages;
import io.americanexpress.synapse.core.error.SynapseErrors;
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

    private final ProblemWriter writer = new ProblemWriter(new RestErrorStatuses(Map.of(), Map.of()),
            new ErrorMessages(messages), new ProblemTitles(messages), new SynapseErrorProperties(false, null), MAPPER,
            "X-Correlation-Id");

    private final SynapseRestExceptionResolver resolver = new SynapseRestExceptionResolver(writer);

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
    void springMediaTypeErrorsKeepTheirHeaders() {
        List<MediaType> supported = List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_XML);

        resolve(new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, supported, HttpMethod.POST));
        assertThat(response.getStatus()).isEqualTo(415);
        assertThat(response.getHeader(HttpHeaders.ACCEPT)).isEqualTo("application/json, application/xml");
        assertThat(response.getHeader("Accept-Patch")).isNull();

        MockHttpServletResponse patch = new MockHttpServletResponse();
        resolver
                .resolveException(request, patch, null,
                        new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, supported, HttpMethod.PATCH));
        assertThat(patch.getStatus()).isEqualTo(415);
        assertThat(patch.getHeader(HttpHeaders.ACCEPT)).isEqualTo("application/json, application/xml");
        assertThat(patch.getHeader("Accept-Patch")).isEqualTo("application/json, application/xml");

        MockHttpServletResponse notAcceptable = new MockHttpServletResponse();
        resolver
                .resolveException(request, notAcceptable, null,
                        new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON)));
        assertThat(notAcceptable.getStatus()).isEqualTo(406);
        assertThat(notAcceptable.getHeader(HttpHeaders.ACCEPT)).isEqualTo("application/json");
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
    void committedResponseIsNotResolved() {
        response.setCommitted(true);
        MockHttpServletRequest onASynapsePath = new MockHttpServletRequest("GET", "/books/7");

        assertThat(resolver.resolveException(request, response, null, new IllegalStateException("x"))).isNull();
        assertThat(pathsResolver().resolveException(onASynapsePath, response, null, new IllegalStateException("x")))
                .isNull();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    /**
     * Spring Security's two exceptions, and their subclasses, propagate to its {@code ExceptionTranslationFilter}:
     * neither instance answers them. Only the exception's own class counts, not its causes.
     */
    @Test
    void securityExceptionsAreNotResolved() {
        MockHttpServletRequest onASynapsePath = new MockHttpServletRequest("GET", "/books/7");
        List<Exception> security = List
                .of(new AccessDeniedException("denied"), new AuthorizationDeniedException("denied"),
                        new BadCredentialsException("bad"), new InsufficientAuthenticationException("none"));
        for (Exception ex : security) {
            assertThat(resolver.resolveException(request, response, null, ex)).as(ex.toString()).isNull();
            assertThat(pathsResolver().resolveException(onASynapsePath, response, null, ex)).as(ex.toString()).isNull();
            assertThat(SynapseRestExceptionResolver.isSecurityException(ex)).isTrue();
        }
        assertThat(response.getContentAsByteArray()).isEmpty();

    }

    @Test
    void wrappedSecurityExceptionsAreNotResolved() {
        // As Spring Security's ThrowableAnalyzer finds them: anywhere in the cause chain.
        List<Exception> wrapped = List
                .of(new RuntimeException(new AccessDeniedException("denied")),
                        new IllegalStateException("outer",
                                new UncheckedIOException(new IOException(new BadCredentialsException("bad")))),
                        new ServletException("wrapped", new AuthorizationDeniedException("denied")));
        for (Exception ex : wrapped) {
            assertThat(SynapseRestExceptionResolver.isSecurityException(ex)).as(ex.toString()).isTrue();
            assertThat(resolver.resolveException(request, response, null, ex)).as(ex.toString()).isNull();
        }
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    @Test
    void securityExceptionInsideASynapseExceptionIsResolved() {
        // The pipeline answers method security inside an operation as INTERNAL on every protocol (BUILD 11): the walk
        // stops at Synapse's own answer, so REST doesn't differ from gRPC.
        SynapseException internal = new SynapseException(SynapseErrors.INTERNAL, new AccessDeniedException("denied"));

        assertThat(SynapseRestExceptionResolver.isSecurityException(internal)).isFalse();
        assertThat(SynapseRestExceptionResolver.isSecurityException(new IllegalStateException(internal))).isFalse();
        assertThat(resolve(internal).get("code").asString()).isEqualTo("INTERNAL");
    }

    @Test
    void causeCycleWithoutASecurityExceptionIsResolved() {
        // Guard: passes before the walk existed (no walk); without the cycle check it would only stop at the bound.
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second", first);
        first.initCause(second);

        assertThat(SynapseRestExceptionResolver.isSecurityException(first)).isFalse();
        assertThat(resolve(first).get("code").asString()).isEqualTo("INTERNAL");
    }

    @Test
    void securityExceptionPastTheBoundIsResolved() {
        // The walk is bounded, as RequestTooLargeException's: a chain deeper than 32 causes is not searched further.
        Exception withinTheBound = new AccessDeniedException("denied");
        for (int depth = 0; depth < 31; depth++) {
            withinTheBound = new RuntimeException(withinTheBound);
        }
        // The 32nd exception of the chain is the last one looked at.
        Exception pastTheBound = new RuntimeException(withinTheBound);

        assertThat(SynapseRestExceptionResolver.isSecurityException(withinTheBound)).isTrue();
        assertThat(SynapseRestExceptionResolver.isSecurityException(pastTheBound)).isFalse();
    }

    @Test
    void pathsInstanceIgnoresAControllerHandler() throws NoSuchMethodException {
        HandlerMethod controller = new HandlerMethod(new Object(), Object.class.getMethod("toString"));
        MockHttpServletRequest onASynapsePath = new MockHttpServletRequest("POST", "/books/7");

        assertThat(pathsResolver()
                .resolveException(onASynapsePath, response, controller, new IllegalStateException("x"))).isNull();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    /**
     * On a Synapse path, the paths instance answers an exception raised before a handler was found, or by a Synapse
     * handler: the route's own, or the 405 fallback's.
     */
    @Test
    void pathsInstanceAnswersOnASynapsePath() {
        RouterFunction<ServerResponse> routes = SynapsePathsTest.booksRoutes();
        HandlerFunction<?> operation = routes
                .route(ServerRequest.create(new MockHttpServletRequest("GET", "/books/7"), List.of()))
                .orElseThrow();
        List<@Nullable Object> handlers = Arrays.asList(null, operation, new MethodNotAllowedHandler(List.of()));
        for (Object handler : handlers) {
            MockHttpServletResponse each = new MockHttpServletResponse();
            ModelAndView mav = SynapseRestExceptionResolver
                    .forSynapsePaths(writer, SynapsePaths.of(routes))
                    .resolveException(new MockHttpServletRequest("DELETE", "/books/7"), each, handler,
                            new HttpMediaTypeNotSupportedException("text/plain"));
            assertThat(mav).as(String.valueOf(handler)).isNotNull();
            assertThat(each.getStatus()).isEqualTo(415);
            assertThat(MAPPER.readTree(each.getContentAsByteArray()).get("code").asString())
                    .isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        }
    }

    @Test
    void pathsInstanceIgnoresOtherPaths() {
        assertThat(pathsResolver()
                .resolveException(new MockHttpServletRequest("GET", "/shelves/7"), response, null,
                        new IllegalStateException("x")))
                .isNull();
        assertThat(SynapseRestExceptionResolver
                .forSynapsePaths(writer, SynapsePaths.none())
                .resolveException(new MockHttpServletRequest("GET", "/books/7"), response, null,
                        new IllegalStateException("x")))
                .isNull();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    /**
     * A 404 for a path no handler serves, under a Synapse route's literal path prefix, is Synapse's
     * {@code ROUTE_NOT_FOUND}: the resource handler's {@code NoResourceFoundException}, or {@code NoHandlerFoundException}
     * when no handler was found at all.
     */
    @Test
    void pathsInstanceAnswersAnUnknownPathUnderASynapsePrefix() {
        assertRouteNotFound(new ResourceHttpRequestHandler(),
                new NoResourceFoundException(HttpMethod.GET, "/books/7/reviews", "books/7/reviews"));
        assertRouteNotFound(null, new NoHandlerFoundException("GET", "/books/7/reviews", new HttpHeaders()));
    }

    private void assertRouteNotFound(@Nullable Object handler, Exception notFound) {
        MockHttpServletResponse answered = new MockHttpServletResponse();
        ModelAndView mav = pathsResolver()
                .resolveException(new MockHttpServletRequest("GET", "/books/7/reviews"), answered, handler, notFound);

        assertThat(mav).isNotNull();
        assertThat(answered.getStatus()).isEqualTo(404);
        assertThat(MAPPER.readTree(answered.getContentAsByteArray()).get("code").asString())
                .isEqualTo("ROUTE_NOT_FOUND");
    }

    /** Elsewhere, and for another exception or handler under a Synapse prefix, the normal order runs. */
    @Test
    void pathsInstanceLeavesOtherNotFoundsToTheNormalOrder() throws NoSuchMethodException {
        HandlerMethod controller = new HandlerMethod(new Object(), Object.class.getMethod("toString"));

        assertThat(pathsResolver()
                .resolveException(new MockHttpServletRequest("GET", "/shelves/7"), response,
                        new ResourceHttpRequestHandler(),
                        new NoResourceFoundException(HttpMethod.GET, "/shelves/7", "shelves/7")))
                .isNull();
        assertThat(pathsResolver()
                .resolveException(new MockHttpServletRequest("GET", "/books/7/reviews"), response,
                        new ResourceHttpRequestHandler(), new IllegalStateException("x")))
                .isNull();
        assertThat(pathsResolver()
                .resolveException(new MockHttpServletRequest("GET", "/books/7/reviews"), response, controller,
                        new NoResourceFoundException(HttpMethod.GET, "/books/7/reviews", "books/7/reviews")))
                .isNull();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }

    /** The Synapse-paths instance for the route {@code GET /books/{id}}. */
    private SynapseRestExceptionResolver pathsResolver() {
        return SynapseRestExceptionResolver.forSynapsePaths(writer, SynapsePaths.of(SynapsePathsTest.booksRoutes()));
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

    record Label(String shortName) {
    }

    record Edition(Integer publishedYear, Map<String, Label> labelsByLocale) {
    }

    @SuppressWarnings("unused")
    void create(@RequestBody Edition edition) {
    }

    record Shelf(Map<String, @Size(max = 1) List<Label>> labelsByAisle) {
    }

    @SuppressWarnings("unused")
    void stock(@RequestBody Shelf shelf) {
    }

    @Test
    void constraintViolationIsNamedFromItsPathSoNoPartOfAKeyIsEchoed() throws Exception {
        SynapseRestExceptionResolver snakeCase = new SynapseRestExceptionResolver(new ProblemWriter(
                new RestErrorStatuses(Map.of(), Map.of()), new ErrorMessages(messages), new ProblemTitles(messages),
                new SynapseErrorProperties(false, null),
                JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build(),
                "X-Correlation-Id"));
        // A crafted key that reads like an item index in Spring's field: labelsByAisle[k][7].
        Shelf shelf = new Shelf(Map.of("k][7", List.of(new Label("a"), new Label("b"))));
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(shelf, "shelf");
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            new SpringValidatorAdapter(factory.getValidator()).validate(shelf, result);
        }
        assertThat(Objects.requireNonNull(result.getFieldError()).getField()).isEqualTo("labelsByAisle[k][7]");
        MethodParameter body = new MethodParameter(
                SynapseRestExceptionResolverTest.class.getDeclaredMethod("stock", Shelf.class), 0);

        snakeCase.resolveException(request, response, null, new MethodArgumentNotValidException(body, result));
        JsonNode problem = MAPPER.readTree(response.getContentAsByteArray());

        assertThat(problem.get("violations")).hasSize(1);
        assertThat(problem.get("violations").get(0).get("field").asString()).isEqualTo("labels_by_aisle");
        assertThat(problem.get("violations").toString()).doesNotContain("7");
    }

    @SuppressWarnings("unused")
    void filter(Edition edition) {
    }

    @Test
    void requestBodyViolationUsesTheJsonNameWithoutMapKeys() throws Exception {
        SynapseRestExceptionResolver snakeCase = new SynapseRestExceptionResolver(new ProblemWriter(
                new RestErrorStatuses(Map.of(), Map.of()), new ErrorMessages(messages), new ProblemTitles(messages),
                new SynapseErrorProperties(false, null),
                JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build(),
                "X-Correlation-Id"));
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Edition(1, Map.of()), "edition");
        result.addError(new FieldError("edition", "publishedYear", "must be at most 3 digits"));
        result.addError(new FieldError("edition", "labelsByLocale[en.GB]].shortName", "must not be blank"));
        result.addError(new ObjectError("edition", "edition is inconsistent"));
        MethodParameter body = new MethodParameter(
                SynapseRestExceptionResolverTest.class.getDeclaredMethod("create", Edition.class), 0);

        snakeCase.resolveException(request, response, null, new MethodArgumentNotValidException(body, result));
        JsonNode problem = MAPPER.readTree(response.getContentAsByteArray());

        assertViolation(problem, 0, "published_year", "must be at most 3 digits");
        assertViolation(problem, 1, "labels_by_locale", "must not be blank");
        assertViolation(problem, 2, "edition", "edition is inconsistent");
        assertThat(problem.toString()).doesNotContain("en.GB");
    }

    @Test
    void boundObjectViolationKeepsTheParameterName() throws Exception {
        SynapseRestExceptionResolver snakeCase = new SynapseRestExceptionResolver(new ProblemWriter(
                new RestErrorStatuses(Map.of(), Map.of()), new ErrorMessages(messages), new ProblemTitles(messages),
                new SynapseErrorProperties(false, null),
                JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build(),
                "X-Correlation-Id"));
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Edition(1, Map.of()), "edition");
        result.addError(new FieldError("edition", "publishedYear", "must be at most 3 digits"));
        MethodParameter form = new MethodParameter(
                SynapseRestExceptionResolverTest.class.getDeclaredMethod("filter", Edition.class), 0);

        snakeCase.resolveException(request, response, null, new MethodArgumentNotValidException(form, result));

        // A form or query object binds request parameters by their Java names, so its fields are already wire names.
        assertViolation(MAPPER.readTree(response.getContentAsByteArray()), 0, "publishedYear",
                "must be at most 3 digits");
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
    void otherServerErrorResponseKeepsItsStatusAtWarn() {
        List<ILoggingEvent> events = SynapseErrorControllerTest
                .logged(() -> resolve(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "secret-text")));
        JsonNode body = MAPPER.readTree(response.getContentAsByteArray());

        assertThat(response.getStatus()).isEqualTo(502);
        assertThat(body.get("code").asString()).isEqualTo("SERVER_ERROR");
        assertThat(body.toString()).doesNotContain("secret-text").doesNotContain("Exception");
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void otherClientErrorResponseKeepsItsStatus(CapturedOutput output) {
        assertClientError(new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "secret-text"), 422,
                "CLIENT_ERROR", output);
        // Any ErrorResponse, not only a ResponseStatusException.
        MockHttpServletResponse other = new MockHttpServletResponse();
        resolver.resolveException(request, other, null, new ErrorResponseException(HttpStatus.I_AM_A_TEAPOT));
        assertThat(other.getStatus()).isEqualTo(418);
    }

    @Test
    void internalServerErrorResponseIsInternalAndLogged() {
        // Pin (R-11): a 500 was INTERNAL, logged at ERROR with its stack trace, before this change too.
        List<ILoggingEvent> events = SynapseErrorControllerTest
                .logged(() -> resolve(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "secret-text")));
        JsonNode body = MAPPER.readTree(response.getContentAsByteArray());

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(body.get("code").asString()).isEqualTo("INTERNAL");
        assertThat(body.toString()).doesNotContain("secret-text");
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy()).isNotNull();
        });
    }

    @Test
    void responseThatIsNoErrorIsInternal(CapturedOutput output) {
        // Pin (R-11): a status outside 400-599 is the application's fault, as before.
        JsonNode body = resolve(new ResponseStatusException(HttpStatus.FOUND, "secret-text"));

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(body.get("code").asString()).isEqualTo("INTERNAL");
        assertThat(errorLines(output)).isEqualTo(1);
    }

    @Test
    void responseStatusExceptionAlongTheCauseChainIsFound() {
        JsonNode body = resolve(new IllegalStateException("secret-text",
                new IllegalArgumentException(new ResponseStatusException(HttpStatus.GONE, "secret-text"))));

        assertThat(response.getStatus()).isEqualTo(410);
        assertThat(body.get("code").asString()).isEqualTo("CLIENT_ERROR");
    }

    @Test
    void headerWithSeveralValuesIsJoinedWithCommas() {
        ErrorResponseException busy = new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE);
        busy.getHeaders().add(HttpHeaders.LINK, "</status>; rel=\"status\"");
        busy.getHeaders().add(HttpHeaders.LINK, "</help>; rel=\"help\"");
        busy.getHeaders().set(HttpHeaders.RETRY_AFTER, "5");

        resolve(busy);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeaders(HttpHeaders.LINK))
                .containsExactly("</status>; rel=\"status\", </help>; rel=\"help\"");
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
    }

    @Test
    void otherErrorResponseOnACauseIsIgnored(CapturedOutput output) {
        // Pin (R-11): as Spring's ResponseStatusExceptionResolver, the walk looks for a ResponseStatusException or
        // @ResponseStatus only; a wrapped ErrorResponseException is the application's failure, as before this change.
        JsonNode body = resolve(new IllegalStateException("secret-text", new ErrorResponseException(HttpStatus.GONE)));

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(body.get("code").asString()).isEqualTo("INTERNAL");
        assertThat(errorLines(output)).isEqualTo(1);
    }

    @Test
    void causeCycleEndsTheWalk(CapturedOutput output) {
        // Guard (R-11): passes before the @ResponseStatus walk existed (INTERNAL by fall-through); without the bound the
        // walk would loop on this cycle.
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second", first);
        first.initCause(second);

        JsonNode body = resolve(first);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(body.get("code").asString()).isEqualTo("INTERNAL");
        assertThat(errorLines(output)).isEqualTo(1);
    }

    @Test
    void reasonIsTheDetailOnlyWithIncludeDetail() {
        SynapseRestExceptionResolver detailed = new SynapseRestExceptionResolver(new ProblemWriter(
                new RestErrorStatuses(Map.of(), Map.of()), new ErrorMessages(messages), new ProblemTitles(messages),
                new SynapseErrorProperties(true, null), MAPPER, "X-Correlation-Id"));
        ErrorResponseException withDetail = new ErrorResponseException(HttpStatus.UNPROCESSABLE_CONTENT);
        withDetail.setDetail("the body's detail");

        detailed.resolveException(request, response, null, withDetail);
        assertThat(MAPPER.readTree(response.getContentAsByteArray()).get("detail").asString())
                .isEqualTo("the body's detail");

        MockHttpServletResponse plain = new MockHttpServletResponse();
        resolver.resolveException(request, plain, null, withDetail);
        assertThat(MAPPER.readTree(plain.getContentAsByteArray()).has("detail")).isFalse();
    }
}
