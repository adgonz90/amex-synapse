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
package io.americanexpress.synapse.test.assertj;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;

import static io.americanexpress.synapse.test.assertj.SynapseAssertions.assertThatSynapseException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SynapseAssertionsTest {

    private static SynapseException invalid() {
        return SynapseException
                .ofViolations(SynapseErrors.VALIDATION_FAILED,
                        List.of(new Violation("title", "must not be blank")));
    }

    @Test
    void passingAssertionsChain() {
        assertThatSynapseException(() -> {
            throw invalid();
        })
                .hasCode(SynapseErrors.VALIDATION_FAILED)
                .hasCode("VALIDATION_FAILED")
                .hasCategory(ErrorCategory.INVALID_INPUT)
                .hasViolation("title")
                .hasViolation("title", "must not be blank");
        SynapseAssertions.assertThat(invalid()).hasCode(SynapseErrors.VALIDATION_FAILED);
    }

    @Test
    void assertionFailsWithUsefulMessages() {
        assertThatThrownBy(() -> assertThatSynapseException(() -> {
            throw invalid();
        }).hasCode(SynapseErrors.NOT_FOUND))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("NOT_FOUND")
                .hasMessageContaining("VALIDATION_FAILED");
        assertThatThrownBy(() -> SynapseAssertions.assertThat(invalid()).hasCode("OTHER"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("OTHER");
        assertThatThrownBy(() -> SynapseAssertions.assertThat(invalid()).hasCategory(ErrorCategory.NOT_FOUND))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("NOT_FOUND")
                .hasMessageContaining("INVALID_INPUT");
        assertThatThrownBy(() -> SynapseAssertions.assertThat(invalid()).hasViolation("author"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("author")
                .hasMessageContaining("title");
        assertThatThrownBy(() -> SynapseAssertions.assertThat(invalid()).hasViolation("title", "wrong"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("wrong")
                .hasMessageContaining("must not be blank");
    }

    @Test
    void nothingThrownFails() {
        assertThatThrownBy(() -> assertThatSynapseException(() -> {
        })).isInstanceOf(AssertionError.class).hasMessageStartingWith("Expected a SynapseException");
    }

    @Test
    void anotherExceptionFailsNamingIt() {
        assertThatThrownBy(() -> assertThatSynapseException(() -> {
            throw new IllegalStateException("x");
        }))
                .isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("Expected a SynapseException")
                .hasMessageContaining("IllegalStateException")
                .hasCauseInstanceOf(IllegalStateException.class);
    }
}
