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
package io.americanexpress.synapse.api.rest.internal.edge;

import java.io.IOException;

import org.jspecify.annotations.Nullable;

/**
 * Thrown by the body stream of a {@link SizeLimitedRequest} when the request body is larger than the limit. It is an
 * {@link IOException} so that it travels through the readers of the stream (Jackson, Spring's message converters), which
 * may wrap it; the error handling finds it in the cause chain and answers {@code PAYLOAD_TOO_LARGE} (413).
 */
public final class RequestTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    private final long limit;

    /**
     * Creates the exception.
     *
     * @param limit the limit in bytes
     */
    public RequestTooLargeException(long limit) {
        super("The request body is larger than " + limit + " bytes");
        this.limit = limit;
    }

    /**
     * Returns the limit the body exceeded.
     *
     * @return the limit in bytes
     */
    public long limit() {
        return limit;
    }

    /**
     * Tells whether a failure was caused by a body over the limit.
     *
     * @param failure the failure
     * @return true when the failure or one of its causes is a {@link RequestTooLargeException}
     */
    public static boolean isCauseOf(@Nullable Throwable failure) {
        Throwable current = failure;
        // Bounded, in case a cause chain loops.
        for (int depth = 0; current != null && depth < 32; depth++) {
            if (current instanceof RequestTooLargeException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
