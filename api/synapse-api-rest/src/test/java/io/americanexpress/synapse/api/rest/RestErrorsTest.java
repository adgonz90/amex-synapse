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

import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.core.error.ErrorCategory;

import static org.assertj.core.api.Assertions.assertThat;

class RestErrorsTest {

    @Test
    void statusesAndCategoriesMatchTheSpec() {
        assertError(RestErrors.ROUTE_NOT_FOUND, "No route matches the request", ErrorCategory.NOT_FOUND, 404);
        assertError(RestErrors.METHOD_NOT_ALLOWED, "The method is not allowed for this route",
                ErrorCategory.INVALID_INPUT, 405);
        assertError(RestErrors.NOT_ACCEPTABLE, "The requested media type is not available",
                ErrorCategory.INVALID_INPUT, 406);
        assertError(RestErrors.PAYLOAD_TOO_LARGE, "The request body is too large", ErrorCategory.INVALID_INPUT, 413);
        assertError(RestErrors.UNSUPPORTED_MEDIA_TYPE, "The request media type is not supported",
                ErrorCategory.INVALID_INPUT, 415);
        assertError(RestErrors.MISSING_API_VERSION, "The request must name an API version",
                ErrorCategory.INVALID_INPUT, 400);
        assertError(RestErrors.UNSUPPORTED_API_VERSION, "The requested API version is not supported",
                ErrorCategory.INVALID_INPUT, 400);
        assertError(RestErrors.CORS_REJECTED, "The cross-origin request is not allowed", ErrorCategory.FORBIDDEN,
                403);
        // A Spring status outside Synapse's table keeps its status; these are the codes' defaults.
        assertError(RestErrors.CLIENT_ERROR, "The request was rejected", ErrorCategory.INVALID_INPUT, 400);
        assertError(RestErrors.SERVER_ERROR, "The server could not complete the request", ErrorCategory.INTERNAL,
                500);
        // PRECONDITION_REQUIRED is core's (SynapseErrors), so that every protocol can raise it.
        assertThat(RestErrors.values()).hasSize(10);
    }

    private static void assertError(RestErrors error, String message, ErrorCategory category, int status) {
        assertThat(error.code()).isEqualTo(error.name());
        assertThat(error.messageTemplate()).isEqualTo(message);
        assertThat(error.category()).isEqualTo(category);
        assertThat(error.status()).isEqualTo(status);
    }
}
