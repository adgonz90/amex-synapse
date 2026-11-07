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
package io.americanexpress.synapse.test.grpc.byname;

import java.util.List;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Event;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.test.grpc.SynapseGrpcTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * A {@code @SynapseGrpcTest} whose bindings use {@code .bindAllByName()} binds each method to the test double of the
 * operation named after it. A method whose operation has no test double fails startup, and the action says to declare
 * one. The check that names missing operations before startup cannot see these operations: they are not parameters of
 * the bindings' {@code @Bean} method.
 *
 * <p>
 * The failing test class, {@link WithoutCountItemsMock}, is launched with the JUnit {@link EngineTestKit}, as in
 * {@code MissingGrpcMockIT}.
 */
class UnboundMethodIT {

    @Test
    void unboundMethodNamesTheMockToDeclare() {
        EngineExecutionResults results = EngineTestKit
                .engine("junit-jupiter")
                .selectors(selectClass(WithoutCountItemsMock.class))
                .configurationParameter("junit.jupiter.conditions.deactivate", "org.junit.*DisabledCondition")
                .execute();

        results.testEvents().assertStatistics(stats -> stats.started(1).failed(1));
        List<Throwable> failures = results
                .testEvents()
                .failed()
                .stream()
                .map(UnboundMethodIT::failure)
                .toList();
        // GetStock, the service's first method, is bound to the GetStockOperation double; CountItems is the failure.
        assertThat(failures)
                .singleElement()
                .satisfies(failure -> assertThat(failure)
                        .rootCause()
                        .isInstanceOfSatisfying(SynapseConfigurationException.class, e -> {
                            assertThat(e.description())
                                    .isEqualTo("gRPC method synapse.slice.v1.InventoryService/CountItems"
                                            + " (count-items): no operation is bound");
                            assertThat(e.action())
                                    .isEqualTo("Bind it with .unary(...), or add .bindAllByName() and an operation"
                                            + " named count-items. In @SynapseGrpcTest, declare a @MockitoBean for"
                                            + " it.");
                        }));
    }

    private static Throwable failure(Event event) {
        return event.getRequiredPayload(TestExecutionResult.class).getThrowable().orElseThrow();
    }

    @SynapseGrpcTest
    @Disabled("Launched by UnboundMethodIT through the JUnit EngineTestKit: its context fails to start on purpose")
    static class WithoutCountItemsMock {

        @MockitoBean
        GetStockOperation getStock;

        @Test
        void startsTheSlice() {
            // The context fails before this runs.
        }
    }
}
