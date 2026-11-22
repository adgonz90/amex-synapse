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

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SynapseErrorsTest {

    private static final Map<String, ErrorCategory> EXPECTED = Map
            .ofEntries(
                    Map.entry("MALFORMED_REQUEST", ErrorCategory.INVALID_INPUT),
                    Map.entry("VALIDATION_FAILED", ErrorCategory.INVALID_INPUT),
                    Map.entry("NOT_FOUND", ErrorCategory.NOT_FOUND),
                    Map.entry("UNAUTHENTICATED", ErrorCategory.UNAUTHENTICATED),
                    Map.entry("FORBIDDEN", ErrorCategory.FORBIDDEN),
                    Map.entry("CONFLICT", ErrorCategory.CONFLICT),
                    Map.entry("STALE_VERSION", ErrorCategory.CONFLICT),
                    Map.entry("ALREADY_EXISTS", ErrorCategory.CONFLICT),
                    Map.entry("PRECONDITION_FAILED", ErrorCategory.PRECONDITION_FAILED),
                    Map.entry("PRECONDITION_REQUIRED", ErrorCategory.PRECONDITION_FAILED),
                    Map.entry("RATE_LIMITED", ErrorCategory.RATE_LIMITED),
                    Map.entry("UNAVAILABLE", ErrorCategory.UNAVAILABLE),
                    Map.entry("DEADLINE_EXCEEDED", ErrorCategory.TIMEOUT),
                    Map.entry("INTERNAL", ErrorCategory.INTERNAL));

    @Test
    void everyCodeHasItsCategory() {
        assertThat(SynapseErrors.values()).hasSize(EXPECTED.size()).hasSize(14);
        for (SynapseErrors error : SynapseErrors.values()) {
            assertThat(error.code()).isEqualTo(error.name());
            assertThat(error.category()).as(error.name()).isEqualTo(EXPECTED.get(error.name()));
        }
    }

    @Test
    void alreadyExistsIsAConflict() {
        assertThat(SynapseErrors.ALREADY_EXISTS.category()).isEqualTo(ErrorCategory.CONFLICT);
        assertThat(SynapseErrors.ALREADY_EXISTS.code()).isEqualTo("ALREADY_EXISTS");
        assertThat(SynapseErrors.ALREADY_EXISTS.messageTemplate())
                .isEqualTo("The resource already exists");
    }

    @Test
    void templatesTakeNoArguments() {
        for (SynapseErrors error : SynapseErrors.values()) {
            assertThat(error.messageTemplate()).doesNotContain("{");
        }
    }

    @Test
    void templatesAreExact() {
        assertThat(SynapseErrors.MALFORMED_REQUEST.messageTemplate()).isEqualTo("The request is malformed");
        assertThat(SynapseErrors.VALIDATION_FAILED.messageTemplate()).isEqualTo("The request is invalid");
        assertThat(SynapseErrors.NOT_FOUND.messageTemplate()).isEqualTo("The resource was not found");
        assertThat(SynapseErrors.UNAUTHENTICATED.messageTemplate()).isEqualTo("Authentication is required");
        assertThat(SynapseErrors.FORBIDDEN.messageTemplate()).isEqualTo("Access is denied");
        assertThat(SynapseErrors.CONFLICT.messageTemplate())
                .isEqualTo("The request conflicts with the current state of the resource");
        assertThat(SynapseErrors.STALE_VERSION.messageTemplate())
                .isEqualTo("The resource was changed by another request");
        assertThat(SynapseErrors.ALREADY_EXISTS.messageTemplate()).isEqualTo("The resource already exists");
        assertThat(SynapseErrors.PRECONDITION_FAILED.messageTemplate()).isEqualTo("A precondition failed");
        assertThat(SynapseErrors.PRECONDITION_REQUIRED.messageTemplate())
                .isEqualTo("The request must be conditional");
        assertThat(SynapseErrors.RATE_LIMITED.messageTemplate()).isEqualTo("Too many requests");
        assertThat(SynapseErrors.UNAVAILABLE.messageTemplate()).isEqualTo("The service is unavailable");
        assertThat(SynapseErrors.DEADLINE_EXCEEDED.messageTemplate()).isEqualTo("The deadline expired");
        assertThat(SynapseErrors.INTERNAL.messageTemplate()).isEqualTo("An internal error occurred");
    }
}
