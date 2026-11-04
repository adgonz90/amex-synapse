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
package io.americanexpress.synapse.api.rest.paging;

import io.americanexpress.synapse.core.Incubating;

/**
 * Thrown by {@link CursorCodec#decode} for a token it rejects. The caller always gets the same response, whatever the
 * reason, so the token reveals nothing about why it failed.
 */
@Incubating
public final class InvalidCursorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param reason a short reason for DEBUG logs, never for the client; it must not contain the token, its contents
     *     or key material
     */
    public InvalidCursorException(String reason) {
        super(reason);
    }
}
