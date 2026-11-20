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
package io.americanexpress.synapse.api.rest.internal.error;

import java.io.IOException;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.masking.Masked;
import io.americanexpress.synapse.core.operation.BusinessOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = RestErrorsIT.TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "synapse.errors.include-detail=true", "management.logging.export.otlp.enabled=false",
        "management.tracing.export.otlp.enabled=false", "management.otlp.metrics.export.enabled=false"})
@AutoConfigureRestTestClient
@ExtendWith(OutputCaptureExtension.class)
@ActiveProfiles("test")
class RestErrorsIT {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** A value that must never reach the caller or the log. */
    private static final String SECRET = "4111-SECRET-1111";

    @Autowired
    private RestTestClient client;

    private record Result(int status, String contentType, @Nullable String allow, @Nullable String correlationHeader,
            String body) {

        JsonNode json() {
            return MAPPER.readTree(body);
        }
    }

    private Result call(RestTestClient.RequestHeadersSpec<?> spec) {
        var result = spec.exchange().returnResult(String.class);
        return new Result(result.getStatus().value(), String.valueOf(result.getResponseHeaders().getContentType()),
                result.getResponseHeaders().getFirst("Allow"), result.getResponseHeaders().getFirst("X-Correlation-Id"),
                String.valueOf(result.getResponseBody()));
    }

    @Test
    @Tag("smoke")
    void synapseExceptionFromControllerIsProblemJson() {
        Result result = call(client.get().uri("/books/42"));

        assertThat(result.status()).isEqualTo(404);
        assertThat(result.contentType()).startsWith("application/problem+json");
        assertThat(result.json().get("code").asString()).isEqualTo("BOOK_NOT_FOUND");
        assertThat(result.json().get("detail").asString()).isEqualTo("Book 42 was not found");
        assertThat(result.json().get("correlationId").asString()).isNotEmpty();
        assertThat(result.correlationHeader()).isEqualTo(result.json().get("correlationId").asString());
    }

