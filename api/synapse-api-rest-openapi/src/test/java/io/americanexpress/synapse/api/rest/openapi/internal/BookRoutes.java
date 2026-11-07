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
package io.americanexpress.synapse.api.rest.openapi.internal;

import java.net.URI;
import java.time.DayOfWeek;
import java.time.Month;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.RestConventions;
import io.americanexpress.synapse.api.rest.internal.binding.BindingValidator;
import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.api.rest.internal.paging.PagingSettings;
import io.americanexpress.synapse.core.conditional.VersionCondition;
import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.operation.OperationGroup;
import io.americanexpress.synapse.core.operation.OperationName;
import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;

/**
 * The routes {@link RouteDocumenterTest} documents, resolved by {@link BindingValidator} as the routes bean resolves
 * them. The operations are never called.
 */
final class BookRoutes {

    private BookRoutes() {
    }

    /**
     * The books API, ten routes:
     * <ol>
     * <li>{@code GET /books/{id}}: {@code Optional} output, {@code .notFound}, {@code .etag};</li>
     * <li>{@code GET /books}: a {@code PageResult}, {@code .sortable(SortKey)}, an optional {@code author} query;</li>
     * <li>{@code GET /books/stats}: an output header with {@code .alsoInBody()}, an {@code @OperationGroup};</li>
     * <li>{@code POST /books}: {@code .created}, {@code .etag}, the whole input from the body;</li>
     * <li>{@code PUT /books/{id}}: {@code If-Match} bound to a {@code VersionCondition};</li>
     * <li>{@code DELETE /books/{id}}: {@code If-Match} bound to a non-null {@code VersionCondition} (required), a
     * {@code Void} output;</li>
     * <li>{@code GET /v2/books/{id}}: {@code .version("2")}, the first route's operation again;</li>
     * <li>{@code GET /authors}: a {@code QueryRecord} convention;</li>
     * <li>{@code POST /books/{id}/loans}: {@code .status(fn)}, a cookie, a header and one body component;</li>
     * <li>{@code GET /books/{id}/cover}: {@code .emptyAs(204)}.</li>
     * </ol>
     */
    static List<RouteDefinition> books(PagingSettings paging) {
        RestBindings bindings = RestBindings
                .builder()
                .get("/books/{id}", new GetBookOperation())
                .notFound(BookErrors.BOOK_NOT_FOUND)
                .etag(Book::version)
                .get("/books", new SearchBooksOperation())
                .sortable(SearchBooksRequest.SortKey.class)
                .get("/books/stats", new GetCatalogStatsOperation())
                .header("X-Books-Total", CatalogStats::books)
                .alsoInBody()
                .post("/books", new CreateBookOperation())
                .created(book -> "/books/" + book.id())
                .etag(Book::version)
                .put("/books/{id}", new UpdateBookOperation())
                .header("If-Match", UpdateBookRequest::version)
                .delete("/books/{id}", new DeleteBookOperation())
                .header("If-Match", DeleteBookRequest::version)
                .get("/books/{id}", new GetBookOperation())
                .version("2")
                .get("/authors", new SearchAuthorsOperation())
                .post("/books/{id}/loans", new LendBookOperation())
                .cookie("session", LoanRequest::session)
                .header("X-Branch", LoanRequest::branch)
                .status(loan -> loan.queued() ? 202 : 201)
                .get("/books/{id}/cover", new FindCoverOperation())
                .emptyAs(204)
                .build();
        RestConventions conventions = RestConventions.builder().query(AuthorFilter.class).build();
        return resolve(bindings, paging, conventions);
    }

    /**
     * Routes of the other shapes:
     * <ul>
     * <li>{@code POST /jobs}: {@code .accepted};</li>
     * <li>{@code PUT /books/{id}/cover}: {@code .body(...)} on the input, a {@code Void} output;</li>
     * <li>{@code GET /quota}: an output header left out of the body;</li>
     * <li>{@code PUT /drafts/{id}}: {@code If-None-Match} bound to a {@code VersionCondition}, {@code .status(203)};</li>
     * <li>{@code HEAD /books/{id}}: a declared {@code HEAD};</li>
     * <li>{@code GET /pages}: a {@code PageQuery} input;</li>
     * <li>{@code GET /trace}: binds the correlation header itself.</li>
     * </ul>
     */
    static List<RouteDefinition> others(PagingSettings paging) {
        RestBindings bindings = RestBindings
                .builder()
                .post("/jobs", new SubmitJobOperation())
                .accepted(job -> "/jobs/" + job.id())
                .put("/books/{id}/cover", new UploadCoverOperation())
                .body(CoverUpload::image)
                .get("/quota", new GetQuotaOperation())
                .header("X-Remaining", Quota::remaining)
                .put("/drafts/{id}", new SaveDraftOperation())
                .header("If-None-Match", DraftRequest::version)
                .status(203)
                .head("/books/{id}", new BookExistsOperation())
                .get("/pages", new PagesOperation())
                .get("/trace", new TraceOperation())
                .header("X-Correlation-Id", TraceRequest::correlation)
                .get("/editions/{id}", new GetEditionOperation())
                .etag(Book::version)
                .status(203)
                .get("/reviews", new SearchReviewsOperation())
                .get("/stock", new GetStockOperation())
                .header("X-Availability", Stock::availability)
                .alsoInBody()
                .header("X-Previous", Stock::previous)
                .header("X-History", Stock::history)
                .get("/whoami", new WhoAmIOperation())
                .context(WhoAmI::channel, context -> context.attribute("channel").orElse(null))
                .from(WhoAmI::agent, request -> request.headers().firstHeader("User-Agent"))
                .get("/covers/{id}", new GetCoverViewOperation())
                .body(CoverView::image)
                .build();
        return resolve(bindings, paging, RestConventions.builder().query(AuthorFilter.class).build());
    }

