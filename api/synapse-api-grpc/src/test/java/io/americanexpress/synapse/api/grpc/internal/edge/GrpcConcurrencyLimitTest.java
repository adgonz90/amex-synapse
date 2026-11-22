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

import io.grpc.health.v1.HealthGrpc;
import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.api.grpc.fixture.proto.CatalogServiceGrpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GrpcConcurrencyLimitTest {

    @Test
    void healthAndReflectionAreUncounted() {
        assertThat(GrpcConcurrencyLimit.isUncounted("grpc.health.v1.Health")).isTrue();
        assertThat(GrpcConcurrencyLimit.isUncounted(HealthGrpc.SERVICE_NAME)).isTrue();
        assertThat(GrpcConcurrencyLimit.isUncounted("grpc.reflection.v1.ServerReflection")).isTrue();
        assertThat(GrpcConcurrencyLimit.isUncounted("grpc.reflection.v1alpha.ServerReflection")).isTrue();

        assertThat(GrpcConcurrencyLimit.isUncounted(CatalogServiceGrpc.SERVICE_NAME)).isFalse();
        assertThat(GrpcConcurrencyLimit.isUncounted("grpc.health.v1.HealthCheck")).isFalse();
        assertThat(GrpcConcurrencyLimit.isUncounted("GRPC.HEALTH.V1.HEALTH")).isFalse();
    }

    @Test
    void admitsUpToTheLimitAndAgainAfterARelease() {
        GrpcConcurrencyLimit limit = new GrpcConcurrencyLimit(2);

        assertThat(limit.tryAcquire()).isTrue();
        assertThat(limit.tryAcquire()).isTrue();
        assertThat(limit.tryAcquire()).isFalse();

        limit.release();

        assertThat(limit.tryAcquire()).isTrue();
        assertThat(limit.tryAcquire()).isFalse();
    }

    @Test
    void rejectsALimitBelowOne() {
        assertThatThrownBy(() -> new GrpcConcurrencyLimit(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxConcurrentCalls must be at least 1 but was 0");
    }
}