    @Test
    void malformedJsonBodyHasNoExceptionText() {
        Result result = call(client
                .post()
                .uri("/books")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{bad"));

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.json().get("code").asString()).isEqualTo("MALFORMED_REQUEST");
        assertThat(result.body())
                .doesNotContain("tools.jackson")
                .doesNotContain("Exception")
                .doesNotContain("Unexpected character");
    }

    @Test
    void unknownRouteIsRouteNotFound() {
        Result result = call(client.get().uri("/nope"));

        assertThat(result.status()).isEqualTo(404);
        assertThat(result.contentType()).startsWith("application/problem+json");
        assertThat(result.json().get("code").asString()).isEqualTo("ROUTE_NOT_FOUND");
    }

    @Test
    void wrongMethodOnControllerIs405WithAllow() {
        Result result = call(client.delete().uri("/books/42"));

        assertThat(result.status()).isEqualTo(405);
        assertThat(result.json().get("code").asString()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(result.allow()).contains("GET");
    }

    @Test
    void unsupportedMediaTypeIs415() {
        Result result = call(client.post().uri("/books").contentType(MediaType.TEXT_PLAIN).body("hello"));

        assertThat(result.status()).isEqualTo(415);
        assertThat(result.json().get("code").asString()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void notAcceptableIs406() {
        Result result = call(client.get().uri("/books/42").accept(MediaType.APPLICATION_XML));

        assertThat(result.status()).isEqualTo(406);
        assertThat(result.contentType()).startsWith("application/problem+json");
        assertThat(result.json().get("code").asString()).isEqualTo("NOT_ACCEPTABLE");
    }

    @Test
    void repeatedSingleValuedQueryParameterIsOneViolation() {
        Result result = call(client.get().uri("/checked?id=1&id=2"));

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.json().get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(result.json().get("violations")).hasSize(1);
        assertThat(result.json().get("violations").get(0).get("field").asString()).isEqualTo("id");
        assertThat(result.json().get("violations").get(0).get("message").asString())
                .isEqualTo("must have a single value");
    }

    @Test
    void rejectedBusinessInputIsInternalWithoutDetailEvenWithDetailOn(CapturedOutput output) {
        Result result = call(client.get().uri("/cards?number=" + SECRET));

        // The service built the business input: its violation is the service's bug, never the caller's.
        assertThat(result.status()).isEqualTo(500);
        assertThat(result.json().get("code").asString()).isEqualTo("INTERNAL");
        assertThat(result.json().has("violations")).isFalse();
        assertThat(result.json().get("detail").asString()).isEqualTo("An internal error occurred");
        assertThat(result.body()).doesNotContain(SECRET).doesNotContain("number").doesNotContain("too long");
        assertThat(output.getOut())
                .contains("Operation card failed with INTERNAL", "number: ${validatedValue} is too long")
                .doesNotContain(SECRET);
    }

    @Test
    void invalidRequestParameterIsValidationFailed() {
        Result result = call(client.get().uri("/items?limit=xyz-qz"));

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.json().get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(result.json().get("violations").get(0).get("field").asString()).isEqualTo("limit");
        assertThat(result.body()).doesNotContain("Exception").doesNotContain("xyz-qz");
    }

    @Test
    void missingRequestParameterIsValidationFailed() {
        Result result = call(client.get().uri("/items"));

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.json().get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(result.json().get("violations").get(0).get("field").asString()).isEqualTo("limit");
        assertThat(result.json().get("violations").get(0).get("message").asString()).isEqualTo("must not be null");
    }

    @Test
    void malformedFormBodyReadBeforeSpringMvcIsMalformedRequestWithoutAnError(CapturedOutput output) {
        int start = output.getAll().length();

        // Boot's FormContentFilter decodes PUT, PATCH and DELETE form bodies before the DispatcherServlet and lets
        // the failure out of the filter chain.
        Result result = call(
                client.put().uri("/books").contentType(MediaType.APPLICATION_FORM_URLENCODED).body("a=%zz"));

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.contentType()).startsWith("application/problem+json");
        assertThat(result.json().get("code").asString()).isEqualTo("MALFORMED_REQUEST");
        assertThat(result.json().get("correlationId").asString()).isNotEmpty();
        assertThat(result.correlationHeader()).isEqualTo(result.json().get("correlationId").asString());
        assertThat(result.body()).doesNotContain("Exception").doesNotContain("decode");
        // A client error is answered at the edge: no logger logs it as an error, Tomcat's included.
        assertThat(output.getAll().substring(start).lines().filter(line -> line.contains("ERROR"))).isEmpty();
    }

    @Test
    void serverErrorEscapingTheFilterChainStillReachesTheErrorController(CapturedOutput output) {
        int start = output.getAll().length();

        Result result = call(client.get().uri("/filter-boom"));

        assertThat(result.status()).isEqualTo(500);
        assertThat(result.json().get("code").asString()).isEqualTo("INTERNAL");
        assertThat(result.correlationHeader()).isEqualTo(result.json().get("correlationId").asString());
        assertThat(result.body()).doesNotContain("secret filter internals").doesNotContain("Exception");
        // The error controller logs it once, with its stack trace, as before.
        assertThat(output
                .getAll()
                .substring(start)
                .lines()
                .filter(line -> line.contains("ERROR") && line.contains("Unhandled error for GET /filter-boom")))
                .hasSize(1);
    }

    @Test
    void neverAStackTrace() {
        Result result = call(client.get().uri("/boom"));

        assertThat(result.status()).isEqualTo(500);
        assertThat(result.json().get("code").asString()).isEqualTo("INTERNAL");
        assertThat(result.body())
                .doesNotContain("at io.")
                .doesNotContain("RuntimeException")
                .doesNotContain("secret internals");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(BooksController.class)
    static class TestApplication {

        @Bean
        CheckOperation checkOperation() {
            return new CheckOperation();
        }

        @Bean
        CardRule cardRule() {
            return new CardRule();
        }

        @Bean
        CardOperation cardOperation(CardRule cardRule) {
            return new CardOperation(cardRule);
        }

        @Bean
        RestBindings bindings(CheckOperation check, CardOperation card) {
            return RestBindings.builder().get("/checked", check).get("/cards", card).build();
        }

        /** A filter of the application that fails on {@code /filter-boom}, outside Spring MVC. */
        @Bean
        FilterRegistrationBean<OncePerRequestFilter> failingFilter() {
            FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(
                    new OncePerRequestFilter() {
                        @Override
                        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                FilterChain chain) throws ServletException, IOException {
                            if (request.getRequestURI().equals("/filter-boom")) {
                                throw new IllegalStateException("secret filter internals");
                            }
                            chain.doFilter(request, response);
                        }
                    });
            registration.setDispatcherTypes(DispatcherType.REQUEST);
            registration.setOrder(0);
            return registration;
        }
    }

    record CheckRequest(@Nullable Long id) {
    }

    /** A query named {@code check}. */
    static class CheckOperation implements QueryOperation<CheckRequest, String> {

        @Override
        public String execute(CheckRequest input) {
            return "checked " + input.id();
        }
    }

    /** A card number: masked, with a constraint message that would show the rejected value. */
    record Card(@Masked @Size(max = 3, message = "${validatedValue} is too long") String number) {
    }

    /** A business rule on a card, named {@code card-rule}. */
    static class CardRule implements BusinessOperation<Card, String> {

        @Override
        public String execute(Card input) {
            return input.number();
        }
    }

    record CardRequest(String number) {
    }

    /** A query named {@code card}: it passes the caller's number to the rule, which rejects one too long. */
    static class CardOperation implements QueryOperation<CardRequest, String> {

        private final CardRule rule;

        CardOperation(CardRule rule) {
            this.rule = rule;
        }

        @Override
        public String execute(CardRequest input) {
            return rule.execute(new Card(input.number()));
        }
    }

    record Book(String title) {
    }

    @RestController
    static class BooksController {

        @GetMapping(path = "/books/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
        Book get(@org.springframework.web.bind.annotation.PathVariable String id) {
            throw new SynapseException(BookErrors.BOOK_NOT_FOUND, id);
        }

        @PostMapping(path = "/books", consumes = MediaType.APPLICATION_JSON_VALUE)
        Book create(@RequestBody Book book) {
            return book;
        }

        @GetMapping("/items")
        String items(@org.springframework.web.bind.annotation.RequestParam int limit) {
            return "limit " + limit;
        }

        @GetMapping("/boom")
        String boom() {
            throw new RuntimeException("secret internals");
        }
    }
}
