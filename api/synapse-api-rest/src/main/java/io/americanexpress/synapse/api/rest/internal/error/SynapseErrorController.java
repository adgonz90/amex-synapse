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

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import io.americanexpress.synapse.api.rest.RestErrors;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;

/**
 * Handles the servlet container's error dispatch (errors raised outside Spring MVC, such as in a filter) and answers
 * with a problem response instead of Boot's error page. It replaces Boot's {@code BasicErrorController}. The
 * dispatch's exception is classified as the exception resolver classifies it ({@link RestExceptionClassifier}), so it
 * gets the same code, status and log level; without an exception, the error follows the container's status.
 * <p>
 * It isn't final, as Boot's isn't: an application's aspect on every {@code @Controller} matches it too, and Spring
 * proxies it by subclassing.
 */
@Controller
@RequestMapping("${server.error.path:${error.path:/error}}")
public class SynapseErrorController implements ErrorController {

    private final ProblemWriter writer;

    /**
     * Creates the controller.
     *
     * @param writer writes the problem response
     */
    public SynapseErrorController(ProblemWriter writer) {
        this.writer = writer;
    }

    /**
     * Writes the problem for the exception or the status the container reports.
     *
     * @param request the error dispatch request
     * @param response the response
     */
    @RequestMapping
    public void error(HttpServletRequest request, HttpServletResponse response) {
        if (request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof Throwable failure) {
            SynapseException error = RestExceptionClassifier.classify(request, failure);
            if (failure instanceof SynapseException && writer.statusOf(request, error) >= 500) {
                // A SynapseException that reaches the error dispatch was thrown outside the operation pipeline, so
                // nothing logged it yet.
                RestExceptionClassifier.logUnhandled(request, failure);
            }
            writer.write(request, response, error);
            return;
        }
        writer.write(request, response, new SynapseException(errorFor(request)));
    }

    private static ErrorDetails errorFor(HttpServletRequest request) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (status == null && request.getDispatcherType() != DispatcherType.ERROR) {
            // A direct request to the error path, not a container error dispatch.
            return RestErrors.ROUTE_NOT_FOUND;
        }
        if (!(status instanceof Integer code)) {
            return SynapseErrors.INTERNAL;
        }
        return StatusErrors.forStatus(code, true);
    }
}