    /**
     * Routes whose names clash:
     * <ul>
     * <li>{@code GET /shelves/{id}} and {@code PUT /shelves/{shelfId}}: one OpenAPI path;</li>
     * <li>{@code GET /a} and {@code GET /b}: one operation on two routes, and {@code GET /c}: an operation named
     * {@code list-shelves-2}, whose output is a record named {@code SynapseProblem}.</li>
     * </ul>
     */
    static List<RouteDefinition> clashes() {
        RestBindings bindings = RestBindings
                .builder()
                .get("/shelves/{id}", new GetShelfOperation())
                .put("/shelves/{shelfId}", new RenameShelfOperation())
                .get("/a", new ListShelvesOperation())
                .get("/b", new ListShelvesOperation())
                .get("/c", new NamedOperation())
                .build();
        return resolve(bindings, PagingSettings.defaults());
    }

    /** {@code PUT /drafts/{id}}: {@code If-None-Match} bound to a non-null {@code VersionCondition}, so required. */
    static List<RouteDefinition> createOnly() {
        RestBindings bindings = RestBindings
                .builder()
                .put("/drafts/{id}", new CreateDraftOperation())
                .header("If-None-Match", NewDraftRequest::unless)
                .build();
        return resolve(bindings, PagingSettings.defaults());
    }

    /** {@code GET /opaque} and {@code GET /opaque/again}, whose output holds a type the generator can't describe. */
    static List<RouteDefinition> opaque() {
        RestBindings bindings = RestBindings
                .builder()
                .get("/opaque", new GetOpaqueOperation())
                .get("/opaque/again", new GetOpaqueAgainOperation())
                .build();
        return resolve(bindings, PagingSettings.defaults());
    }

    /** {@code GET /clash}, whose output Jackson refuses: two components with one JSON name. */
    static List<RouteDefinition> clashing() {
        RestBindings bindings = RestBindings.builder().get("/clash", new GetClashOperation()).build();
        return resolve(bindings, PagingSettings.defaults());
    }

    /**
     * Enum parameters, which the {@code ConversionService} binds by constant name:
     * <ul>
     * <li>{@code GET /calendar/{month}}: a {@code Month} path variable; query parameters of a {@code @JsonProperty}
     * enum, a {@code Month}, a {@code List} of the enum and an {@code Optional<DayOfWeek>}; the enum in a header;</li>
     * <li>{@code PUT /shelving/{id}}: the same enum in the body, which the mapper reads by its JSON names.</li>
     * </ul>
     */
    static List<RouteDefinition> enums() {
        RestBindings bindings = RestBindings
                .builder()
                .get("/calendar/{month}", new CalendarOperation())
                .query("day", CalendarRequest::day)
                .header("X-Shelving", CalendarRequest::preferred)
                .put("/shelving/{id}", new ShelveOperation())
                .build();
        return resolve(bindings, PagingSettings.defaults());
    }

    /** Resolves the routes as the routes bean does. */
    static List<RouteDefinition> resolve(RestBindings bindings, PagingSettings paging,
            RestConventions... conventions) {
        return new BindingValidator(paging).validate(bindings.routes(), List.of(conventions));
    }

    enum BookErrors implements ErrorDetails {

        BOOK_NOT_FOUND;

        @Override
        public String code() {
            return name();
        }

        @Override
        public String messageTemplate() {
            return "No book {0}";
        }

        @Override
        public ErrorCategory category() {
            return ErrorCategory.NOT_FOUND;
        }
    }

    record Book(UUID id, String isbn, String title, String author, @Nullable Integer year, long version) {
    }

    record GetBookRequest(UUID id) {
    }

    record SearchBooksRequest(@Nullable String author, PageQuery page) {

        enum SortKey {
            TITLE, YEAR
        }
    }

