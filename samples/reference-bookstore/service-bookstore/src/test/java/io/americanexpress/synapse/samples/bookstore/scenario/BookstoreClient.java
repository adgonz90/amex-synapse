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
package io.americanexpress.synapse.samples.bookstore.scenario;

import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The bookstore's five operations over one protocol, with every answer as an {@link Outcome}. Each implementation
 * also checks its own protocol's status for every answer (REST 201 with a {@code Location}, 412 or 428; gRPC
 * {@code FAILED_PRECONDITION} or {@code ALREADY_EXISTS}, for example), so a wrong status fails that protocol's run
 * even when the outcome is right. The one exception is gRPC's status for {@code PRECONDITION_FAILED}, which depends on
 * why the precondition failed: the scenario that sends the {@code etag} checks it
 * ({@link GrpcBookstoreClient#lastStatus()}). There is no conditional read: REST's 304 has no gRPC form, so it is
 * tested in {@code BookstoreRestTest} only.
 */
public interface BookstoreClient extends AutoCloseable {

    /**
     * Adds a book.
     *
     * @param isbn the ISBN
     * @param title the title, or {@code null} to send none (a missing required field)
     * @param author the author
     * @param year the year of publication, or {@code null} to send none
     * @return {@code CREATED} with the book and its etag, or the failure
     */
    Outcome create(String isbn, @Nullable String title, String author, @Nullable Integer year);

    /**
     * Reads a book.
     *
     * @param id the id
     * @return {@code OK} with the book and its etag, or the failure
     */
    Outcome get(UUID id);

    /**
     * Replaces a book's details.
     *
     * @param id the id
     * @param etag the entity tag to send ({@code If-Match}, gRPC {@code etag}), or {@code null} to send none
     * @param isbn the ISBN
     * @param title the title
     * @param author the author
     * @param year the year of publication, or {@code null} to send none
     * @return {@code OK} with the book and no etag, or the failure
     */
    Outcome update(UUID id, @Nullable String etag, String isbn, String title, String author, @Nullable Integer year);

    /**
     * Removes a book.
     *
     * @param id the id
     * @param etag the entity tag to send, or {@code null} to send none
     * @return {@code NO_CONTENT}, or the failure
     */
    Outcome delete(UUID id, @Nullable String etag);

    /**
     * Searches the books, from the first page or from a token.
     *
     * @param author part of the author's name, or {@code null} to send none
     * @param size the page size, always sent
     * @param token a page token, or {@code null} to send none ({@code ""} is sent, as an empty value)
     * @param sort the sort keys in REST's form ({@code title,-publishedYear}), or {@code null} to send none; the gRPC
     *        client sends them in its own form ({@code title, published_year desc})
     * @return the page, or the failure
     */
    default SearchOutcome search(@Nullable String author, int size, @Nullable String token, @Nullable String sort) {
        return search(author, size, token, null, sort);
    }

    /**
     * Searches the books from an offset: REST's {@code offset}, gRPC's {@code skip}.
     *
     * @param author part of the author's name, or {@code null} to send none
     * @param size the page size, always sent
     * @param token a page token, or {@code null} to send none
     * @param offset the books to skip, or {@code null} to send none
     * @param sort the sort keys in REST's form, or {@code null} to send none
     * @return the page, or the failure
     */
    SearchOutcome search(@Nullable String author, int size, @Nullable String token, @Nullable Long offset,
            @Nullable String sort);

    /**
     * Reads a book with a correlation id, as {@link #get(UUID)} does, and returns the answer with the correlation id
     * it carries: REST's {@code X-Correlation-Id} header, the gRPC {@code x-correlation-id} headers or trailers.
     *
     * @param id the id of the book; an unknown one gets a failure, which carries the id too
     * @param correlationId the correlation id to send
     * @return the read's outcome and the answer's correlation id
     */
    Echo echoedCorrelationId(UUID id, String correlationId);

    /**
     * Returns the messages of the violations of this client's last failed call, by field in the contract's names, as
     * the {@link Outcome} names them: the message is the one part of a violation that the outcome leaves out.
     *
     * @return the messages in the order the answer listed them, empty when the last failure had no violations or no
     *         call failed
     */
    Map<String, String> lastViolations();

    /** Releases the client's connections. */
    @Override
    void close();

    /**
     * A read and the correlation id its answer carried.
     *
     * @param outcome the read's outcome
     * @param correlationId the correlation id of the answer, or {@code null} when it has none
     */
    record Echo(Outcome outcome, @Nullable String correlationId) {
    }
}
