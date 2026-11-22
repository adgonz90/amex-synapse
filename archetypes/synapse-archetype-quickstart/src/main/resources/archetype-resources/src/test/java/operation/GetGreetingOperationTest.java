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
package ${package}.operation;

import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.test.operation.SynapseOperations;

import ${package}.model.GetGreetingRequest;
import ${package}.model.Greeting;

import static io.americanexpress.synapse.test.assertj.SynapseAssertions.assertThatSynapseException;
import static org.assertj.core.api.Assertions.assertThat;

/** The operation with the real pipeline (validation, errors), without Spring or a web server. */
class GetGreetingOperationTest {

    private final GetGreetingOperation operation = new GetGreetingOperation();

    @Test
    void greetsByName() {
        Greeting greeting = SynapseOperations.invoke(operation, new GetGreetingRequest("Ada"));

        assertThat(greeting.message()).isEqualTo("Hello, Ada!");
    }

    @Test
    void blankNameIsInvalidInput() {
        assertThatSynapseException(() -> SynapseOperations.invoke(operation, new GetGreetingRequest(" ")))
                .hasCategory(ErrorCategory.INVALID_INPUT)
                .hasViolation("name");
    }
}
