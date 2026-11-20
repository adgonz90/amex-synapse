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
package io.americanexpress.synapse.core.internal.pipeline;

import java.util.List;
import java.util.Set;
import jakarta.validation.ConstraintViolationException;

import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.CardLabel;

import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.SECRET;
import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.VALIDATOR;
import static org.assertj.core.api.Assertions.assertThat;

class SanitizedViolationsTest {

    private static ConstraintViolationException card() {
        return new ConstraintViolationException(VALIDATOR.validate(new CardLabel(SECRET)));
    }

    @Test
    void namesPathsAndTemplatesNeverTheInterpolatedMessage() {
        ConstraintViolationException violations = card();

        SanitizedViolations sanitized = SanitizedViolations.replacing(violations, violations);

        assertThat(sanitized)
                .hasMessage("ConstraintViolationException: number: ${validatedValue} is too long")
                .hasNoCause();
        assertThat(sanitized.violations()).isEqualTo("number: ${validatedValue} is too long");
        assertThat(sanitized.getStackTrace()).isEqualTo(violations.getStackTrace());
        assertThat(sanitized.toString()).doesNotContain(SECRET);
    }

    @Test
    void aRejectionListsTemplatesThenThePipelinesOwnTexts() {
        SanitizedViolations sanitized = SanitizedViolations
                .ofRejection(VALIDATOR.validate(new CardLabel(SECRET)), List.of("page.size: must be at least 1"));

        assertThat(sanitized).hasMessage("number: ${validatedValue} is too long, page.size: must be at least 1");
        assertThat(sanitized.getStackTrace()).isEmpty();
    }

    @Test
    void findsViolationsInTheCauseChainOnlyToItsDepth() {
        ConstraintViolationException violations = card();
        Throwable shallow = new IllegalStateException("outer", new IllegalArgumentException("inner", violations));
        Throwable deep = violations;
        for (int i = 0; i < SanitizedViolations.MAX_DEPTH; i++) {
            deep = new IllegalStateException("wrapper " + i, deep);
        }

        assertThat(SanitizedViolations.find(shallow)).isSameAs(violations);
        assertThat(SanitizedViolations.find(deep)).isNull();
        assertThat(SanitizedViolations.replacing(shallow, violations))
                .hasMessageStartingWith("IllegalStateException > IllegalArgumentException > ")
                .hasMessageContaining("ConstraintViolationException: number: ");
    }

    @Test
    void aCauseCycleEnds() {
        IllegalStateException first = new IllegalStateException("first");
        IllegalStateException second = new IllegalStateException("second", first);
        first.initCause(second);

        assertThat(SanitizedViolations.find(first)).isNull();
        assertThat(SanitizedViolations.in(first)).isNull();
    }

    @Test
    void anExceptionWithoutViolationsNamesNone() {
        ConstraintViolationException none = new ConstraintViolationException("none", Set.of());

        assertThat(SanitizedViolations.replacing(none, none)).hasMessage("ConstraintViolationException: ");
    }
}
