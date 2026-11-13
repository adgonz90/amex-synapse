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
package io.americanexpress.synapse.samples.bookstore.api.rest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.samples.bookstore.contract.Book;
import io.americanexpress.synapse.samples.bookstore.contract.BookErrors;
import io.americanexpress.synapse.samples.bookstore.contract.DeleteBookRequest;
import io.americanexpress.synapse.samples.bookstore.contract.SearchBooksRequest;
import io.americanexpress.synapse.samples.bookstore.contract.UpdateBookRequest;
import io.americanexpress.synapse.samples.bookstore.operation.CreateBookOperation;
import io.americanexpress.synapse.samples.bookstore.operation.DeleteBookOperation;
import io.americanexpress.synapse.samples.bookstore.operation.GetBookOperation;
import io.americanexpress.synapse.samples.bookstore.operation.SearchBooksOperation;
import io.americanexpress.synapse.samples.bookstore.operation.UpdateBookOperation;

/**
 * The REST API of the bookstore. This bean is all the REST configuration the service has: Synapse turns it into
 * Spring MVC routes, binds each request to the operation's input record, runs the operation through the pipeline and
 * maps the output (or the error) to the response. {@code BookstoreGrpcConfiguration} binds the same operations over
 * gRPC, with the same declarations where gRPC has them, so the two APIs take the same requests and give the same
 * answers.
 *
 * <p>
 * With {@code synapse-api-rest-openapi} on the class path, the same bean is the API's documentation: every route
 * below, with its parameters, schemas, statuses and problem responses, is in the OpenAPI document at
 * {@code /v3/api-docs}, which {@code application-local.yaml} turns on (it is off by default).
 */
@Configuration(proxyBeanMethods = false)
public class BookstoreRestConfiguration {

    // The bean is named after the method, so the REST and gRPC bindings need different names.
    @Bean
    RestBindings restBindings(CreateBookOperation createBook, GetBookOperation getBook,
            SearchBooksOperation searchBooks,
            UpdateBookOperation updateBook, DeleteBookOperation deleteBook) {
        return RestBindings
                .builder()
                // The JSON body fills CreateBookRequest. 201 with a Location header, and the new book's ETag ("0"),
                // ready for the If-Match of an update or a delete. An ISBN another book has is 409 ALREADY_EXISTS.
                .post("/books", createBook)
                .created(book -> "/books/" + book.id())
                .etag(Book::version)
                // The path variable {id} fills GetBookRequest.id. An empty Optional is a 404 with BOOK_NOT_FOUND. The
                // book's version is its ETag, and a GET whose If-None-Match matches it is answered 304 (HTTP caching,
                // which gRPC doesn't have).
                .get("/books/{id}", getBook)
                .notFound(BookErrors.BOOK_NOT_FOUND)
                .etag(Book::version)
                // ?author= fills SearchBooksRequest.author, and cursor, offset, size and sort fill its PageQuery. The
                // answer is a page object {items, nextCursor, hasNext, total} with a Link: rel="next" header. The sort
                // keys are SortKey's constants in lowerCamel: sort=title, sort=-publishedYear (descending); any other
                // key is a 400.
                .get("/books", searchBooks)
                .sortable(SearchBooksRequest.SortKey.class)
                // id from the path, everything else from the body; a body "id" that differs from the path is a 400.
                // If-Match fills UpdateBookRequest.version, which isn't @Nullable, so it is required: without it, 428
                // PRECONDITION_REQUIRED. A version the book no longer has (or a weak tag) is 412 PRECONDITION_FAILED.
                // No ETag on the 200 (RFC 9110 9.3.4): the client reads the book again for its new one.
                .put("/books/{id}", updateBook)
                .header("If-Match", UpdateBookRequest::version)
                // 204 with no body. If-Match is required, as on PUT.
                .delete("/books/{id}", deleteBook)
                .header("If-Match", DeleteBookRequest::version)
                .build();
    }
}
