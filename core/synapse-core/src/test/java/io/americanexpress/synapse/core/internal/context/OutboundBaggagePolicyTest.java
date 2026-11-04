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
package io.americanexpress.synapse.core.internal.context;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutboundBaggagePolicyTest {

    @Test
    void alwaysKeepsCorrelationId() {
        assertThat(OutboundBaggagePolicy.allowing(Set.of("tenant")).keptFields())
                .isEqualTo(Set.of("correlationId", "tenant"));
    }

    @Test
    void headersToRemoveAreRemoteFieldsNotKept() {
        assertThat(OutboundBaggagePolicy
                .allowing(Set.of("tenant"))
                .headersToRemove(List.of("tenant", "secret", "correlationId"))).isEqualTo(Set.of("secret"));
    }

    @Test
    void headersToRemoveCompareCaseInsensitively() {
        assertThat(OutboundBaggagePolicy.allowing(Set.of("tenant")).headersToRemove(List.of("TENANT", "Secret")))
                .isEqualTo(Set.of("Secret"));
    }

    @Test
    void filtersTheBaggageHeaderToKeptFields() {
        OutboundBaggagePolicy policy = OutboundBaggagePolicy.allowing(Set.of("tenant"));
        assertThat(policy.filterBaggageHeader("correlationId=c,secret=s,tenant=t"))
                .isEqualTo("correlationId=c,tenant=t");
        assertThat(policy.filterBaggageHeader("secret=s")).isNull();
        assertThat(policy.filterBaggageHeader(null)).isNull();
    }
}
