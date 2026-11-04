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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * Turns every exception that reaches Spring MVC into a problem response. It runs before Spring's own resolvers, so
 * callers never see a framework error page, exception text or stack trace; the full exception goes to the log. The
 * classification and its log levels are shared with the error controller ({@link RestExceptionClassifier}).
 */
public final class SynapseRestExceptionResolver implements HandlerExceptionResolver, Ordered {

    private final ProblemWriter writer;

    /**
     * Creates the resolver.
     *
     * @param writer writes the problem response
     */
    public SynapseRestExceptionResolver(ProblemWriter writer) {
        this.writer = writer;
    }

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE;
    }

    @Override
    public @Nullable ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
            @Nullable Object handler, Exception ex) {
        if (response.isCommitted()) {
            return null;
        }
        writer.write(request, response, RestExceptionClassifier.classify(request, ex));
        return new ModelAndView();
    }
}
