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
package io.americanexpress.synapse.api.rest.openapi;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.validation.constraints.NotBlank;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.core.conditional.VersionCondition;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;

/**
 * A books API with one route of each kind {@code /v3/api-docs} documents, and an escape-hatch controller next to it:
 * <ul>
 * <li>{@code GET /books/{id}}: an {@code Optional} output with {@code .etag};</li>
 * <li>{@code GET /books}: a {@code PageResult} with a next cursor and a total;</li>
 * <li>{@code POST /books}: {@code .created}, the whole input from the body;</li>
 * <li>{@code PUT /books/{id}}: the body, the path and {@code If-Match};</li>
 * <li>{@code DELETE /books/{id}}: {@code If-Match}, a {@code Void} output;</li>
 * <li>{@code GET /v2/books/{id}}: {@code .version("2")};</li>
 * <li>the controller's {@code GET /books/{bookId}/reviews} and {@code PATCH /books/{bookId}}, which OpenAPI sees as
 * Synapse's {@code /books/{id}}.</li>
 * </ul>
 * Property names have more than one word, so a naming strategy changes them.
 */
final class BooksApi {

    static final UUID DUNE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private BooksApi() {
    }

    static Book dune() {
        return new Book(DUNE, "Dune", "Frank Herbert", 1965, 3);
    }

    record Book(UUID id, String title, String authorName, @Nullable Integer publishedYear, long version) {
    }

    record GetBookRequest(UUID id) {
    }

    record SearchBooksRequest(@Nullable String authorName, PageQuery page) {
    }

    record CreateBookRequest(@NotBlank String title, @NotBlank String authorName, @Nullable Integer publishedYear) {
    }

    record UpdateBookRequest(UUID id, @NotBlank String title, @NotBlank String authorName,
            @Nullable Integer publishedYear, @Nullable VersionCondition<Long> version) {
    }

    record DeleteBookRequest(UUID id, @Nullable VersionCondition<Long> version) {
    }

    static class GetBookOperation implements QueryOperation<GetBookRequest, Optional<Book>> {

        @Override
        public Optional<Book> execute(GetBookRequest input) {
            return DUNE.equals(input.id()) ? Optional.of(dune()) : Optional.empty();
        }
    }

    static class SearchBooksOperation implements QueryOperation<SearchBooksRequest, PageResult<Book>> {

        @Override
        public PageResult<Book> execute(SearchBooksRequest input) {
            return PageResult.ofCursor(List.of(dune()), "after-dune").withTotal(2);
        }
    }

    static class CreateBookOperation implements CommandOperation<CreateBookRequest, Book> {

        @Override
        public Book execute(CreateBookRequest input) {
            return new Book(UUID.randomUUID(), input.title(), input.authorName(), input.publishedYear(), 1);
        }
    }

    static class UpdateBookOperation implements CommandOperation<UpdateBookRequest, Book> {

        @Override
        public Book execute(UpdateBookRequest input) {
            return new Book(input.id(), input.title(), input.authorName(), input.publishedYear(), 4);
        }
    }

    static class DeleteBookOperation implements CommandOperation<DeleteBookRequest, Void> {

        @Override
        @SuppressWarnings("NullAway") // A void operation returns null; ServiceOperation has no @Nullable bound on O.
        public Void execute(DeleteBookRequest input) {
            return null;
        }
    }

    /** An escape-hatch controller: springdoc documents it. */
    @RestController
    static class ReviewsController {

        @GetMapping("/books/{bookId}/reviews")
        List<String> reviews(@PathVariable String bookId) {
            return List.of("A classic.");
        }

        @PatchMapping("/books/{bookId}")
        String touch(@PathVariable String bookId) {
            return bookId;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(ReviewsController.class)
    static class Routes {

        @Bean
        GetBookOperation getBookOperation() {
            return new GetBookOperation();
        }

        @Bean
        SearchBooksOperation searchBooksOperation() {
            return new SearchBooksOperation();
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
        RestBindings books(GetBookOperation getBook, SearchBooksOperation searchBooks, CreateBookOperation createBook,
                UpdateBookOperation updateBook, DeleteBookOperation deleteBook) {
            return RestBindings
                    .builder()
                    .get("/books/{id}", getBook)
                    .etag(Book::version)
                    .get("/books", searchBooks)
                    .post("/books", createBook)
                    .created(book -> "/books/" + book.id())
                    .put("/books/{id}", updateBook)
                    .header("If-Match", UpdateBookRequest::version)
                    .delete("/books/{id}", deleteBook)
                    .header("If-Match", DeleteBookRequest::version)
                    .get("/books/{id}", getBook)
                    .version("2")
                    .build();
        }
    }
}
