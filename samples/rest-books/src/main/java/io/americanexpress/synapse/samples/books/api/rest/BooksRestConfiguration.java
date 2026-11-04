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
import io.americanexpress.synapse.samples.books.model.Book;
import io.americanexpress.synapse.samples.books.model.CatalogStats;
import io.americanexpress.synapse.samples.books.model.CreateBookRequest;
import io.americanexpress.synapse.samples.books.model.DeleteBookRequest;
import io.americanexpress.synapse.samples.books.model.SearchBooksRequest;
import io.americanexpress.synapse.samples.books.model.UpdateBookRequest;
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
                // The book's version is its strong ETag ("1"), sent with Cache-Control: no-cache and Vary: Accept.
                // A GET or HEAD whose If-None-Match matches it is answered 304 without a body.
                .etag(Book::version)
                // On GET, simple components come from the query string: ?author=guin fills SearchBooksRequest.author.
                // The PageQuery component comes from cursor, offset, size and sort. The output is a PageResult, so the
                // answer is a page object {items, nextCursor, hasNext, total} plus a Link: rel="next" header, and the
                // operation's cursor reaches the client only encrypted.
                .get("/books", searchBooks)
                // The sort keys callers may use: sort=title, sort=-year (descending), sort=year,title. Any other key
                // is a 400; without .sortable, any sort parameter would be.
                .sortable(SearchBooksRequest.SortKey.class)
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
                // The 201 also carries the new book's ETag ("1"), ready for a later If-Match.
                .etag(Book::version)
                .maxRequestSize(MAX_BOOK_SIZE)
                // id from the path, everything else from the body. A body "id" that differs from the path is a 400.
                .put("/books/{id}", updateBook)
                // If-Match: "1" fills UpdateBookRequest.version with 1, and the update succeeds only on that version:
                // otherwise the business operation throws STALE_VERSION, which is answered 412 PRECONDITION_FAILED
                // because the request carried If-Match. If-Match is optional here: without it (or with *) the version
                // is null, and a concurrent change that wins between the operation's read and its write is a 409
                // STALE_VERSION. A weak tag (W/"1") is 412 before the operation runs. No ETag on the 200 (RFC 9110
                // 9.3.4): the new version is in the body.
                .header("If-Match", UpdateBookRequest::version)
                .maxRequestSize(MAX_BOOK_SIZE)
                // DELETE answers 204 with no body. If-Match fills DeleteBookRequest.version as on PUT, and here it is
                // required: a DELETE without it is 428 PRECONDITION_REQUIRED. If-Match: * leaves the version null, so
                // RemoveBook deletes the version it has just read; if a concurrent update changes the book between
                // that read and the delete, the delete is still 412.
                .delete("/books/{id}", deleteBook)
                .header("If-Match", DeleteBookRequest::version)
                .requireIfMatch()
                .build();
    }
}
