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
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import io.americanexpress.synapse.core.error.SynapseException;

/**
 * Answers client errors that escape the filter chain, such as a form body that Boot's {@code FormContentFilter}
 * (order -9900) can't decode, or a body over the size limit that it read. Left to the container, such an exception is
 * logged at ERROR with its stack trace by Tomcat before the error dispatch, which lets any caller flood the logs.
 *
 * <p>
 * The filter classifies the exception as the error controller does ({@link RestExceptionClassifier}; the root cause of
 * a {@link ServletException}, as Tomcat reports it to the error dispatch). When the error's status is 4xx, the response
 * is not committed and the request did not start async processing, it writes the problem with the
 * {@link ProblemWriter} (code, violations and correlation id kept, no exception text), logs one DEBUG line with the
 * correlation id and returns normally. Any other exception, and every {@link Error}, is rethrown unchanged, so the
 * container's error dispatch and {@link SynapseErrorController} handle and log it as before. When the problem can't
 * be written (for example, the chain already called {@code getWriter()}), the write failure is added to the original
 * exception as suppressed and the original is rethrown.
 *
 * <p>
 * It is the innermost edge filter: inside the context filter, so the problem and its log line carry the request's
 * correlation id, inside the CORS filter, so the problem carries the CORS headers of an allowed origin, and inside
 * Boot's server observation, which then records the 4xx response instead of an exception.
 */
public final class ClientErrorFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger LOG = LoggerFactory.getLogger("io.americanexpress.synapse.api.rest");

    private final ProblemWriter writer;

    /**
     * Creates the filter.
     *
     * @param writer writes the problem response
     */
    public ClientErrorFilter(ProblemWriter writer) {
        this.writer = writer;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 6;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException failure) {
            // Once async processing started, the container must see the failure to end the async request.
            if (response.isCommitted() || request.isAsyncStarted()) {
                throw failure;
            }
            SynapseException error = RestExceptionClassifier.errorFor(reported(failure));
            int status = writer.statusOf(request, error);
            if (status < 400 || status > 499) {
                throw failure;
            }
            try {
                writer.write(request, response, error);
            } catch (RuntimeException writeFailure) {
                // Such as getOutputStream() after the chain called getWriter(): leave the original to the container.
                failure.addSuppressed(writeFailure);
                throw failure;
            }
            try (MDC.MDCCloseable ignored = CorrelationMdc.put(request)) {
                LOG
                        .debug("Answered {} {} with {} {}: {}", request.getMethod(), request.getRequestURI(), status,
                                error.code(), failure.toString());
            }
        }
    }

    /** The exception Tomcat reports to the error dispatch: the root cause of a {@link ServletException}. */
    private static Throwable reported(Exception failure) {
        Throwable rootCause = failure instanceof ServletException servlet ? servlet.getRootCause() : null;
        return rootCause == null ? failure : rootCause;
    }
}
