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

import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.api.rest.RestErrors;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.ErrorDetails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestErrorStatusesTest {

    private static ErrorDetails of(String code, ErrorCategory category) {
        return new ErrorDetails() {
            @Override
            public String code() {
                return code;
            }

            @Override
            public String messageTemplate() {
                return code;
            }

            @Override
            public ErrorCategory category() {
                return category;
            }
        };
    }

    @Test
    void perOperationBeatsGlobalBeatsCategory() {
        RestErrorStatuses statuses = new RestErrorStatuses(Map.of("BOOK_NOT_FOUND", 423),
                Map.of("get-book", Map.of("BOOK_NOT_FOUND", 410)));
        ErrorDetails notFound = BookErrors.BOOK_NOT_FOUND;

        assertThat(statuses.statusFor(notFound, "get-book")).isEqualTo(410);
        assertThat(statuses.statusFor(notFound, "other")).isEqualTo(423);
        assertThat(statuses.statusFor(notFound, null)).isEqualTo(423);
        assertThat(statuses.statusFor(of("UNKNOWN", ErrorCategory.NOT_FOUND), "get-book")).isEqualTo(404);
    }

    @Test
    void restErrorsUseTheirFixedStatus() {
        assertThat(new RestErrorStatuses(Map.of(), Map.of()).statusFor(RestErrors.METHOD_NOT_ALLOWED, null))
                .isEqualTo(405);
    }

    @Test
    void categoryTable() {
        RestErrorStatuses statuses = new RestErrorStatuses(Map.of(), Map.of());
        Map<ErrorCategory, Integer> expected = Map
                .of(ErrorCategory.INVALID_INPUT, 400, ErrorCategory.UNAUTHENTICATED,
                        401, ErrorCategory.FORBIDDEN, 403, ErrorCategory.NOT_FOUND, 404, ErrorCategory.CONFLICT, 409,
                        ErrorCategory.PRECONDITION_FAILED, 412, ErrorCategory.RATE_LIMITED, 429,
                        ErrorCategory.UNAVAILABLE, 503,
                        ErrorCategory.TIMEOUT, 504, ErrorCategory.INTERNAL, 500);
        for (ErrorCategory category : ErrorCategory.values()) {
            assertThat(statuses.statusFor(of("X", category), null))
                    .as(category.name())
                    .isEqualTo(expected.get(category));
        }
    }

    @Test
    void rejectsStatusOutsideErrorRange() {
        assertThatThrownBy(() -> new RestErrorStatuses(Map.of("X", 200), Map.of()))
                .isInstanceOf(SynapseConfigurationException.class)
                .hasMessageContaining("synapse.api.rest.errors.status.[X]");
        assertThatThrownBy(() -> new RestErrorStatuses(Map.of(), Map.of("op", Map.of("Y", 600))))
                .isInstanceOf(SynapseConfigurationException.class)
                .hasMessageContaining("synapse.api.rest.operations.op.errors.status.[Y]");
    }

    @Test
    void acceptsBoundaryStatuses() {
        RestErrorStatuses statuses = new RestErrorStatuses(Map.of("A", 400, "B", 599), Map.of());

        assertThat(statuses.statusFor(of("A", ErrorCategory.INTERNAL), null)).isEqualTo(400);
        assertThat(statuses.statusFor(of("B", ErrorCategory.INTERNAL), null)).isEqualTo(599);
    }
}
