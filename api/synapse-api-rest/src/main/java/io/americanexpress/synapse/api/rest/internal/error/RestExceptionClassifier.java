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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.accept.InvalidApiVersionException;
import org.springframework.web.accept.MissingApiVersionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingRequestCookieException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import io.americanexpress.synapse.api.rest.RestErrors;
import io.americanexpress.synapse.api.rest.internal.edge.RequestTooLargeException;
import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;

/**
 * Classifies a failure that reaches the REST edge into the error the caller sees, and logs it. It is the one
 * classification of {@link SynapseRestExceptionResolver} (exceptions inside Spring MVC),
 * {@link SynapseErrorController} (the exception of the servlet container's error dispatch, such as one a filter threw)
 * and {@link ClientErrorFilter} (client errors that escape the filter chain), so an exception gets the same code and
 * status on every path, and the same log level on the first two:
 * <ul>
 * <li>a {@link SynapseException} is the error itself, and is not logged here;</li>
 * <li>a body over the size limit anywhere in the cause chain is {@code PAYLOAD_TOO_LARGE};</li>
 * <li>Spring MVC's protocol and client-error exceptions map to their error (validation failures with their
 * violations), logged at DEBUG, or at WARN when they map to {@code UNAVAILABLE} or {@code TIMEOUT}; Spring's API
 * version errors are {@code MISSING_API_VERSION} and {@code UNSUPPORTED_API_VERSION};</li>
 * <li>anything else is {@code INTERNAL}, logged at ERROR with its stack trace.</li>
 * </ul>
 * Each line carries the edge's correlation id, also outside the request context (on ERROR and ASYNC dispatches).
 * Exception text goes to the log only; the error carries none of it.
 */
final class RestExceptionClassifier {

    private static final Logger LOG = LoggerFactory.getLogger("io.americanexpress.synapse.api.rest");

    private RestExceptionClassifier() {
    }

    /**
     * Classifies the failure and logs it.
     *
     * @param request the request, or the error dispatch request
     * @param failure the failure
     * @return the error to write
     */
    static SynapseException classify(HttpServletRequest request, Throwable failure) {
        SynapseException mapped = errorFor(failure);
        if (failure instanceof SynapseException) {
            return mapped;
        }
        // A protocol error never maps to INTERNAL, so INTERNAL means the failure is not one.
        if (mapped.details() == SynapseErrors.INTERNAL) {
            logUnhandled(request, failure);
            return mapped;
        }
        ErrorCategory category = mapped.category();
        try (MDC.MDCCloseable ignored = CorrelationMdc.put(request)) {
            if (category == ErrorCategory.UNAVAILABLE || category == ErrorCategory.TIMEOUT) {
                LOG.warn("Request failed: {}", failure.toString());
            } else {
                LOG.debug("Request rejected: {}", failure.toString());
            }
        }
        return mapped;
    }

    /**
     * Classifies the failure as {@link #classify} does, without logging it.
     *
     * @param failure the failure
     * @return the error to write
     */
    static SynapseException errorFor(Throwable failure) {
        if (failure instanceof SynapseException synapse) {
            return synapse;
        }
        SynapseException mapped = protocolError(failure);
        return mapped == null ? new SynapseException(SynapseErrors.INTERNAL, failure) : mapped;
    }

    /**
     * Logs a failure the caller sees as a server error, with its stack trace and the edge's correlation id.
     *
     * @param request the request, or the error dispatch request
     * @param failure the failure
     */
    static void logUnhandled(HttpServletRequest request, Throwable failure) {
        try (MDC.MDCCloseable ignored = CorrelationMdc.put(request)) {
            LOG.error("Unhandled error for {} {}", request.getMethod(), requestUri(request), failure);
        }
    }

    /** The URI the caller requested: on an error dispatch, the one the container reports. */
    private static @Nullable Object requestUri(HttpServletRequest request) {
        Object original = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        return request.getDispatcherType() == DispatcherType.ERROR && original != null
                ? original
                : request.getRequestURI();
    }

    /** Maps a protocol or client-error failure, or returns null when the failure is not one. */
    private static @Nullable SynapseException protocolError(Throwable failure) {
        // The size limit trips inside whatever reads the body (Spring's converters, Jackson, a filter), which may wrap
        // it.
        if (RequestTooLargeException.isCauseOf(failure)) {
            return new SynapseException(RestErrors.PAYLOAD_TOO_LARGE, failure);
        }
        if (!(failure instanceof Exception ex)) {
            return null;
        }
        if (ex instanceof HttpMessageNotReadableException) {
            return new SynapseException(SynapseErrors.MALFORMED_REQUEST, ex);
        }
        if (ex instanceof HttpMediaTypeNotSupportedException) {
            return new SynapseException(RestErrors.UNSUPPORTED_MEDIA_TYPE, ex);
        }
        if (ex instanceof HttpMediaTypeNotAcceptableException) {
            return new SynapseException(RestErrors.NOT_ACCEPTABLE, ex);
        }
        if (ex instanceof NoResourceFoundException || ex instanceof NoHandlerFoundException) {
            return new SynapseException(RestErrors.ROUTE_NOT_FOUND, ex);
        }
        if (ex instanceof HttpRequestMethodNotSupportedException notSupported) {
            return methodNotAllowed(notSupported);
        }
        // Before the generic ErrorResponse branch, which would make both MALFORMED_REQUEST.
        if (ex instanceof MissingApiVersionException) {
            return new SynapseException(RestErrors.MISSING_API_VERSION, ex);
        }
        if (ex instanceof InvalidApiVersionException) {
            return new SynapseException(RestErrors.UNSUPPORTED_API_VERSION, ex);
        }
        return clientError(ex);
    }

