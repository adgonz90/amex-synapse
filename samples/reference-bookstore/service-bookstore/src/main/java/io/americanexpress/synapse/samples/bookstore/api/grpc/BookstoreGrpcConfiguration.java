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
package io.americanexpress.synapse.samples.bookstore.api.grpc;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.americanexpress.synapse.api.grpc.GrpcBindings;
import io.americanexpress.synapse.samples.bookstore.contract.Book;
import io.americanexpress.synapse.samples.bookstore.contract.BookErrors;
import io.americanexpress.synapse.samples.bookstore.contract.SearchBooksRequest;
import io.americanexpress.synapse.samples.bookstore.contract.proto.BookServiceGrpc;
import io.americanexpress.synapse.samples.bookstore.operation.CreateBookOperation;
import io.americanexpress.synapse.samples.bookstore.operation.DeleteBookOperation;
import io.americanexpress.synapse.samples.bookstore.operation.GetBookOperation;
import io.americanexpress.synapse.samples.bookstore.operation.SearchBooksOperation;
import io.americanexpress.synapse.samples.bookstore.operation.UpdateBookOperation;

/**
 * The gRPC API of the bookstore: {@code synapse.samples.bookstore.v1.BookService}, generated in contract-bookstore.
 * This bean is all the gRPC configuration the service has: Synapse turns it into a service that Spring Boot's server
 * serves, maps each request message to the operation's input record, runs the operation through the pipeline and maps
 * the output (or the error) to the response message (or the status).
 *
 * <p>
 * Each method is bound to the operation of the REST route of the same name in {@code BookstoreRestConfiguration},
 * with the same declarations: the not-found code, the entity tag on the read and the create, and the sort keys. Every
 * message field maps to the record component with the same name ({@code published_year} to {@code publishedYear}),
 * so no method needs a {@code .map}. A {@code VersionCondition} reads the request's {@code etag} field, REST's entity
 * tag token, so a client can send the ETag of a REST read here, and the reverse. Every method is bound explicitly, so
 * this class lists the whole API.
 */
@Configuration(proxyBeanMethods = false)
public class BookstoreGrpcConfiguration {

    // The bean is named after the method, so the REST and gRPC bindings need different names.
    @Bean
    GrpcBindings grpcBindings(CreateBookOperation createBook, GetBookOperation getBook,
            SearchBooksOperation searchBooks,
            UpdateBookOperation updateBook, DeleteBookOperation deleteBook) {
        return GrpcBindings
                .service(BookServiceGrpc.getServiceDescriptor())
                // POST /books. The response carries the new book's etag ("0"), as REST's 201 carries its ETag. An ISBN
                // another book has is ALREADY_EXISTS.
                .unary(BookServiceGrpc.getCreateBookMethod(), createBook)
                .etag(Book::version)
                // GET /books/{id}. An empty Optional is NOT_FOUND with the reason BOOK_NOT_FOUND in ErrorInfo, and the
                // response carries the book's etag, as REST's ETag header.
                .unary(BookServiceGrpc.getGetBookMethod(), getBook)
                .notFound(BookErrors.BOOK_NOT_FOUND)
                .etag(Book::version)
                // GET /books. SearchBooksRequest.page reads the AIP-158 fields page_size, page_token, skip and
                // order_by; order_by names SortKey's constants in lower case: "title, published_year desc". The page's
                // books go to the response's repeated books, and Synapse writes next_page_token, has_next and
                // total_size.
                .unary(BookServiceGrpc.getSearchBooksMethod(), searchBooks)
                .sortable(SearchBooksRequest.SortKey.class)
                // PUT /books/{id}. The etag field fills UpdateBookRequest.version, which isn't @Nullable, so it is
                // required: without it, FAILED_PRECONDITION with the reason PRECONDITION_REQUIRED (REST 428). A stale
                // one is ABORTED (AIP-154) and a weak one FAILED_PRECONDITION, both with PRECONDITION_FAILED (REST
                // 412). No .etag: as REST sends no ETag on PUT, the response's etag stays empty, and Synapse fails
                // startup if a method whose input has a VersionCondition declares one.
                .unary(BookServiceGrpc.getUpdateBookMethod(), updateBook)
                // DELETE /books/{id}. The etag is required, as for UpdateBook. The operation returns nothing: the
                // response is google.protobuf.Empty, as REST's 204.
                .unary(BookServiceGrpc.getDeleteBookMethod(), deleteBook)
                .build();
    }
}