    record CatalogStats(int books, int authors) {
    }

    record CreateBookRequest(@NotBlank String isbn, @NotBlank String title, @NotBlank String author,
            @Positive @Nullable Integer year) {
    }

    record UpdateBookRequest(UUID id, @NotBlank String isbn, @NotBlank String title, @NotBlank String author,
            @Positive @Nullable Integer year, @Nullable VersionCondition<Long> version) {
    }

    /** Its condition isn't {@code @Nullable}, so the route requires {@code If-Match}. */
    record DeleteBookRequest(UUID id, VersionCondition<Long> version) {
    }

    record AuthorFilter(@Nullable String country, int minBooks) {
    }

    record AuthorSearch(AuthorFilter filter) {
    }

    record Author(String name, int books) {
    }

    record LoanRequest(UUID id, String borrower, @Nullable String session, @Nullable String branch) {
    }

    record Loan(UUID book, boolean queued) {
    }

    record Cover(UUID book, URI image) {
    }

    record JobRequest(String title) {
    }

    record Job(UUID id, boolean queued) {
    }

    record Image(URI uri, String type) {
    }

    record CoverUpload(UUID id, Image image) {
    }

    record Quota(int limit, int remaining) {
    }

    record DraftRequest(UUID id, String text, @Nullable VersionCondition<Long> version) {
    }

    record Draft(UUID id, String text) {
    }

    record TraceRequest(@Nullable String correlation) {
    }

    /** A class, not a record: the generator documents it as {@code {}}. */
    static final class Opaque {
    }

    record Wrapper(Opaque value) {
    }

    /** Two components with one JSON name: Jackson refuses the type. */
    record Clash(@JsonProperty("x") String a, @JsonProperty("x") String b) {
    }

    static class GetClashOperation implements QueryOperation<Void, Clash> {
        @Override
        public Clash execute(Void input) {
            throw new UnsupportedOperationException("not called");
        }
    }

    record ReviewSearch(@Nullable AuthorFilter filter) {
    }

    /** Jackson writes its own names; a header carries the constant names. */
    enum Availability {
        @JsonProperty("in-stock")
        IN_STOCK, @JsonProperty("gone")
        GONE
    }

    record Stock(UUID book, Availability availability, Optional<Availability> previous, List<Availability> history) {
    }

    record WhoAmI(@Nullable String channel, @Nullable String agent) {
    }

    /** Jackson reads and writes its own names; the {@code ConversionService} binds the constant names. */
    enum Shelving {
        @JsonProperty("on-shelf")
        ON_SHELF, @JsonProperty("lent")
        LENT
    }

    record CalendarRequest(Month month, @Nullable Shelving shelving, @Nullable Month since,
            @Nullable List<Shelving> any, Optional<DayOfWeek> day, @Nullable Shelving preferred) {
    }

    record ShelveRequest(UUID id, Shelving shelving) {
    }

    static class CalendarOperation implements QueryOperation<CalendarRequest, List<Shelf>> {
        @Override
        public List<Shelf> execute(CalendarRequest input) {
            return List.of();
        }
    }

    static class ShelveOperation implements CommandOperation<ShelveRequest, Shelf> {
        @Override
        public Shelf execute(ShelveRequest input) {
            throw new UnsupportedOperationException("not called");
        }
    }

    record CoverView(UUID id, @Nullable Image image) {
    }

    record ShelfRequest(UUID id) {
    }

    record RenameShelf(UUID shelfId, String name) {
    }

    record Shelf(UUID id, String name) {
    }

    /** An application type with the name of Synapse's problem component. */
    record SynapseProblem(String reason) {
    }

    static class GetBookOperation implements QueryOperation<GetBookRequest, Optional<Book>> {
        @Override
        public Optional<Book> execute(GetBookRequest input) {
            return Optional.empty();
        }
    }

    static class SearchBooksOperation implements QueryOperation<SearchBooksRequest, PageResult<Book>> {
        @Override
        public PageResult<Book> execute(SearchBooksRequest input) {
            return PageResult.of(List.of(), false);
        }
    }

    @OperationGroup("books")
    static class GetCatalogStatsOperation implements QueryOperation<Void, CatalogStats> {
        @Override
        public CatalogStats execute(Void input) {
            return new CatalogStats(0, 0);
        }
    }

    static class CreateBookOperation implements CommandOperation<CreateBookRequest, Book> {
        @Override
        public Book execute(CreateBookRequest input) {
            throw new UnsupportedOperationException("not called");
        }
    }

    static class UpdateBookOperation implements CommandOperation<UpdateBookRequest, Book> {
        @Override
        public Book execute(UpdateBookRequest input) {
            throw new UnsupportedOperationException("not called");
        }
    }