    /** Maps Spring MVC's own client-error exceptions, or returns null when the exception is not one of them. */
    private static @Nullable SynapseException clientError(Exception ex) {
        if (ex instanceof MethodArgumentNotValidException invalid) {
            List<Violation> violations = new ArrayList<>();
            for (ObjectError error : invalid.getBindingResult().getAllErrors()) {
                String name = error instanceof FieldError field ? field.getField() : error.getObjectName();
                violations.add(new Violation(name, messageOf(error)));
            }
            return validationFailed(violations);
        }
        if (ex instanceof HandlerMethodValidationException invalid) {
            return validationFailed(violations(invalid));
        }
        if (ex instanceof MethodArgumentTypeMismatchException mismatch) {
            return mismatch(mismatch.getName());
        }
        if (ex instanceof TypeMismatchException mismatch) {
            return mismatch(mismatch.getPropertyName());
        }
        if (ex instanceof MissingPathVariableException missing && !missing.getStatusCode().is4xxClientError()) {
            return null;
        }
        if (ex instanceof ServletRequestBindingException binding) {
            String name = missingName(binding);
            return name == null
                    ? new SynapseException(SynapseErrors.MALFORMED_REQUEST, ex)
                    : validationFailed(List.of(new Violation(name, "must not be null")));
        }
        if (ex instanceof ErrorResponse response) {
            ErrorDetails mapped = StatusErrors.forStatus(response.getStatusCode().value(), false);
            if (mapped != SynapseErrors.INTERNAL) {
                return new SynapseException(mapped, ex);
            }
        }
        return null;
    }

    private static SynapseException mismatch(@Nullable String name) {
        return name == null
                ? new SynapseException(SynapseErrors.MALFORMED_REQUEST)
                : validationFailed(List.of(new Violation(name, "is not a valid value")));
    }

    private static @Nullable String missingName(ServletRequestBindingException ex) {
        if (ex instanceof MissingServletRequestParameterException parameter) {
            return parameter.getParameterName();
        }
        if (ex instanceof MissingRequestHeaderException header) {
            return header.getHeaderName();
        }
        if (ex instanceof MissingRequestCookieException cookie) {
            return cookie.getCookieName();
        }
        if (ex instanceof MissingPathVariableException variable) {
            return variable.getVariableName();
        }
        return null;
    }

    private static List<Violation> violations(HandlerMethodValidationException ex) {
        List<Violation> violations = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors errors) {
                for (ObjectError error : errors.getAllErrors()) {
                    String name = error instanceof FieldError field ? field.getField() : error.getObjectName();
                    violations.add(new Violation(name, messageOf(error)));
                }
            } else {
                String name = wireName(result.getMethodParameter());
                for (MessageSourceResolvable error : result.getResolvableErrors()) {
                    violations.add(new Violation(name == null ? "value" : name, messageOf(error)));
                }
            }
        }
        for (MessageSourceResolvable error : ex.getCrossParameterValidationResults()) {
            violations.add(new Violation("value", messageOf(error)));
        }
        return violations;
    }

    private static String messageOf(MessageSourceResolvable error) {
        // A binding failure carries Spring conversion text with Java type names; only constraint messages pass through.
        if (error instanceof FieldError field && field.isBindingFailure()) {
            return "is not a valid value";
        }
        String message = error.getDefaultMessage();
        return message == null ? "is invalid" : message;
    }

    private static @Nullable String wireName(MethodParameter parameter) {
        RequestParam param = parameter.getParameterAnnotation(RequestParam.class);
        if (param != null) {
            return firstNonEmpty(param.name(), param.value(), parameter);
        }
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        if (header != null) {
            return firstNonEmpty(header.name(), header.value(), parameter);
        }
        PathVariable path = parameter.getParameterAnnotation(PathVariable.class);
        if (path != null) {
            return firstNonEmpty(path.name(), path.value(), parameter);
        }
        CookieValue cookie = parameter.getParameterAnnotation(CookieValue.class);
        if (cookie != null) {
            return firstNonEmpty(cookie.name(), cookie.value(), parameter);
        }
        return parameter.getParameterName();
    }

    private static @Nullable String firstNonEmpty(String name, String value, MethodParameter parameter) {
        if (!name.isEmpty()) {
            return name;
        }
        return value.isEmpty() ? parameter.getParameterName() : value;
    }

    private static SynapseException validationFailed(List<Violation> violations) {
        return SynapseException.ofViolations(SynapseErrors.VALIDATION_FAILED, violations);
    }

    private static SynapseException methodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        Set<HttpMethod> supported = ex.getSupportedHttpMethods();
        if (supported == null || supported.isEmpty()) {
            return new RestProtocolException(RestErrors.METHOD_NOT_ALLOWED, Map.of());
        }
        TreeSet<String> names = new TreeSet<>();
        supported.forEach(method -> names.add(method.name()));
        return new RestProtocolException(RestErrors.METHOD_NOT_ALLOWED, Map.of("Allow", String.join(", ", names)));
    }

}
