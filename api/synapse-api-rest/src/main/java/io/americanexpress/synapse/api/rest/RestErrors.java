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
package io.americanexpress.synapse.api.rest;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.ErrorDetails;

import static io.americanexpress.synapse.core.error.ErrorCategory.FORBIDDEN;
import static io.americanexpress.synapse.core.error.ErrorCategory.INVALID_INPUT;
import static io.americanexpress.synapse.core.error.ErrorCategory.NOT_FOUND;

/**
 * Errors the REST edge reports for requests that never reach an operation. Each has a fixed HTTP status because the
 * status comes from the protocol, not from an operation's category. A request without a precondition the operation's
 * input requires is core's {@code SynapseErrors.PRECONDITION_REQUIRED}, which every protocol can raise; REST answers
 * it 428.
 */
public enum RestErrors implements ErrorDetails {

    /**
     * The request names no API version, and Boot's {@code spring.mvc.apiversion.required} requires one.
     */
    MISSING_API_VERSION("The request must name an API version", INVALID_INPUT, 400),

    /**
     * The request names an API version that is not supported, from a header, query parameter or media type parameter
     * Boot's {@code spring.mvc.apiversion.use.*} settings read. A version in the path that no route declares is
     * {@link #ROUTE_NOT_FOUND}.
     */
    UNSUPPORTED_API_VERSION("The requested API version is not supported", INVALID_INPUT, 400),

    /**
     * The request comes from a browser page whose origin, method or request headers
     * {@code synapse.api.rest.cors} doesn't allow, or its {@code Origin} is malformed.
     */
    CORS_REJECTED("The cross-origin request is not allowed", FORBIDDEN, 403),

    /** No route matches the request path. */
    ROUTE_NOT_FOUND("No route matches the request", NOT_FOUND, 404),

    /** The route exists but not for the request method. */
    METHOD_NOT_ALLOWED("The method is not allowed for this route", INVALID_INPUT, 405),

    /** The route cannot produce a media type the caller accepts. */
    NOT_ACCEPTABLE("The requested media type is not available", INVALID_INPUT, 406),

    /** The request body is larger than the route allows. */
    PAYLOAD_TOO_LARGE("The request body is too large", INVALID_INPUT, 413),

    /** The route does not consume the media type of the request body. */
    UNSUPPORTED_MEDIA_TYPE("The request media type is not supported", INVALID_INPUT, 415);

    private final String messageTemplate;

    private final ErrorCategory category;

    private final int status;

    RestErrors(String messageTemplate, ErrorCategory category, int status) {
        this.messageTemplate = messageTemplate;
        this.category = category;
        this.status = status;
    }

    @Override
    public String messageTemplate() {
        return messageTemplate;
    }

    @Override
    public ErrorCategory category() {
        return category;
    }

    @Override
    public String code() {
        return name();
    }

    /**
     * Returns the HTTP status for this error.
     *
     * @return the status code
     */
    public int status() {
        return status;
    }
}
