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
package io.americanexpress.synapse.samples.bookstore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.test.data.StoreConnection;
import io.americanexpress.synapse.test.data.SynapseContainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bookstore as operations see it, on the JVM in the {@code test} environment: readiness at {@code /health}, CORS,
 * the OpenAPI document, the audit user and the startup report. Problem detail is off, as in every deployed
 * environment. Each nested class has its own context; {@link StoreDown} stops its database, so it has its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"spring.grpc.server.port=0",
        "synapse.errors.include-detail=false", "management.endpoint.health.show-components=always"})
@AutoConfigureRestTestClient
@ActiveProfiles("test")
class BookstoreOperationalIT {

    /** The front end that application-local.yaml allows. */
    static final String LOCAL_FRONT_END = "http://localhost:3000";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The service with CORS on for the local front end, as application-local.yaml turns it on. */
    @Nested
    @ExtendWith(OutputCaptureExtension.class)
    @TestPropertySource(properties = "synapse.api.rest.cors.allowed-origins=" + LOCAL_FRONT_END)
    class Running {

        @StoreConnection("books")
        static final PostgreSQLContainer BOOKS = SynapseContainers.postgres();

        @Autowired
        RestTestClient client;

        @Autowired
        @Qualifier("books")
        JdbcClient jdbc;

        @Autowired
        ConfigurableEnvironment environment;

        /**
         * Acceptance 15. {@code /health} is the readiness group on the server's port ({@code additional-path} in
         * application.yaml): Boot's {@code readinessState}, and the books store, which opts in with
         * {@code health.readiness: true} in data-books' config.yaml.
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
            assertThat(health.at("/components/db/components/books/status").asString()).isEqualTo("UP");
            assertThat(health.at("/components/readinessState/status").asString()).isEqualTo("UP");
        }

        @Test
        void corsPreflightForTheConfiguredOrigin() {
            client
                    .method(HttpMethod.OPTIONS)
                    .uri("/books/{id}", UUID.randomUUID())
                    .header(HttpHeaders.ORIGIN, LOCAL_FRONT_END)
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type, If-Match")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectHeader()
                    .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, LOCAL_FRONT_END);

            JsonNode rejected = json(client
                    .method(HttpMethod.OPTIONS)
                    .uri("/books/{id}", UUID.randomUUID())
                    .header(HttpHeaders.ORIGIN, "https://evil.example")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT")
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectHeader()
                    .doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)
                    .expectBody(String.class)
                    .returnResult()
                    .getResponseBody());
            assertThat(rejected.get("code").asString()).isEqualTo("CORS_REJECTED");
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
            String isbn = "978-" + ThreadLocalRandom.current().nextLong(1_000_000_000_000L, 10_000_000_000_000L);
            JsonNode book = json(client
                    .post()
                    .uri("/books")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"isbn\": \"" + isbn + "\", \"title\": \"Tehanu\", \"author\": \"Ursula K. Le Guin\"}")
                    .exchange()
                    .expectStatus()
                    .isCreated()
                    .expectBody(String.class)
                    .returnResult()
                    .getResponseBody());

            UUID id = UUID.fromString(book.get("id").asString());
            assertThat(jdbc.sql("SELECT created_by FROM book WHERE id = ?").param(id).query(String.class).single())
                    .isEqualTo("system");
            assertThat(jdbc.sql("SELECT modified_by FROM book WHERE id = ?").param(id).query(String.class).single())
                    .isEqualTo("system");
        }

        /**
         * data-books ships its settings in its jar, and the service references none of them: the environment has the
         * module's {@code config.yaml} as a source of its own, and the startup report lists it with the jar it came
         * from. The report names each source by its file and origin, never a value.
         */
        @Test
        void startupReportNamesModuleOrigins(CapturedOutput output) {
            List<String> sources = new ArrayList<>();
            for (PropertySource<?> source : environment.getPropertySources()) {
                sources.add(source.getName());
            }
            assertThat(sources).anyMatch(name -> name.startsWith("Synapse module config [config.yaml] data-books"));

            assertThat(output.getAll())
                    .contains("Synapse environment: test (")
                    // The test logs are ECS JSON, one object per line, so the report's line breaks are the two
                    // characters \n inside its message. Plain-text logs would need a real line break here.
                    .containsPattern(Pattern.compile("\\\\n {2}\\d+\\. config\\.yaml +data-books"));
        }

        /** The OpenAPI document on, as application-local.yaml turns it on. */
        @Nested
        @TestPropertySource(properties = "springdoc.api-docs.enabled=true")
        class WithApiDocs {

            @Autowired
            RestTestClient docsClient;

            /**
             * Every route of BookstoreRestConfiguration, one operation each. The writes' versions aren't
             * {@code @Nullable}, so PUT and DELETE require {@code If-Match} and document 428. The document is saved
             * to {@code target/api-docs-jvm.json}, to compare with other runs of the same jar.
             */
            @Test
            void apiDocsListEveryRoute() throws Exception {
                String body = docsClient
                        .get()
                        .uri("/v3/api-docs")
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(String.class)
                        .returnResult()
                        .getResponseBody();
                Files.writeString(Path.of("target", "api-docs-jvm.json"), String.valueOf(body), StandardCharsets.UTF_8);
                JsonNode paths = json(body).get("paths");

                assertThat(paths.propertyNames()).containsExactlyInAnyOrder("/books", "/books/{id}");
                assertThat(paths.get("/books").propertyNames()).containsExactlyInAnyOrder("get", "post");
                assertThat(paths.get("/books/{id}").propertyNames()).containsExactlyInAnyOrder("get", "put", "delete");
                for (String method : List.of("put", "delete")) {
                    JsonNode operation = paths.get("/books/{id}").get(method);
                    assertThat(ifMatch(operation).get("required").asBoolean()).as(method).isTrue();
                    assertThat(operation.get("responses").propertyNames()).as(method).contains("412", "428");
                }
            }

            private static JsonNode ifMatch(JsonNode operation) {
                for (JsonNode parameter : operation.get("parameters")) {
                    if (parameter.get("name").asString().equals("If-Match")) {
                        return parameter;
                    }
                }
                throw new AssertionError("no If-Match parameter in " + operation);
            }
        }
    }

    /** The books store's database stops: the store opted in to readiness, so the service is not ready. */
    @Nested
    class StoreDown {

        /** Not the shared container: this one is stopped. */
        @StoreConnection("books")
        static final PostgreSQLContainer BOOKS = new PostgreSQLContainer(SynapseContainers.POSTGRES_IMAGE);

        @Autowired
        RestTestClient client;

        @Test
        void readinessDownWhenTheStoreIsDown() {
            client.get().uri("/health").exchange().expectStatus().isOk();

            BOOKS.stop();

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
            assertThat(health.at("/components/db/components/books/status").asString()).isEqualTo("DOWN");
            assertThat(health.at("/components/readinessState/status").asString()).isEqualTo("UP");
        }
    }

    private static JsonNode json(@Nullable String body) {
        return MAPPER.readTree(String.valueOf(body));
    }
}
