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
package io.americanexpress.synapse.samples.books.api.grpc;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.americanexpress.synapse.api.grpc.GrpcBindings;
import io.americanexpress.synapse.samples.books.api.grpc.proto.BookServiceGrpc;
import io.americanexpress.synapse.samples.books.contract.BookErrors;
import io.americanexpress.synapse.samples.books.model.CreateBookRequest;
import io.americanexpress.synapse.samples.books.operation.CreateBookOperation;
import io.americanexpress.synapse.samples.books.operation.GetBookOperation;

/**
 * The gRPC API of the catalog: {@code synapse.samples.books.v1.BookService} from {@code src/main/proto/books/v1}. This
 * bean is all the gRPC configuration the service has: Synapse turns it into a gRPC service that Spring Boot's server
 * serves, maps each request message to the operation's input record, runs the operation through the same pipeline as
 * REST and maps the output (or the error) to the response message (or the status). The operations are the ones
 * {@code BooksRestConfiguration} binds; neither binding knows about the other.
 */
@Configuration(proxyBeanMethods = false)
public class BooksGrpcConfiguration {

    @Bean
    GrpcBindings bookService(GetBookOperation getBook, CreateBookOperation createBook) {
        return GrpcBindings
                .service(BookServiceGrpc.getServiceDescriptor())
                // GetBookRequest.id (a string) fills GetBookRequest.id (a UUID); a string that isn't one is
                // INVALID_ARGUMENT. The output is an Optional: when it is empty, the answer is NOT_FOUND with the
                // reason BOOK_NOT_FOUND in ErrorInfo instead of the default NOT_FOUND.
                .unary(BookServiceGrpc.getGetBookMethod(), getBook)
                .notFound(BookErrors.BOOK_NOT_FOUND)
                // The message fields fill the components of the same name; channel comes from the request context
                // instead (the x-channel metadata, see synapse.api.grpc.context.metadata in application.yaml). The
                // proto message has no channel field, so a caller can't set it.
                .unary(BookServiceGrpc.getCreateBookMethod(), createBook)
                .context(CreateBookRequest::channel, context -> context.attribute("channel").orElse(null))
                // UpdateBook, DeleteBook and GetCatalogStats are bound to the operations named after them
                // (update-book, delete-book, get-catalog-stats), field by field. UpdateBookRequest.version and
                // DeleteBookRequest.version are Int64Value wrappers: unset binds null, as If-Match: * does over REST,
                // and a stale version is ABORTED with the reason STALE_VERSION. DeleteBook answers Empty, and
                // GetCatalogStats takes it.
                .bindAllByName()
                .build();
    }
}
