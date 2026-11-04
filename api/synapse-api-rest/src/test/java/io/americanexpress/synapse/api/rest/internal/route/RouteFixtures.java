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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.RestConventions;
import io.americanexpress.synapse.api.rest.internal.binding.BindingValidator;
import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;

/** Records and operations of the route tests. */
final class RouteFixtures {

    static final Book BOOK = new Book(7, "Dune");

    private RouteFixtures() {
    }

    /** Resolves the first route of the bindings, as the routes bean does. */
    static RouteDefinition route(RestBindings bindings, RestConventions... conventions) {
        return new BindingValidator().validate(bindings.routes(), List.of(conventions)).getFirst();
    }

    record BookId(long id) {
    }

    record Book(long id, String title) {
    }

    record BookResult(Book book, int remaining) {
    }

    record RenamedResult(Book book, @JsonProperty("left") int remaining) {
    }

    record Job(long id, boolean queued) {
    }

    record TaggedBook(Book book, @Nullable String tag) {
    }

    record Labels(Book book, List<String> tags, Optional<String> label) {
    }

    record Quota(Book book, int rateRemaining) {
    }

    static class GetLabelsOperation implements QueryOperation<BookId, Labels> {
        @Override
        public Labels execute(BookId input) {
            return new Labels(BOOK, List.of(), Optional.empty());
        }
    }

    static class GetQuotaOperation implements QueryOperation<BookId, Quota> {
        @Override
        public Quota execute(BookId input) {
            return new Quota(BOOK, 5);
        }
    }

    static class GetTaggedBookOperation implements QueryOperation<BookId, TaggedBook> {
        @Override
        public TaggedBook execute(BookId input) {
            return new TaggedBook(BOOK, null);
        }
    }

    static class GetBookOperation implements QueryOperation<BookId, Book> {

        final AtomicInteger calls = new AtomicInteger();

        @Override
        public Book execute(BookId input) {
            calls.incrementAndGet();
            return new Book(input.id(), "Dune");
        }
    }

    static class FindBookOperation implements QueryOperation<BookId, Optional<Book>> {
        @Override
        public Optional<Book> execute(BookId input) {
            return Optional.empty();
        }
    }

    static class GetBookResultOperation implements QueryOperation<BookId, BookResult> {
        @Override
        public BookResult execute(BookId input) {
            return new BookResult(BOOK, 5);
        }
    }

    static class FindBookResultOperation implements QueryOperation<BookId, Optional<BookResult>> {
        @Override
        public Optional<BookResult> execute(BookId input) {
            return Optional.of(new BookResult(BOOK, 5));
        }
    }

    static class GetRenamedResultOperation implements QueryOperation<BookId, RenamedResult> {
        @Override
        public RenamedResult execute(BookId input) {
            return new RenamedResult(BOOK, 5);
        }
    }

    static class ListBooksOperation implements QueryOperation<Void, List<Book>> {
        @Override
        public List<Book> execute(Void input) {
            return List.of();
        }
    }

    static class CreateBookOperation implements CommandOperation<Book, Book> {
        @Override
        public Book execute(Book input) {
            return input;
        }
    }

    static class DeleteBookOperation implements CommandOperation<BookId, Book> {
        @Override
        public Book execute(BookId input) {
            return BOOK;
        }
    }

    static class PurgeBookOperation implements CommandOperation<BookId, Void> {
        @Override
        public Void execute(BookId input) {
            throw new UnsupportedOperationException("not called by the tests");
        }
    }

    static class SubmitJobOperation implements CommandOperation<Book, Job> {
        @Override
        public Job execute(Book input) {
            return new Job(9, true);
        }
    }
}
