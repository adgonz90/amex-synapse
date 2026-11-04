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
package io.americanexpress.synapse.api.rest.internal.route;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.validation.constraints.NotBlank;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.internal.error.BookErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Serves a fixture service whose only REST configuration is one {@link RestBindings} bean, on a real server.
 */
@SpringBootTest(classes = RestRoutesIT.TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.logging.export.otlp.enabled=false", "management.tracing.export.otlp.enabled=false",
        "management.otlp.metrics.export.enabled=false"})
@AutoConfigureRestTestClient
@ExtendWith(OutputCaptureExtension.class)
class RestRoutesIT {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private RestTestClient client;

    @Autowired
    private GetBookOperation getBook;

    @LocalServerPort
    private int port;

    /** A response: its status, content type, two headers and body. */
    record Result(int status, String contentType, @Nullable String allow, @Nullable String location, String body) {

        JsonNode json() {
            return MAPPER.readTree(body);
        }
    }

    static Result call(RestTestClient.RequestHeadersSpec<?> spec) {
        var result = spec.exchange().returnResult(String.class);
        return new Result(result.getStatus().value(), String.valueOf(result.getResponseHeaders().getContentType()),
                result.getResponseHeaders().getFirst("Allow"), result.getResponseHeaders().getFirst("Location"),
                String.valueOf(result.getResponseBody()));
    }

