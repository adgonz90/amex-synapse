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
package io.americanexpress.synapse.api.grpc.internal.edge;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.grpc.Context;
import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.api.grpc.fixture.proto.GetItemRequest;
import io.americanexpress.synapse.api.grpc.internal.mapping.MessageMapping;
import io.americanexpress.synapse.api.grpc.internal.mapping.RecordReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GrpcCallStateTest {

    @Test
    void startsWithTheCandidatesOnly() {
        Map<String, String> baggage = new HashMap<>(Map.of("tenant", "t-1"));
        GrpcCallState state = new GrpcCallState("c-raw", baggage);
        baggage.clear();

        assertThat(state.correlationCandidate()).isEqualTo("c-raw");
        assertThat(state.baggageCandidates()).isEqualTo(Map.of("tenant", "t-1"));
        assertThat(state.correlationId()).isNull();
        assertThat(state.operationName()).isNull();
        assertThat(state.locale()).isNull();
        assertThat(state.fieldPaths()).isEmpty();
        assertThatThrownBy(() -> state.baggageCandidates().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void recordsWhatTheCallResolves() {
        GrpcCallState state = new GrpcCallState(null, Map.of());
        Map<String, String> paths = new HashMap<>(Map.of("publishedOn", "published_on"));

        state.correlationId("c-1");
        state.operationName("get-book");
        state.locale(Locale.FRENCH);
        state.fieldPaths(paths);
        paths.clear();

        assertThat(state.correlationCandidate()).isNull();
        assertThat(state.correlationId()).isEqualTo("c-1");
        assertThat(state.operationName()).isEqualTo("get-book");
        assertThat(state.locale()).isEqualTo(Locale.FRENCH);
        assertThat(state.fieldPaths()).isEqualTo(Map.of("publishedOn", "published_on"));
    }

    @Test
    void currentReadsTheCallContext() {
        GrpcCallState state = new GrpcCallState(null, Map.of());

        assertThat(GrpcCallState.current()).isNull();
        Context
                .current()
                .withValue(GrpcCallState.KEY, state)
                .run(() -> assertThat(GrpcCallState.current()).isSameAs(state));
        assertThat(GrpcCallState.current()).isNull();
    }

    @Test
    void aBindingsFieldPathsAreSharedNotCopiedPerCall() {
        RecordReader reader = MessageMapping.reader(GetItemRequest.getDescriptor(), Id.class, List.of(), List.of());
        GrpcCallState state = new GrpcCallState(null, Map.of());

        state.fieldPaths(reader.fieldPaths());

        assertThat(state.fieldPaths()).isSameAs(reader.fieldPaths()).containsEntry("id", "id");
        assertThatThrownBy(() -> state.fieldPaths().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

    record Id(String id) {
    }
}
