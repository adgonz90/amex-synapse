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

import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole service on a real server, as it runs in production: no profile is active, so problem responses carry no
 * {@code detail}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Tag("smoke")
class BooksApplicationIT {

    private static final String DUNE = "/books/00000000-0000-0000-0000-000000000001";

    @Autowired
    RestTestClient client;

    @Test
    void servesASeededBook() {
        client
                .get()
                .uri(DUNE)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.title")
                .isEqualTo("Dune")
                .jsonPath("$.author")
                .isEqualTo("Frank Herbert");
    }

    @Test
    void echoesTheCorrelationId() {
        client
                .get()
                .uri(DUNE)
                .header("X-Correlation-Id", "it-correlation-1")
                .exchange()
                .expectHeader()
                .valueEquals("X-Correlation-Id", "it-correlation-1");
    }

    @Test
    void createsABookAtItsLocationThenDeletesIt() {
        var created = client
                .post()
                .uri("/books")
                .header("X-Channel", "it")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"isbn\":\"isbn-it-1\",\"title\":\"The Dispossessed\",\"author\":\"Ursula K. Le Guin\"}")
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody()
                .jsonPath("$.addedVia")
                .isEqualTo("it")
                .returnResult();
        String location = Objects.requireNonNull(created.getResponseHeaders().getLocation()).toString();
        assertThat(location).matches("/books/[0-9a-f-]{36}");

        client
                .get()
                .uri(location)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.title")
                .isEqualTo("The Dispossessed");
        client.delete().uri(location).exchange().expectStatus().isNoContent().expectBody().isEmpty();
        client.get().uri(location).exchange().expectStatus().isNotFound();
    }

    @Test
    void anInvalidBookIsValidationFailedWithViolations() {
        client
                .post()
                .uri("/books")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"isbn\":\"\",\"title\":\" \",\"author\":\"Nobody\",\"year\":-1}")
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("VALIDATION_FAILED")
                .jsonPath("$.violations[*].field")
                .isEqualTo(List.of("isbn", "title", "year"))
                .jsonPath("$.detail")
                .doesNotExist();
    }

    @Test
    void anUnknownBookIsBookNotFoundWithoutDetailInProduction() {
        client
                .get()
                .uri("/books/00000000-0000-0000-0000-000000000099")
                .header("X-Correlation-Id", "it-correlation-2")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("BOOK_NOT_FOUND")
                .jsonPath("$.correlationId")
                .isEqualTo("it-correlation-2")
                .jsonPath("$.detail")
                .doesNotExist();
    }

    @Test
    void aDuplicateIsbnIsAConflict() {
        client
                .post()
                .uri("/books")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"isbn\":\"978-0441172719\",\"title\":\"Dune\",\"author\":\"Frank Herbert\"}")
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("DUPLICATE_ISBN");
    }

    @Test
    void anotherMethodIs405WithAllow() {
        var result = client
                .method(HttpMethod.PATCH)
                .uri(DUNE)
                .exchange()
                .expectStatus()
                .isEqualTo(405)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("METHOD_NOT_ALLOWED")
                .returnResult();

        assertThat(result.getResponseHeaders().getAllow())
                .containsExactly(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.PUT, HttpMethod.DELETE,
                        HttpMethod.OPTIONS);
    }

    @Test
    void theTotalIsInAHeaderAlsoOnHead() {
        client.head().uri("/books/stats").exchange().expectStatus().isOk().expectHeader().exists("X-Books-Total");
    }

    @Test
    void aBodyOverTheLimitIs413() {
        client
                .post()
                .uri("/books")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"title\":\"" + "x".repeat(20_000) + "\"}")
                .exchange()
                .expectStatus()
                .isEqualTo(413)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("PAYLOAD_TOO_LARGE");
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
