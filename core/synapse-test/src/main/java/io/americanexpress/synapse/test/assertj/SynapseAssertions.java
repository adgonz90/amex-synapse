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

import org.assertj.core.api.Assertions;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

import io.americanexpress.synapse.core.error.SynapseException;

/**
 * Entry points for Synapse assertions.
 */
public final class SynapseAssertions {

    private SynapseAssertions() {
    }

    /**
     * Runs the code and asserts that it throws a {@link SynapseException}.
     *
     * @param code the code under test
     * @return assertions on the thrown exception
     * @throws AssertionError if nothing, or something other than a {@code SynapseException}, is thrown
     */
    public static SynapseExceptionAssert assertThatSynapseException(ThrowingCallable code) {
        Throwable thrown = Assertions.catchThrowable(code);
        if (thrown == null) {
            throw new AssertionError("Expected a SynapseException but nothing was thrown");
        }
        if (!(thrown instanceof SynapseException failure)) {
            throw new AssertionError("Expected a SynapseException but was " + thrown.getClass().getName(), thrown);
        }
        return new SynapseExceptionAssert(failure);
    }

    /**
     * Asserts on an existing exception.
     *
     * @param actual the exception
     * @return assertions on it
     */
    public static SynapseExceptionAssert assertThat(SynapseException actual) {
        return new SynapseExceptionAssert(actual);
    }
}
