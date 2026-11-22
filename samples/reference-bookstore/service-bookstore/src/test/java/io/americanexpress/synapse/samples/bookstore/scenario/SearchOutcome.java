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

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.samples.bookstore.scenario.Outcome.BookView;
import io.americanexpress.synapse.samples.bookstore.scenario.Outcome.Kind;

/**
 * The answer to a search, in a form that doesn't depend on the protocol: one page, or a failure as in
 * {@link Outcome}. The token is encrypted with a new nonce every time, so two answers with the same next page have
 * different tokens: scenarios compare the books and the flags, and use the token to ask for the next page.
 *
 * @param kind {@link Kind#OK} for a page, {@link Kind#FAILED} for a failure
 * @param category the error's category, for a failure
 * @param code the error code, for a failure
 * @param violations the fields the violations of a failure name, in the contract's names
 * @param books the books of the page
 * @param nextToken the token of the next page: REST's {@code nextCursor}, gRPC's {@code next_page_token}; {@code null}
 *        when there is none
 * @param hasNext whether another page follows
 * @param total the number of books that match, or {@code null} when not counted
 */
public record SearchOutcome(Kind kind, @Nullable ErrorCategory category, @Nullable String code,
        List<String> violations, List<BookView> books, @Nullable String nextToken, boolean hasNext,
        @Nullable Long total) {

    /**
     * Creates a search outcome.
     */
    public SearchOutcome {
        violations = List.copyOf(violations);
        books = List.copyOf(books);
    }

    /**
     * Returns a page.
     *
     * @param books the books
     * @param nextToken the next page's token, or {@code null}
     * @param hasNext whether another page follows
     * @param total the number of books that match, or {@code null}
     * @return the outcome
     */
    static SearchOutcome page(List<BookView> books, @Nullable String nextToken, boolean hasNext,
            @Nullable Long total) {
        return new SearchOutcome(Kind.OK, null, null, List.of(), books, nextToken, hasNext, total);
    }

    /**
     * Returns a failure as a client received it.
     *
     * @param code the error code
     * @param violations the fields its violations name, in the contract's names
     * @return the outcome
     */
    static SearchOutcome failed(String code, List<String> violations) {
        Outcome failure = Outcome.failed(code, violations);
        return new SearchOutcome(Kind.FAILED, failure.category(), code, violations, List.of(), null, false, null);
    }

    /**
     * Returns this outcome as an {@link Outcome}, to compare a failure with {@link Outcome#failed(ErrorDetails,
     * String...)}.
     *
     * @return the outcome, without the page
     */
    public Outcome asOutcome() {
        return new Outcome(kind, category, code, null, null, violations);
    }

    /**
     * Returns the ids of the page's books, in their order.
     *
     * @return the ids
     */
    public List<UUID> ids() {
        return books.stream().map(BookView::id).toList();
    }
}