    static class DeleteBookOperation implements CommandOperation<DeleteBookRequest, Void> {
        @Override
        public Void execute(DeleteBookRequest input) {
            throw new UnsupportedOperationException("not called");
        }
    }

    static class SearchAuthorsOperation implements QueryOperation<AuthorSearch, List<Author>> {
        @Override
        public List<Author> execute(AuthorSearch input) {
            return List.of();
        }
    }

    static class LendBookOperation implements CommandOperation<LoanRequest, Loan> {
        @Override
        public Loan execute(LoanRequest input) {
            return new Loan(input.id(), false);
        }
    }

    static class FindCoverOperation implements QueryOperation<GetBookRequest, Optional<Cover>> {
        @Override
        public Optional<Cover> execute(GetBookRequest input) {
            return Optional.empty();
        }
    }

    static class SubmitJobOperation implements CommandOperation<JobRequest, Job> {
        @Override
        public Job execute(JobRequest input) {
            throw new UnsupportedOperationException("not called");
        }
    }

    static class UploadCoverOperation implements CommandOperation<CoverUpload, Void> {
        @Override
        public Void execute(CoverUpload input) {
            throw new UnsupportedOperationException("not called");
        }
    }

    static class GetQuotaOperation implements QueryOperation<Void, Quota> {
        @Override
        public Quota execute(Void input) {
            return new Quota(10, 5);
        }
    }

    static class SaveDraftOperation implements CommandOperation<DraftRequest, Draft> {
        @Override
        public Draft execute(DraftRequest input) {
            throw new UnsupportedOperationException("not called");
        }
    }

    /** A create-only draft: its {@code If-None-Match} condition isn't {@code @Nullable}, so it is required. */
    record NewDraftRequest(UUID id, String text, VersionCondition<Long> unless) {
    }

    static class CreateDraftOperation implements CommandOperation<NewDraftRequest, Draft> {
        @Override
        public Draft execute(NewDraftRequest input) {
            throw new UnsupportedOperationException("not called");
        }
    }

    static class BookExistsOperation implements QueryOperation<GetBookRequest, Book> {
        @Override
        public Book execute(GetBookRequest input) {
            throw new UnsupportedOperationException("not called");
        }
    }

    static class PagesOperation implements QueryOperation<PageQuery, PageResult<Book>> {
        @Override
        public PageResult<Book> execute(PageQuery input) {
            return PageResult.of(List.of(), false);
        }
    }

    static class TraceOperation implements QueryOperation<TraceRequest, Draft> {
        @Override
        public Draft execute(TraceRequest input) {
            throw new UnsupportedOperationException("not called");
        }
    }

    static class GetOpaqueOperation implements QueryOperation<Void, Wrapper> {
        @Override
        public Wrapper execute(Void input) {
            throw new UnsupportedOperationException("not called");
        }
    }

    static class GetEditionOperation implements QueryOperation<GetBookRequest, Book> {
        @Override
        public Book execute(GetBookRequest input) {
            throw new UnsupportedOperationException("not called");
        }
    }

    static class SearchReviewsOperation implements QueryOperation<ReviewSearch, List<Author>> {
        @Override
        public List<Author> execute(ReviewSearch input) {
            return List.of();
        }
    }

    static class GetStockOperation implements QueryOperation<Void, Stock> {
        @Override
        public Stock execute(Void input) {
            throw new UnsupportedOperationException("not called");
        }
    }

    static class WhoAmIOperation implements QueryOperation<WhoAmI, Draft> {
        @Override
        public Draft execute(WhoAmI input) {
            throw new UnsupportedOperationException("not called");
        }
    }

    static class GetCoverViewOperation implements QueryOperation<GetBookRequest, CoverView> {
        @Override
        public CoverView execute(GetBookRequest input) {
            throw new UnsupportedOperationException("not called");
        }
    }

    static class GetShelfOperation implements QueryOperation<ShelfRequest, Shelf> {
        @Override
        public Shelf execute(ShelfRequest input) {
            throw new UnsupportedOperationException("not called");
        }
    }

    static class RenameShelfOperation implements CommandOperation<RenameShelf, Shelf> {
        @Override
        public Shelf execute(RenameShelf input) {
            throw new UnsupportedOperationException("not called");
        }
    }

    static class ListShelvesOperation implements QueryOperation<Void, List<Shelf>> {
        @Override
        public List<Shelf> execute(Void input) {
            return List.of();
        }
    }

    @OperationName("list-shelves-2")
    static class NamedOperation implements QueryOperation<Void, SynapseProblem> {
        @Override
        public SynapseProblem execute(Void input) {
            throw new UnsupportedOperationException("not called");
        }
    }

    static class GetOpaqueAgainOperation implements QueryOperation<Void, Wrapper> {
        @Override
        public Wrapper execute(Void input) {
            throw new UnsupportedOperationException("not called");
        }
    }
}
