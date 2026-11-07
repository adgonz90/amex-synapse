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
package io.americanexpress.synapse.api.rest.internal.route;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import io.americanexpress.synapse.api.rest.RestErrors;
import io.americanexpress.synapse.api.rest.internal.binding.RequestBinder;
import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.api.rest.internal.conditional.Preconditions;
import io.americanexpress.synapse.api.rest.internal.edge.RequestSizeLimits;
import io.americanexpress.synapse.api.rest.internal.edge.RestRequestAttributes;
import io.americanexpress.synapse.api.rest.internal.paging.PageCursors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.operation.ServiceOperation;

/**
 * Handles the requests of one route. In order, it:
 * <ol>
 * <li>sets {@link RestRequestAttributes#OPERATION_NAME}, so errors use the operation's status overrides;</li>
 * <li>applies the route's request body limit ({@code .maxRequestSize(...)}), if it has one, in place of the default;</li>
 * <li>checks {@code Accept}: absent or JSON-compatible, otherwise {@link RestErrors#NOT_ACCEPTABLE};</li>
 * <li>binds the input;</li>
 * <li>replaces the client's cursor token with the operation's raw cursor (a token the codec rejects is the cursor
 * violation, and the operation is not called);</li>
 * <li>checks a write's preconditions: an {@code If-Match} or {@code If-None-Match} the route doesn't bind, or a weak
 * {@code If-Match}, is 412; a missing {@code If-Match} or {@code If-None-Match} that the input requires (a
 * {@code VersionCondition} component that isn't {@code @Nullable}) is 428. Binding already answers that one, since
 * the input can't be built without it, so its cursor is never read;</li>
 * <li>calls the operation bean, a proxy that runs the operation pipeline, and answers its {@code STALE_VERSION} as 412
 * when the request sent a bound {@code If-Match};</li>
 * <li>writes the response, wrapping a page's next cursor in a token bound to the same input.</li>
 * </ol>
 */
public final class OperationHandler implements HandlerFunction<ServerResponse> {

    private final RouteDefinition route;

    private final RequestBinder binder;

    private final ResponseWriter writer;

    private final PageCursors cursors;

    /**
     * Creates the handler.
     *
     * @param route the resolved route
     * @param binder binds the input
     * @param writer writes the response
     * @param cursors unwraps the client's cursor token
     */
    public OperationHandler(RouteDefinition route, RequestBinder binder, ResponseWriter writer, PageCursors cursors) {
        this.route = route;
        this.binder = binder;
        this.writer = writer;
        this.cursors = cursors;
    }

    /**
     * Returns the route this handler serves.
     *
     * @return the resolved route
     */
    public RouteDefinition route() {
        return route;
    }

    @Override
    public ServerResponse handle(ServerRequest request) {
        request.servletRequest().setAttribute(RestRequestAttributes.OPERATION_NAME, route.operationName());
        DataSize maxRequestSize = route.response().maxRequestSize();
        if (maxRequestSize != null) {
            // Before binding opens the body stream; the limit can't change after that.
            RequestSizeLimits.apply(request.servletRequest(), maxRequestSize);
        }
        checkAccept(request);
        Object bound = binder.bind(route, request);
        // The digest leaves the PageQuery out, so the unwrapped input has the same cursor scope as the bound one.
        Object input = cursors.unwrap(route, bound);
        // After the 400s, just before the action (RFC 9110 §13.2.1).
        Preconditions.check(route, request);
        Object result;
        try {
            result = execute(route.operation(), input);
        } catch (SynapseException e) {
            throw Preconditions.translate(route, request, e);
        }
        return writer.write(route, result, request, input);
    }

    /**
     * Calls the operation with the bound input, which is {@code null} only when the input type is {@code Void}. The
     * output is {@code null} for a {@code void} operation. NullAway can't see that {@code I} is {@code Void} when the
     * input is {@code null}, as callers of a {@code ServiceOperation<Void, O>} pass it.
     */
    @SuppressWarnings({"unchecked", "NullAway"})
    private static <I, O> @Nullable Object execute(ServiceOperation<I, O> operation, @Nullable Object input) {
        return operation.execute((I) input);
    }

    /** Accepts a request without {@code Accept}, or one whose {@code Accept} admits {@code application/json}. */
    private static void checkAccept(ServerRequest request) {
        List<String> values = request.headers().header(HttpHeaders.ACCEPT);
        if (values.isEmpty()) {
            return;
        }
        List<MediaType> accepted;
        try {
            accepted = MediaType.parseMediaTypes(values);
        } catch (InvalidMediaTypeException e) {
            throw new SynapseException(RestErrors.NOT_ACCEPTABLE, e);
        }
        if (accepted.isEmpty()) {
            // An empty Accept header names no type, like no header.
            return;
        }
        for (MediaType type : accepted) {
            if (type.getQualityValue() > 0 && type.isCompatibleWith(MediaType.APPLICATION_JSON)) {
                return;
            }
        }
        throw new SynapseException(RestErrors.NOT_ACCEPTABLE);
    }
}
