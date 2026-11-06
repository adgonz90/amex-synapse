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
package io.americanexpress.synapse.core.autoconfigure;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the configuration metadata the docs site builds its property tables from.
 */
class ConfigurationMetadataTest {

    private static final String METADATA = "META-INF/spring-configuration-metadata.json";

    @Test
    void describesEverySynapsePropertyInPlainText() throws IOException {
        List<Map<String, Object>> properties = JsonPath.read(synapseMetadata(), "$.properties");
        List<Map<String, Object>> synapse = properties
                .stream()
                .filter(property -> String.valueOf(property.get("name")).startsWith("synapse."))
                .toList();
        assertThat(synapse).isNotEmpty().allSatisfy(property -> {
            String description = String.valueOf(property.get("description"));
            assertThat(description)
                    .as(String.valueOf(property.get("name")))
                    .doesNotContain("{@")
                    .matches("[A-Z].*");
        });
    }

    @Test
    void describesPerOperationPayloadLogging() throws IOException {
        List<Map<String, Object>> entries = JsonPath
                .read(synapseMetadata(),
                        "$.properties[?(@.name == 'synapse.operations.*.logging.payloads')]");
        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry).containsEntry("type", "java.lang.Boolean").containsEntry("defaultValue", false);
            assertThat(String.valueOf(entry.get("description"))).startsWith("Whether");
        });
    }

    @Test
    void hasNoEnvironmentProperties() throws IOException {
        List<String> names = JsonPath.read(synapseMetadata(), "$.properties[*].name");
        assertThat(names).noneMatch(name -> name.startsWith("synapse.environment."));
    }

    private static String synapseMetadata() throws IOException {
        for (URL url : Collections.list(ConfigurationMetadataTest.class.getClassLoader().getResources(METADATA))) {
            try (InputStream in = url.openStream()) {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                if (json.contains("\"synapse.errors.include-detail\"")) {
                    return json;
                }
            }
        }
        throw new IllegalStateException("Synapse core configuration metadata not found");
    }
}