    @Test
    @Tag("smoke")
    void servesARouteWithNoOtherConfiguration() {
        Result result = call(client.get().uri("/books/1"));

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.contentType()).startsWith("application/json");
        assertThat(result.json().get("id").asLong()).isEqualTo(1);
        assertThat(result.json().get("title").asString()).isEqualTo("Title 1");
    }

    @Test
    void createdAndDeletedFollowTheResponseRules() {
        Result created = call(
                client.post().uri("/books").contentType(MediaType.APPLICATION_JSON).body("{\"title\":\"Dune\"}"));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.location()).isEqualTo("/books/42");
        assertThat(created.json().get("title").asString()).isEqualTo("Dune");

        Result updated = call(
                client.put().uri("/books/5").contentType(MediaType.APPLICATION_JSON).body("{\"title\":\"New\"}"));
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.json().get("id").asLong()).isEqualTo(5);

        Result deleted = call(client.delete().uri("/books/5"));
        assertThat(deleted.status()).isEqualTo(204);
    }

    @Test
    void notFoundIsProblemJson() {
        Result result = call(client.get().uri("/books/404"));

        assertThat(result.status()).isEqualTo(404);
        assertThat(result.contentType()).startsWith("application/problem+json");
        assertThat(result.json().get("code").asString()).isEqualTo("BOOK_NOT_FOUND");
        assertThat(result.json().get("correlationId").asString()).isNotEmpty();
        assertThat(result.json().has("detail")).isFalse();
    }

    @Test
    void validationFailureHasViolations() {
        Result result = call(
                client.post().uri("/books").contentType(MediaType.APPLICATION_JSON).body("{\"title\":\"\"}"));

        assertThat(result.status()).isEqualTo(400);
        assertThat(result.json().get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(result.json().get("violations").get(0).get("field").asString()).isEqualTo("title");
    }

    @Test
    void wrongMethodIs405WithAllow() {
        Result result = call(client.patch().uri("/books/1"));

        assertThat(result.status()).isEqualTo(405);
        assertThat(result.contentType()).startsWith("application/problem+json");
        assertThat(result.json().get("code").asString()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(result.allow()).isEqualTo("GET, HEAD, PUT, DELETE, OPTIONS");

        Result post = call(client.post().uri("/books/1").contentType(MediaType.APPLICATION_JSON).body("{}"));
        assertThat(post.status()).isEqualTo(405);
        assertThat(post.allow()).isEqualTo("GET, HEAD, PUT, DELETE, OPTIONS");
    }

    @Test
    void mostSpecificTemplateWinsOverAnEarlierDeclaredOne() {
        int before = getBook.calls();

        // /books/latest is declared after /books/{id}, which also matches it.
        Result result = call(client.get().uri("/books/latest"));

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.json().get("id").asLong()).isEqualTo(99);
        assertThat(result.json().get("title").asString()).isEqualTo("Latest");
        assertThat(getBook.calls()).isEqualTo(before);
    }

    @Test
    void headIsServedForAGetRoute() {
        var get = client.get().uri("/books/1").exchange().returnResult(String.class);
        int before = getBook.calls();

        var head = client.head().uri("/books/1").exchange().returnResult(String.class);

        assertThat(head.getStatus().value()).isEqualTo(200);
        assertThat(head.getResponseBody()).isNullOrEmpty();
        assertThat(head.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(head.getResponseHeaders().getContentLength())
                .isEqualTo(get.getResponseHeaders().getContentLength())
                .isPositive();
        assertThat(getBook.calls()).isEqualTo(before + 1);

        assertThat(call(client.head().uri("/books/404")).status()).isEqualTo(404);
    }

    @Test
    void optionsAnswersAllow() {
        Result result = call(client.options().uri("/books/1"));

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.allow()).isEqualTo("GET, HEAD, PUT, DELETE, OPTIONS");

        Result collection = call(client.options().uri("/books"));
        assertThat(collection.status()).isEqualTo(200);
        assertThat(collection.allow()).isEqualTo("POST, OPTIONS");
    }

    @Test
    void unknownRouteIs404RouteNotFound() {
        for (String uri : new String[]{"/nope", "/books/1/extra"}) {
            Result result = call(client.get().uri(uri));

            assertThat(result.status()).as(uri).isEqualTo(404);
            assertThat(result.contentType()).startsWith("application/problem+json");
            assertThat(result.json().get("code").asString()).as(uri).isEqualTo("ROUTE_NOT_FOUND");
        }
    }

    @Test
    void exposureInventoryIsLogged(CapturedOutput output) {
        assertThat(output)
                .contains("Synapse REST exposes GET /books/{id} -> get-book on port " + port)
                .contains("Synapse REST exposes POST /books -> create-book on port " + port)
                .contains("Synapse REST exposes PUT /books/{id} -> update-book on port " + port)
                .contains("Synapse REST exposes DELETE /books/{id} -> delete-book on port " + port)
                .doesNotContain("Synapse REST exposes HEAD");
    }

    @Test
    void acceptXmlIs406BeforeExecute() {
        int before = getBook.calls();

        Result result = call(client.get().uri("/books/1").accept(MediaType.APPLICATION_XML));

        assertThat(result.status()).isEqualTo(406);
        assertThat(result.contentType()).startsWith("application/problem+json");
        assertThat(result.json().get("code").asString()).isEqualTo("NOT_ACCEPTABLE");
        assertThat(getBook.calls()).isEqualTo(before);
    }

    @Nested
    @TestPropertySource(properties = "synapse.api.rest.operations.get-book.errors.status.[BOOK_NOT_FOUND]=410")
    class PerOperationStatus {

        @Autowired
        private RestTestClient nestedClient;

        @Test
        void perOperationOverrideChangesOneRoute() {
            Result getBook = call(nestedClient.get().uri("/books/404"));
            assertThat(getBook.status()).isEqualTo(410);
            assertThat(getBook.json().get("code").asString()).isEqualTo("BOOK_NOT_FOUND");

            Result deleteBook = call(nestedClient.delete().uri("/books/404"));
            assertThat(deleteBook.status()).isEqualTo(404);
            assertThat(deleteBook.json().get("code").asString()).isEqualTo("BOOK_NOT_FOUND");
        }
    }

    @Nested
    @ActiveProfiles("dev")
    class OutsideProduction {

        @Autowired
        private RestTestClient nestedClient;

        @Test
        void notFoundDetailHasThePathValue() {
            Result result = call(nestedClient.get().uri("/books/404"));

            assertThat(result.status()).isEqualTo(404);
            assertThat(result.json().get("code").asString()).isEqualTo("BOOK_NOT_FOUND");
            assertThat(result.json().get("detail").asString()).isEqualTo("Book 404 was not found");
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {

        @Bean
        GetBookOperation getBookOperation() {
            return new GetBookOperation();
        }

        @Bean
        CreateBookOperation createBookOperation() {
            return new CreateBookOperation();
        }

        @Bean
        UpdateBookOperation updateBookOperation() {
            return new UpdateBookOperation();
        }

        @Bean
        DeleteBookOperation deleteBookOperation() {
            return new DeleteBookOperation();
        }

        @Bean
        LatestBookOperation latestBookOperation() {
            return new LatestBookOperation();
        }

        @Bean
        RestBindings books(GetBookOperation getBook, CreateBookOperation createBook, UpdateBookOperation updateBook,
                DeleteBookOperation deleteBook, LatestBookOperation latestBook) {
            return RestBindings
                    .builder()
                    .get("/books/{id}", getBook)
                    .notFound(BookErrors.BOOK_NOT_FOUND)
                    .post("/books", createBook)
                    .created(book -> "/books/" + book.id())
                    .put("/books/{id}", updateBook)
                    .delete("/books/{id}", deleteBook)
                    .get("/books/latest", latestBook)
                    .build();
        }
    }

    record BookId(long id) {
    }

    record Book(long id, String title) {
    }

    record CreateBookRequest(@NotBlank String title) {
    }

    record UpdateBookRequest(long id, String title) {
    }

    static class GetBookOperation implements QueryOperation<BookId, Optional<Book>> {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Optional<Book> execute(BookId input) {
            calls.incrementAndGet();
            return input.id() == 404 ? Optional.empty() : Optional.of(new Book(input.id(), "Title " + input.id()));
        }

        /** Public, so the call reaches the target through the pipeline proxy. */
        public int calls() {
            return calls.get();
        }
    }

    static class CreateBookOperation implements CommandOperation<CreateBookRequest, Book> {
        @Override
        public Book execute(CreateBookRequest input) {
            return new Book(42, input.title());
        }
    }

    static class UpdateBookOperation implements CommandOperation<UpdateBookRequest, Book> {
        @Override
        public Book execute(UpdateBookRequest input) {
            return new Book(input.id(), input.title());
        }
    }

    static class LatestBookOperation implements QueryOperation<Void, Book> {
        @Override
        public Book execute(Void input) {
            return new Book(99, "Latest");
        }
    }

    static class DeleteBookOperation implements CommandOperation<BookId, Void> {
        @Override
        @SuppressWarnings("NullAway") // A void operation returns null; ServiceOperation has no @Nullable bound on O.
        public Void execute(BookId input) {
            if (input.id() == 404) {
                throw new SynapseException(BookErrors.BOOK_NOT_FOUND, input.id());
            }
            return null;
        }
    }
}
