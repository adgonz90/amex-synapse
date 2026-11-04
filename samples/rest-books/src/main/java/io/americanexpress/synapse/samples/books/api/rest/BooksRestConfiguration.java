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
package io.americanexpress.synapse.samples.books.api.rest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.samples.books.contract.BookErrors;
import io.americanexpress.synapse.samples.books.model.CatalogStats;
import io.americanexpress.synapse.samples.books.model.CreateBookRequest;
import io.americanexpress.synapse.samples.books.operation.CreateBookOperation;
import io.americanexpress.synapse.samples.books.operation.DeleteBookOperation;
import io.americanexpress.synapse.samples.books.operation.GetBookOperation;
import io.americanexpress.synapse.samples.books.operation.GetCatalogStatsOperation;
import io.americanexpress.synapse.samples.books.operation.SearchBooksOperation;
import io.americanexpress.synapse.samples.books.operation.UpdateBookOperation;

/**
 * The REST API of the catalog. This bean is all the REST configuration the service has: Synapse turns it into Spring
 * MVC routes, binds each request to the operation's input record, runs the operation through the pipeline and maps
 * the output (or the error) to the response.
 */
@Configuration(proxyBeanMethods = false)
public class BooksRestConfiguration {

    /** A book is a few hundred bytes of JSON; anything near this is not a book. The default limit is 1 MB. */
    private static final DataSize MAX_BOOK_SIZE = DataSize.ofKilobytes(16);

    @Bean
    RestBindings books(GetBookOperation getBook, SearchBooksOperation searchBooks,
            GetCatalogStatsOperation getCatalogStats, CreateBookOperation createBook, UpdateBookOperation updateBook,
            DeleteBookOperation deleteBook) {
        return RestBindings
                .builder()
                // The path variable {id} fills GetBookRequest.id. The output is an Optional: when it is empty, the
                // answer is a 404 problem with this code instead of the default NOT_FOUND.
                .get("/books/{id}", getBook)
                .notFound(BookErrors.BOOK_NOT_FOUND)
                // On GET, simple components come from the query string: ?author=guin fills SearchBooksRequest.author.
                .get("/books", searchBooks)
                // An output component written to a response header. It would be removed from the body; alsoInBody()
                // keeps it there too. HEAD /books/stats answers the header without the body.
                .get("/books/stats", getCatalogStats)
                .header("X-Books-Total", CatalogStats::books)
                .alsoInBody()
                // The JSON body fills the components; channel comes from the request context instead (the X-Channel
                // header, see synapse.api.rest.context.headers in application.yaml). 201 with a Location header.
                .post("/books", createBook)
                .context(CreateBookRequest::channel, context -> context.attribute("channel").orElse(null))
                .created(book -> "/books/" + book.id())
                .maxRequestSize(MAX_BOOK_SIZE)
                // id from the path, everything else from the body. A body "id" that differs from the path is a 400.
                .put("/books/{id}", updateBook)
                .maxRequestSize(MAX_BOOK_SIZE)
                // DELETE answers 204 with no body.
                .delete("/books/{id}", deleteBook)
                .build();
    }
}
