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

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import org.springframework.format.support.DefaultFormattingConversionService;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.RestConventions;
import io.americanexpress.synapse.api.rest.autoconfigure.SynapseRestProperties;
import io.americanexpress.synapse.api.rest.internal.binding.BindingValidator;
import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.api.rest.internal.conditional.ETagValues;
import io.americanexpress.synapse.api.rest.internal.paging.AesGcmCursorCodec;
import io.americanexpress.synapse.api.rest.internal.paging.CursorKeys;
import io.americanexpress.synapse.api.rest.internal.paging.InputDigest;
import io.americanexpress.synapse.api.rest.internal.paging.PageCursors;
import io.americanexpress.synapse.api.rest.internal.paging.PagingSettings;
import io.americanexpress.synapse.core.conditional.VersionCondition;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;

/** Records and operations of the route tests. */
final class RouteFixtures {

    static final Book BOOK = new Book(7, "Dune");

    /** A configured cursor key (32 zero bytes), so tokens stay valid across the cursors of one test. */
    static final String CURSOR_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private RouteFixtures() {
    }

    /** The cursors of an AES-GCM codec with the key, or with a key generated at startup when it is null. */
    static PageCursors cursors(@Nullable String key) {
        SecureRandom random = new SecureRandom();
        SynapseRestProperties.Paging.Cursor cursor = new SynapseRestProperties.Paging.Cursor(key, List.of(), null);
        return new PageCursors(new AesGcmCursorCodec(CursorKeys.from(cursor, random), Clock.systemUTC(), null, random),
                new InputDigest(), PagingSettings.defaults());
    }

    /** The cursors of an AES-GCM codec with {@link #CURSOR_KEY}. */
    static PageCursors cursors() {
        return cursors(CURSOR_KEY);
    }

    /** The entity tag values, formatted with a default conversion service. */
    static ETagValues etags() {
        return new ETagValues(new DefaultFormattingConversionService());
    }

    /** Resolves the first route of the bindings, as the routes bean does. */
    static RouteDefinition route(RestBindings bindings, RestConventions... conventions) {
        return new BindingValidator(PagingSettings.defaults())
                .validate(bindings.routes(), List.of(conventions))
                .getFirst();
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

    record PagedBooksRequest(@Nullable String author, PageQuery page) {
    }

    /** A book with the version its {@code ETag} is made from. */
    record VersionedBook(long id, String title, @Nullable Long version) {
    }

    static class GetVersionedBookOperation implements QueryOperation<BookId, VersionedBook> {
        @Override
        public VersionedBook execute(BookId input) {
            return new VersionedBook(input.id(), "Dune", 7L);
        }
    }

    static class CreateVersionedBookOperation implements CommandOperation<VersionedBook, VersionedBook> {
        @Override
        public VersionedBook execute(VersionedBook input) {
            return input;
        }
    }

    /** A revision of a book, conditional on the version bound from {@code If-Match}. */
    record RevisionRequest(long id, String title, @Nullable VersionCondition<Long> version) {
    }

    /** Revises a book and counts its calls. */
    static class ReviseVersionedBookOperation implements CommandOperation<RevisionRequest, VersionedBook> {

        final AtomicInteger calls = new AtomicInteger();

        @Override
        public VersionedBook execute(RevisionRequest input) {
            calls.incrementAndGet();
            return new VersionedBook(input.id(), input.title(), 2L);
        }
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

    /** Answers with its {@code result}, and records the inputs it receives. */
    static class PagedBooksOperation implements QueryOperation<PagedBooksRequest, PageResult<Book>> {

        final List<PagedBooksRequest> inputs = new CopyOnWriteArrayList<>();

        volatile PageResult<Book> result = PageResult.of(List.of(BOOK), false);

        @Override
        public PageResult<Book> execute(PagedBooksRequest input) {
            inputs.add(input);
            return result;
        }
    }
}
