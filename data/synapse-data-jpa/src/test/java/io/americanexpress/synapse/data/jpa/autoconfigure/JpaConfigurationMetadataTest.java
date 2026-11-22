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
package io.americanexpress.synapse.data.jpa.autoconfigure;

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
 * Minor 8: the configuration metadata describes every {@code synapse.data.stores.*.jpa.*} key a JPA store binds, so
 * the docs site's property table lists them.
 */
class JpaConfigurationMetadataTest {

    private static final String METADATA = "META-INF/spring-configuration-metadata.json";

    @Test
    void describesEveryStoreJpaKey() throws IOException {
        List<Map<String, Object>> properties = JsonPath.read(jpaMetadata(), "$.properties");
        assertThat(properties)
                .extracting(property -> property.get("name"))
                .containsExactlyInAnyOrder("synapse.data.stores.*.jpa.properties",
                        "synapse.data.stores.*.jpa.mapping-resources",
                        "synapse.data.stores.*.jpa.database-platform", "synapse.data.stores.*.jpa.database",
                        "synapse.data.stores.*.jpa.show-sql", "synapse.data.stores.*.jpa.generate-ddl",
                        "synapse.data.stores.*.jpa.hibernate.ddl-auto",
                        "synapse.data.stores.*.jpa.hibernate.naming.physical-strategy",
                        "synapse.data.stores.*.jpa.hibernate.naming.implicit-strategy");
        assertThat(properties)
                .allSatisfy(property -> assertThat(String.valueOf(property.get("description")))
                        .as(String.valueOf(property.get("name")))
                        .doesNotContain("{@")
                        .matches("[A-Z].*\\."));
    }

    private static String jpaMetadata() throws IOException {
        for (URL url : Collections
                .list(JpaConfigurationMetadataTest.class.getClassLoader().getResources(METADATA))) {
            try (InputStream in = url.openStream()) {
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                if (json.contains("\"synapse.data.stores.*.jpa.properties\"")) {
                    return json;
                }
            }
        }
        throw new IllegalStateException("synapse-data-jpa configuration metadata not found");
    }
}
