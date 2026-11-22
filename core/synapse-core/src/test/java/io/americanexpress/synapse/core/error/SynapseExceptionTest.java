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

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static io.americanexpress.synapse.core.error.ErrorCategory.NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SynapseExceptionTest {

    enum TestErrors implements ErrorDetails {
        BOOK_NOT_FOUND("Book {0} not found", NOT_FOUND),
        QUOTED("Can''t find book {0}", NOT_FOUND),
        BROKEN("Book {0 not found", NOT_FOUND),
        NO_ARGS("Nothing here", NOT_FOUND);

        private final String template;

        private final ErrorCategory category;

        TestErrors(String template, ErrorCategory category) {
            this.template = template;
            this.category = category;
        }

        @Override
        public String code() {
            return name();
        }

        @Override
        public String messageTemplate() {
            return template;
        }

        @Override
        public ErrorCategory category() {
            return category;
        }
    }

    @Test
    void formatsTemplateWithArgsAsGiven() {
        assertThat(new SynapseException(TestErrors.BOOK_NOT_FOUND, 12345).getMessage())
                .isEqualTo("Book 12345 not found");
    }

    @Test
    void doubledApostropheIsLiteral() {
        assertThat(new SynapseException(TestErrors.QUOTED, 7).getMessage()).isEqualTo("Can't find book 7");
    }

    @Test
    void malformedTemplateFallsBackToTemplate() {
        assertThat(new SynapseException(TestErrors.BROKEN, 1).getMessage()).isEqualTo("Book {0 not found");
    }

    @Test
    void nullArgIsPrintedAsNull() {
        assertThat(new SynapseException(TestErrors.BOOK_NOT_FOUND, (Object) null).getMessage())
                .isEqualTo("Book null not found");
    }

    @Test
    void noArgsLeavesTemplateAsIs() {
        assertThat(new SynapseException(TestErrors.NO_ARGS).getMessage()).isEqualTo("Nothing here");
    }

    @Test
    void exposesCodeCategoryAndCause() {
        var cause = new IllegalStateException("boom");
        var exception = new SynapseException(TestErrors.BOOK_NOT_FOUND, cause, 5);
        assertThat(exception.code()).isEqualTo("BOOK_NOT_FOUND");
        assertThat(exception.category()).isEqualTo(NOT_FOUND);
        assertThat(exception.details()).isSameAs(TestErrors.BOOK_NOT_FOUND);
        assertThat(exception.getCause()).isSameAs(cause);
        assertThat(exception.getMessage()).isEqualTo("Book 5 not found");
        assertThat(exception.args()).containsExactly(5);
    }

    @Test
    void argsAndViolationsAreDefensiveCopies() {
        Object[] array = {"a", "b"};
        var exception = new SynapseException(TestErrors.BOOK_NOT_FOUND, array);
        array[0] = "changed";
        assertThat(exception.args()).containsExactly("a", "b");
        exception.args().clear();
        assertThat(exception.args()).containsExactly("a", "b");

        var violations = new ArrayList<>(List.of(new Violation("f", "m")));
        var withViolations = SynapseException.ofViolations(SynapseErrors.VALIDATION_FAILED, violations);
        violations.add(new Violation("g", "n"));
        assertThat(withViolations.violations()).containsExactly(new Violation("f", "m"));
        assertThatThrownBy(() -> withViolations.violations().add(new Violation("x", "y")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void plainExceptionHasNoViolations() {
        assertThat(new SynapseException(TestErrors.NO_ARGS).violations()).isEmpty();
    }

    @Test
    void ofViolationsKeepsViolationsInOrder() {
        var v1 = new Violation("title", "must not be blank");
        var v2 = new Violation("isbn", "is invalid");
        var exception = SynapseException.ofViolations(SynapseErrors.VALIDATION_FAILED, List.of(v1, v2));
        assertThat(exception.violations()).containsExactly(v1, v2);
        assertThat(exception.code()).isEqualTo("VALIDATION_FAILED");
        assertThat(exception.getMessage()).isEqualTo("The request is invalid");
    }
}
