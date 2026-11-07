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
package io.americanexpress.synapse.samples.books;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole service in the {@code local} environment, with the sample's own {@code application-local.yaml} and no test
 * property: that file turns the OpenAPI document on, which is off by default.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Tag("smoke")
@ActiveProfiles("local")
class BooksLocalEnvironmentIT {

    @Autowired
    RestTestClient client;

    @Test
    void applicationLocalYamlServesTheApiDocs() {
        var result = client.get().uri("/v3/api-docs").exchange().returnResult(String.class);

        assertThat(result.getStatus().value()).isEqualTo(200);
        JsonNode docs = JsonMapper.builder().build().readTree(String.valueOf(result.getResponseBody()));
        assertThat(docs.get("openapi").asString()).isEqualTo("3.1.0");
    }
}
