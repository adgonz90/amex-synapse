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

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.samples.bookstore.contract.BookErrors;

/**
 * The answer to one request, in a form that doesn't depend on the protocol, so the same scenario can expect the same
 * outcome over REST and gRPC. A failure is its error code (REST's problem {@code code}, gRPC's
 * {@code ErrorInfo.reason}), that code's category, looked up in the codes the bookstore declares and never derived
 * from the protocol's status, and the fields its violations name. The protocol's own status is checked by each
 * client.
 *
 * @param kind what the request did
 * @param category the error's category, for a failure
 * @param code the error code, for a failure
 * @param book the book in the answer, or {@code null} when it has none
 * @param etag the entity tag of the answer: REST's {@code ETag} header, gRPC's {@code Book.etag}; {@code null} when
 *        the answer has none (an update, a delete, a failure)
 * @param violations the fields the violations of a failure name, in the contract's names (those of the REST API: a
 *        gRPC client translates {@code published_year}, {@code page_size} and the other field names it differs in)
 */
public record Outcome(Kind kind, @Nullable ErrorCategory category, @Nullable String code, @Nullable BookView book,
        @Nullable String etag, List<String> violations) {

    /**
     * Creates an outcome.
     */
    public Outcome {
        violations = List.copyOf(violations);
    }

    /** What a request did. */
    public enum Kind {

        /** Answered with the book: REST 200, a gRPC response. */
        OK,

        /** Added the book: REST 201 with a {@code Location} header, a gRPC response to {@code CreateBook}. */
        CREATED,

        /** Answered with nothing: REST 204, gRPC {@code google.protobuf.Empty}. */
        NO_CONTENT,

        /** Failed with an error code. */
        FAILED
    }

    /**
     * A book as either protocol returns it: the REST body, or the gRPC {@code Book} message without its etag.
     *
     * @param id the id
     * @param isbn the ISBN
     * @param title the title
     * @param author the author
     * @param publishedYear the year of publication, or {@code null}
     * @param version the version
     */
    public record BookView(UUID id, String isbn, String title, String author, @Nullable Integer publishedYear,
            long version) {
    }

    /**
     * Returns the answer of a read or an update.
     *
     * @param book the book
     * @param etag the entity tag, or {@code null}
     * @return the outcome
     */
    public static Outcome ok(BookView book, @Nullable String etag) {
        return new Outcome(Kind.OK, null, null, book, etag, List.of());
    }

    /**
     * Returns the answer of a create.
     *
     * @param book the book
     * @param etag the entity tag
     * @return the outcome
     */
    public static Outcome created(BookView book, String etag) {
        return new Outcome(Kind.CREATED, null, null, book, etag, List.of());
    }

    /**
     * Returns the answer of a delete.
     *
     * @return the outcome
     */
    public static Outcome noContent() {
        return new Outcome(Kind.NO_CONTENT, null, null, null, null, List.of());
    }

    /**
     * Returns a failure.
     *
     * @param code the error
     * @param violations the fields its violations name
     * @return the outcome
     */
    public static Outcome failed(ErrorDetails code, String... violations) {
        return new Outcome(Kind.FAILED, code.category(), code.code(), null, null, Arrays.asList(violations));
    }

    /**
     * Returns a failure as a client received it.
     *
     * @param code the error code
     * @param violations the fields its violations name, in the contract's names
     * @return the outcome
     * @throws AssertionError when the bookstore declares no such code
     */
    static Outcome failed(String code, List<String> violations) {
        return new Outcome(Kind.FAILED, details(code).category(), code, null, null, violations);
    }

    /**
     * Returns the error a code names: one of Synapse's or one of the bookstore's.
     *
     * @param code the error code
     * @return the error
     * @throws AssertionError when neither declares it
     */
    static ErrorDetails details(String code) {
        for (ErrorDetails details : SynapseErrors.values()) {
            if (details.code().equals(code)) {
                return details;
            }
        }
        for (ErrorDetails details : BookErrors.values()) {
            if (details.code().equals(code)) {
                return details;
            }
        }
        throw new AssertionError("The bookstore declares no error code " + code);
    }
}
