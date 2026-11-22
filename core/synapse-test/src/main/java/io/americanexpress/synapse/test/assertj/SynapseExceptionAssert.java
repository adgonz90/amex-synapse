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

import org.assertj.core.api.AbstractAssert;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;

/**
 * Assertions on a {@link SynapseException}.
 */
public final class SynapseExceptionAssert extends AbstractAssert<SynapseExceptionAssert, SynapseException> {

    SynapseExceptionAssert(SynapseException actual) {
        super(actual, SynapseExceptionAssert.class);
    }

    /**
     * Verifies the error code.
     *
     * @param expected the expected error
     * @return this assertion
     */
    public SynapseExceptionAssert hasCode(ErrorDetails expected) {
        return hasCode(expected.code());
    }

    /**
     * Verifies the error code.
     *
     * @param expected the expected code
     * @return this assertion
     */
    public SynapseExceptionAssert hasCode(String expected) {
        isNotNull();
        if (!actual.code().equals(expected)) {
            failWithMessage("Expected error code <%s> but was <%s>", expected, actual.code());
        }
        return this;
    }

    /**
     * Verifies the error category.
     *
     * @param expected the expected category
     * @return this assertion
     */
    public SynapseExceptionAssert hasCategory(ErrorCategory expected) {
        isNotNull();
        if (actual.category() != expected) {
            failWithMessage("Expected error category <%s> but was <%s>", expected, actual.category());
        }
        return this;
    }

    /**
     * Verifies that a violation names the field.
     *
     * @param field the field name
     * @return this assertion
     */
    public SynapseExceptionAssert hasViolation(String field) {
        isNotNull();
        List<Violation> violations = actual.violations();
        if (violations.stream().noneMatch(violation -> violation.field().equals(field))) {
            failWithMessage("Expected a violation on <%s> but the violations were <%s>", field, violations);
        }
        return this;
    }

    /**
     * Verifies that a violation names the field with the message.
     *
     * @param field the field name
     * @param message the violation message
     * @return this assertion
     */
    public SynapseExceptionAssert hasViolation(String field, String message) {
        isNotNull();
        List<Violation> violations = actual.violations();
        if (!violations.contains(new Violation(field, message))) {
            failWithMessage("Expected a violation on <%s> with message <%s> but the violations were <%s>", field,
                    message, violations);
        }
        return this;
    }
}
