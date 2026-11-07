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
package io.americanexpress.synapse.api.grpc;

import io.grpc.Status;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.ErrorDetails;

/**
 * Errors the gRPC edge reports for calls that never reach an operation. Each has a fixed gRPC status because the
 * status comes from the protocol, not from an operation's category.
 */
public enum GrpcErrors implements ErrorDetails {

    /** No service method matches the called method name. */
    METHOD_NOT_FOUND("No method matches the call", ErrorCategory.NOT_FOUND, Status.Code.UNIMPLEMENTED);

    private final String messageTemplate;

    private final ErrorCategory category;

    private final Status.Code status;

    GrpcErrors(String messageTemplate, ErrorCategory category, Status.Code status) {
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
     * Returns the gRPC status for this error.
     *
     * @return the status code
     */
    public Status.Code status() {
        return status;
    }
}
