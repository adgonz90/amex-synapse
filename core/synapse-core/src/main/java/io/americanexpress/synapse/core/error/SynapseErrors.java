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
package io.americanexpress.synapse.core.error;

/**
 * The error codes Synapse itself raises. Their templates take no arguments.
 */
public enum SynapseErrors implements ErrorDetails {

    /** The request could not be parsed. */
    MALFORMED_REQUEST("The request is malformed", ErrorCategory.INVALID_INPUT),

    /** The request failed validation. */
    VALIDATION_FAILED("The request is invalid", ErrorCategory.INVALID_INPUT),

    /** The resource does not exist. */
    NOT_FOUND("The resource was not found", ErrorCategory.NOT_FOUND),

    /** Authentication is required. */
    UNAUTHENTICATED("Authentication is required", ErrorCategory.UNAUTHENTICATED),

    /** Access is denied. */
    FORBIDDEN("Access is denied", ErrorCategory.FORBIDDEN),

    /** The request conflicts with the resource state. */
    CONFLICT("The request conflicts with the current state of the resource", ErrorCategory.CONFLICT),

    /** The resource changed since the caller read it, so the update would overwrite someone else's change. */
    STALE_VERSION("The resource was changed by another request", ErrorCategory.CONFLICT),

    /** A precondition failed. */
    PRECONDITION_FAILED("A precondition failed", ErrorCategory.PRECONDITION_FAILED),

    /** The caller is rate limited. */
    RATE_LIMITED("Too many requests", ErrorCategory.RATE_LIMITED),

    /** The service is unavailable. */
    UNAVAILABLE("The service is unavailable", ErrorCategory.UNAVAILABLE),

    /** The deadline expired. */
    DEADLINE_EXCEEDED("The deadline expired", ErrorCategory.TIMEOUT),

    /** An unexpected internal failure. */
    INTERNAL("An internal error occurred", ErrorCategory.INTERNAL);

    private final String messageTemplate;

    private final ErrorCategory category;

    SynapseErrors(String messageTemplate, ErrorCategory category) {
        this.messageTemplate = messageTemplate;
        this.category = category;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public String messageTemplate() {
        return messageTemplate;
    }

    @Override
    public ErrorCategory category() {
        return category;
    }
}
