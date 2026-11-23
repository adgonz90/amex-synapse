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

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

import io.americanexpress.synapse.api.rest.internal.route.MethodNotAllowedHandler;
import io.americanexpress.synapse.api.rest.internal.route.OperationHandler;
import io.americanexpress.synapse.core.error.SynapseException;

/**
 * Turns an exception that reaches Spring MVC into a problem response; the full exception goes to the log, and the
 * caller never sees exception text or a stack trace. The classification and its log levels are shared with the error
 * controller ({@link RestExceptionClassifier}). It is not a bean: {@link SynapseRestExceptionResolverConfigurer} adds
 * two instances to Spring MVC's composite resolver.
 * <ul>
 * <li>The main instance runs right after Spring's {@code ExceptionHandlerExceptionResolver}, so an application's
 * {@code @ExceptionHandler} methods and {@code @ControllerAdvice} classes answer first for its controllers, and
 * answers every exception they leave.</li>
 * <li>The Synapse-paths instance ({@link #forSynapsePaths}) runs first in the composite. It answers only an exception
 * raised on a Synapse route's path ({@link SynapsePaths}) whose handler is a Synapse handler or none was found (a
 * missing or invalid API version, a 405 or 415 from annotated-mapping lookup), so on a Synapse path the answer is
 * always Synapse's problem, before any advice. It also answers the 404 of a path no handler serves under a Synapse
 * route's literal path prefix ({@link SynapsePaths#owns}: {@code /api/books} for {@code /api/books/{id}}, under any
 * version for a versioned route), so an unknown path of the Synapse API is {@code ROUTE_NOT_FOUND} whatever advice the
 * application has; other 404s keep Spring's order.</li>
 * </ul>
 * Neither instance answers when the response is committed, or for Spring Security's {@code AccessDeniedException} and
 * {@code AuthenticationException}, also wrapped in other exceptions ({@link #isSecurityException}), which propagate to
 * Security's {@code ExceptionTranslationFilter}.
 */
public final class SynapseRestExceptionResolver implements HandlerExceptionResolver {

    /** Spring Security's two exceptions, by name: Synapse has no Security dependency. */
    private static final Set<String> SECURITY_EXCEPTIONS = Set
            .of("org.springframework.security.access.AccessDeniedException",
                    "org.springframework.security.core.AuthenticationException");

    /** How many exceptions of a cause chain are searched for a Security exception. */
    private static final int MAX_CAUSES = 32;

    private final ProblemWriter writer;

    /** The Synapse paths of the Synapse-paths instance; null for the main instance. */
    private final @Nullable SynapsePaths paths;

    /**
     * Creates the main instance, which answers every exception except a Security exception, unless the response is
     * committed.
     *
     * @param writer writes the problem response
     */
    public SynapseRestExceptionResolver(ProblemWriter writer) {
        this(writer, null);
    }

    private SynapseRestExceptionResolver(ProblemWriter writer, @Nullable SynapsePaths paths) {
        this.writer = writer;
        this.paths = paths;
    }

    /**
     * Creates the Synapse-paths instance: it answers only when the handler is {@code null}, an
     * {@link OperationHandler} or a {@link MethodNotAllowedHandler}, and a Synapse route serves the request's path, or
     * when no handler serves the path ({@code NoResourceFoundException} or {@code NoHandlerFoundException}) and it is
     * one a Synapse route owns ({@link SynapsePaths#owns}); otherwise it returns {@code null} and the next resolver
     * runs.
     *
     * @param writer writes the problem response
     * @param paths the paths the Synapse routes serve
     * @return the resolver
     */
    public static SynapseRestExceptionResolver forSynapsePaths(ProblemWriter writer, SynapsePaths paths) {
        return new SynapseRestExceptionResolver(writer, paths);
    }

    /**
     * Tells whether the exception is, or wraps, one of Spring Security's: an exception in its cause chain whose class
     * or a superclass is {@code org.springframework.security.access.AccessDeniedException} or
     * {@code org.springframework.security.core.AuthenticationException}. The chain is walked as Security's
     * {@code ThrowableAnalyzer} walks it in {@code ExceptionTranslationFilter}, which then finds the wrapped one; the
     * walk stops after {@value #MAX_CAUSES} exceptions, at an exception already seen, and at a
     * {@link SynapseException}: that is Synapse's own answer, such as the {@code INTERNAL} the operation pipeline makes
     * of method security inside an operation on every protocol.
     *
     * @param ex the exception
     * @return true for a Security exception anywhere in the cause chain
     */
    static boolean isSecurityException(Throwable ex) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = ex;
        for (int depth = 0; current != null && depth < MAX_CAUSES && seen.add(current); depth++) {
            if (current instanceof SynapseException) {
                // Synapse's own answer: the pipeline made method security inside an operation INTERNAL, as on gRPC.
                return false;
            }
            for (Class<?> type = current.getClass(); type != null; type = type.getSuperclass()) {
                if (SECURITY_EXCEPTIONS.contains(type.getName())) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    @Override
    public @Nullable ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
            @Nullable Object handler, Exception ex) {
        if (response.isCommitted() || isSecurityException(ex)) {
            return null;
        }
        if (paths != null && !(isSynapseHandler(handler) && paths.matches(request))
                && !(isRouteNotFound(handler, ex) && paths.owns(request))) {
            return null;
        }
        writer.write(request, response, RestExceptionClassifier.classify(request, ex, writer.mapper()));
        return new ModelAndView();
    }

    /** No handler (the failure came before one was found), or one of Synapse's. */
    private static boolean isSynapseHandler(@Nullable Object handler) {
        return handler == null || handler instanceof OperationHandler || handler instanceof MethodNotAllowedHandler;
    }

    /**
     * The 404 of a path no handler serves: the static resource handler's {@code NoResourceFoundException} (Boot maps
     * it to {@code /**}), or {@code NoHandlerFoundException} when no handler was found at all.
     */
    private static boolean isRouteNotFound(@Nullable Object handler, Exception ex) {
        return (handler == null || handler instanceof ResourceHttpRequestHandler)
                && (ex instanceof NoResourceFoundException || ex instanceof NoHandlerFoundException);
    }
}
