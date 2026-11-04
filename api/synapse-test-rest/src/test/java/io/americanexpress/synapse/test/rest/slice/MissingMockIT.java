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
package io.americanexpress.synapse.test.rest.slice;

import java.util.List;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Event;

import io.americanexpress.synapse.test.rest.SynapseRestTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * A {@code @SynapseRestTest} that does not mock the operations its bindings use fails before any bean is created, and
 * names each missing operation.
 *
 * <p>
 * The failing test class, {@link WithoutOperationMocks}, is a static nested class. Maven's surefire and failsafe leave
 * nested classes out by default ({@code **}{@code /*$*}), and it is also {@link Disabled}, so an IDE or a class-path
 * scan that discovers it skips it. This test launches it with the JUnit {@link EngineTestKit}, deactivating JUnit's
 * {@code DisabledCondition} for that launch only, and inspects the failure.
 */
class MissingMockIT {

    private static final String MESSAGE = "@SynapseRestTest: declare a @MockitoBean for each operation the bindings "
            + "use; missing: GetBookOperation, SearchBooksOperation (bound by BooksRestConfiguration.books)";

    @Test
    void failsFastListingMissingOperations() {
        EngineExecutionResults results = EngineTestKit
                .engine("junit-jupiter")
                .selectors(selectClass(WithoutOperationMocks.class))
                .configurationParameter("junit.jupiter.conditions.deactivate", "org.junit.*DisabledCondition")
                .execute();

        results.testEvents().assertStatistics(stats -> stats.started(1).failed(1));
        List<Throwable> failures = results
                .testEvents()
                .failed()
                .stream()
                .map(MissingMockIT::failure)
                .toList();
        assertThat(failures).singleElement().satisfies(failure -> {
            assertThat(failure)
                    .hasStackTraceContaining("declare a @MockitoBean")
                    .hasStackTraceContaining(
                            "GetBookOperation");
            assertThat(failure).rootCause().isInstanceOf(IllegalStateException.class).hasMessage(MESSAGE);
        });
    }

    private static Throwable failure(Event event) {
        return event.getRequiredPayload(TestExecutionResult.class).getThrowable().orElseThrow();
    }

    @SynapseRestTest
    @Disabled("Launched by MissingMockIT through the JUnit EngineTestKit: its context fails to start on purpose")
    static class WithoutOperationMocks {

        @Test
        void startsTheSlice() {
            // The context fails before this runs.
        }
    }
}
