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
package ${package};

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.test.data.StoreConnection;
import io.americanexpress.synapse.test.data.SynapseContainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The service as operations see it, on the JVM in the {@code test} environment: readiness at {@code /health}, the
 * OpenAPI document's safe default, and the audit user. Problem detail is off, as in every deployed environment. Each
 * nested class has its own context; {@link StoreDown} stops its database, so it has its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"spring.grpc.server.port=0",
        "synapse.errors.include-detail=false", "management.endpoint.health.show-components=always"})
@AutoConfigureRestTestClient
@ActiveProfiles("test")
class ServiceOperationalIT {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The service with its store up. */
    @Nested
    class Running {

        @StoreConnection("${serviceName}")
        static final PostgreSQLContainer STORE = SynapseContainers.postgres();

        @Autowired
        RestTestClient client;

        @Autowired
        @Qualifier("${serviceName}")
        JdbcClient jdbc;

        /**
         * {@code /health} is the readiness group on the server's port ({@code additional-path} in application.yaml):
         * Boot's {@code readinessState}, and the ${serviceName} store, which opts in with
         * {@code health.readiness: true} in data-${serviceName}'s config.yaml.
         */
        @Test
        void healthAndReadinessOnAdditionalPath() {
            JsonNode health = json(client
                    .get()
                    .uri("/health")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(String.class)
                    .returnResult()
                    .getResponseBody());

            assertThat(health.get("status").asString()).isEqualTo("UP");
            assertThat(health.get("components").propertyNames()).containsExactlyInAnyOrder("db", "readinessState");
            assertThat(health.at("/components/db/components/${serviceName}/status").asString()).isEqualTo("UP");
            assertThat(health.at("/components/readinessState/status").asString()).isEqualTo("UP");
        }

        @Test
        void apiDocsAreOffByDefault() {
            // Off in every environment unless its file turns it on; only application-local.yaml does.
            JsonNode problem = json(client
                    .get()
                    .uri("/v3/api-docs")
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(String.class)
                    .returnResult()
                    .getResponseBody());

            assertThat(problem.get("code").asString()).isEqualTo("ROUTE_NOT_FOUND");
        }

        /**
         * Nothing signs callers in, so the audit columns record {@code synapse.data.auditing.system-user}, which is
         * {@code system} by default.
         */
        @Test
        void writesRecordTheSystemUser() {
            JsonNode item = json(client
                    .post()
                    .uri("/items")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"name\": \"Item " + UUID.randomUUID() + "\"}")
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody(String.class)
                    .returnResult()
                    .getResponseBody());

            UUID id = UUID.fromString(item.get("id").asString());
            assertThat(jdbc.sql("SELECT created_by FROM item WHERE id = ?").param(id).query(String.class).single())
                    .isEqualTo("system");
            assertThat(jdbc.sql("SELECT modified_by FROM item WHERE id = ?").param(id).query(String.class).single())
                    .isEqualTo("system");
        }
    }

    /** The ${serviceName} store's database stops: the store opted in to readiness, so the service is not ready. */
    @Nested
    class StoreDown {

        /** Not the shared container: this one is stopped. */
        @StoreConnection("${serviceName}")
        static final PostgreSQLContainer STORE = new PostgreSQLContainer(SynapseContainers.POSTGRES_IMAGE);

        @Autowired
        RestTestClient client;

        @Test
        void readinessDownWhenTheStoreIsDown() {
            client.get().uri("/health").exchange().expectStatus().isOk();

            STORE.stop();

            JsonNode health = json(client
                    .get()
                    .uri("/health")
                    .exchange()
                    .expectStatus()
                    .isEqualTo(503)
                    .expectBody(String.class)
                    .returnResult()
                    .getResponseBody());
            assertThat(health.get("status").asString()).isEqualTo("DOWN");
            assertThat(health.at("/components/db/components/${serviceName}/status").asString()).isEqualTo("DOWN");
            assertThat(health.at("/components/readinessState/status").asString()).isEqualTo("UP");
        }
    }

    private static JsonNode json(@Nullable String body) {
        return MAPPER.readTree(String.valueOf(body));
    }
}
