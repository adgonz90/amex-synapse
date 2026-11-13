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
package io.americanexpress.synapse.samples.hello;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The whole service on a real server in the {@code test} environment, which sets nothing, so it checks Synapse's
 * defaults: problem responses carry no {@code detail}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
class HelloApplicationIT {

    @Autowired
    RestTestClient client;

    @Test
    void servesTheGreetingWithACorrelationId() {
        client
                .get()
                .uri("/greetings/Ada")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .exists("X-Correlation-Id")
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("Hello, Ada!");
    }

    @Test
    void tooLongNameIsAProblem() {
        client
                .get()
                .uri("/greetings/{name}", "a".repeat(41))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("VALIDATION_FAILED")
                .jsonPath("$.violations[0].field")
                .isEqualTo("name")
                .jsonPath("$.detail")
                .doesNotExist();
    }

    @Test
    void unknownRouteIsRouteNotFound() {
        client
                .get()
                .uri("/nope")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("ROUTE_NOT_FOUND");
    }

    @Test
    void healthIsUp() {
        client
                .get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("UP");
    }
}
