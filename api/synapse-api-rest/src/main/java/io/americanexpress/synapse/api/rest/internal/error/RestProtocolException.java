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

import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.error.SynapseException;

/**
 * A failure of the HTTP protocol that must add response headers, such as {@code Allow} on a 405.
 */
public class RestProtocolException extends SynapseException {

    private static final long serialVersionUID = 1L;

    private final transient Map<String, String> headers;

    /**
     * Creates the exception.
     *
     * @param details the error
     * @param headers the response headers to set, by name
     */
    public RestProtocolException(ErrorDetails details, Map<String, String> headers) {
        super(details);
        this.headers = Map.copyOf(headers);
    }

    /**
     * Creates the exception with a cause.
     *
     * @param details the error
     * @param headers the response headers to set, by name
     * @param cause the cause, or {@code null} when there is none (a bare status of the container's error dispatch)
     */
    // initCause is Throwable's, called with a cause the caller passed: no subclass overrides it (KeptStatusException,
    // the only one, is final), and SynapseException has no constructor that takes a null cause.
    @SuppressWarnings("this-escape")
    protected RestProtocolException(ErrorDetails details, Map<String, String> headers, @Nullable Throwable cause) {
        super(details);
        this.headers = Map.copyOf(headers);
        if (cause != null) {
            initCause(cause);
        }
    }

    /**
     * Returns the response headers to set.
     *
     * @return the headers, by name
     */
    public Map<String, String> headers() {
        return headers;
    }
}
