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

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

class SynapseBaggageFieldsTest {

    @Test
    void correlationIdThenBothAttributeMaps() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("synapse.api.grpc.context.metadata.channel", "x-channel");
        properties.put("synapse.api.rest.context.headers.tenant", "X-Tenant");
        properties.put("synapse.api.grpc.context.metadata.tenant", "x-tenant");

        assertThat(SynapseBaggageFields.of(new Binder(new MapConfigurationPropertySource(properties))))
                .containsExactly("correlationId", "tenant", "channel");
    }

    @Test
    void onlyCorrelationIdWithoutAttributes() {
        assertThat(SynapseBaggageFields.of(new Binder(new MapConfigurationPropertySource(Map.of()))))
                .containsExactly("correlationId");
    }

    @Test
    void attributeMapsAreTheRestThenTheGrpcKeys() {
        assertThat(SynapseBaggageFields.ATTRIBUTE_MAPS)
                .containsExactly("synapse.api.rest.context.headers", "synapse.api.grpc.context.metadata");
    }
}
