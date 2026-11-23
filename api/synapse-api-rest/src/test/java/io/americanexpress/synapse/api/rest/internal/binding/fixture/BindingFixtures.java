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
package io.americanexpress.synapse.api.rest.internal.binding.fixture;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

import io.americanexpress.synapse.core.conditional.VersionCondition;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;
import io.americanexpress.synapse.data.restfixture.DataCapability;
import io.americanexpress.synapse.data.restfixture.DataRow;

/**
 * Operations and records used by the binding tests.
 */
public final class BindingFixtures {

    private static final BookResult BOOK = new BookResult(new UUID(0, 1), "T", 5);

    private BindingFixtures() {
    }

    public record GetBookRequest(UUID id) {
    }

    public record BookResult(UUID id, String title, int remaining) {
    }

    public record OtherRecord(String value) {
    }

    public enum Genre {
        FICTION
    }

    public record SearchBooksRequest(@Nullable String author, int page, @Nullable Integer limit, boolean available,
            @Nullable Genre genre, @Nullable LocalDate since, @Nullable List<String> tags, @Nullable UUID cursor,
            @Nullable List<UUID> ids) {
    }

    public record Filter(String text) {
    }

    public record GetWithBodyOnlyRequest(Filter filter) {
    }

    public record NullableFilterRequest(@Nullable Filter filter) {
    }

    public record CreateBookRequest(String title) {
    }

    public record WriteOptions(boolean dryRun, @Nullable String reason) {
    }

    public record UpdateBookRequest(UUID id, String title, WriteOptions options) {
    }

    public record BookChanges(String title) {
    }

    public record PatchBookRequest(UUID id, BookChanges changes) {
    }

    public record TenantId(String value) {
    }

    public record HeaderRequest(String channel, String session, String requestedBy, String region, TenantId tenant) {
    }

    public record DataBook(String title) implements DataCapability {
    }

    @jakarta.persistence.Entity
    public static class BookEntity {
    }

    public static class BookRow extends DataRow {
    }

    public record FileRequest(String name, String ext) {
    }

    public static class GetFileOperation implements QueryOperation<FileRequest, String> {
        @Override
        public String execute(FileRequest input) {
            return input.name() + "." + input.ext();
        }
    }

    public static class GetRowOperation implements QueryOperation<GetBookRequest, BookRow> {
        @Override
        public BookRow execute(GetBookRequest input) {
            return new BookRow();
        }
    }

    public static class GetBookOperation implements QueryOperation<GetBookRequest, Optional<BookResult>> {
        @Override
        public Optional<BookResult> execute(GetBookRequest input) {
            return Optional.of(BOOK);
        }
    }

    public static class GetPlainBookOperation implements QueryOperation<GetBookRequest, BookResult> {
        @Override
        public BookResult execute(GetBookRequest input) {
            return BOOK;
        }
    }

    public static class SearchBooksOperation implements QueryOperation<SearchBooksRequest, List<BookResult>> {
        @Override
        public List<BookResult> execute(SearchBooksRequest input) {
            return List.of(BOOK);
        }
    }

    public static class GetWithBodyOnlyOperation implements QueryOperation<GetWithBodyOnlyRequest, BookResult> {
        @Override
        public BookResult execute(GetWithBodyOnlyRequest input) {
            return BOOK;
        }
    }

    public static class GetWithNullableFilterOperation implements QueryOperation<NullableFilterRequest, BookResult> {
        @Override
        public BookResult execute(NullableFilterRequest input) {
            return BOOK;
        }
    }

    public static class CreateBookOperation implements CommandOperation<CreateBookRequest, BookResult> {
        @Override
        public BookResult execute(CreateBookRequest input) {
            return BOOK;
        }
    }

    public static class UpdateBookOperation implements CommandOperation<UpdateBookRequest, BookResult> {
        @Override
        public BookResult execute(UpdateBookRequest input) {
            return BOOK;
        }
    }

    public static class PatchBookOperation implements CommandOperation<PatchBookRequest, BookResult> {
        @Override
        public BookResult execute(PatchBookRequest input) {
            return BOOK;
        }
    }

    public static class HeadersOperation implements QueryOperation<HeaderRequest, Optional<BookResult>> {
        @Override
        public Optional<BookResult> execute(HeaderRequest input) {
            return Optional.of(BOOK);
        }
    }

    public static class GetEntityOperation implements QueryOperation<GetBookRequest, Optional<BookEntity>> {
        @Override
        public Optional<BookEntity> execute(GetBookRequest input) {
            return Optional.of(new BookEntity());
        }
    }

    public static class SaveEntityOperation implements CommandOperation<BookEntity, BookResult> {
        @Override
        public BookResult execute(BookEntity input) {
            return BOOK;
        }
    }

    public static class GetDataOperation implements QueryOperation<GetBookRequest, List<DataBook>> {
        @Override
        public List<DataBook> execute(GetBookRequest input) {
            return List.of();
        }
    }

    public static class EchoOperation implements QueryOperation<String, String> {
        @Override
        public String execute(String input) {
            return input;
        }
    }

    public record FindBooksRequest(@Nullable List<String> author, @Nullable Integer limit,
            @Nullable WriteOptions options) {
    }

    public static class FindBooksOperation implements QueryOperation<FindBooksRequest, List<BookResult>> {
        @Override
        public List<BookResult> execute(FindBooksRequest input) {
            return List.of(BOOK);
        }
    }

    public record StockBookRequest(String title, int copies) {
    }

    public static class StockBookOperation implements CommandOperation<StockBookRequest, BookResult> {
        @Override
        public BookResult execute(StockBookRequest input) {
            return BOOK;
        }
    }

    public record AnnotateBookRequest(UUID id, String channel, String session, String requestedBy, String region,
            String note, String text) {
    }

    public static class AnnotateBookOperation implements CommandOperation<AnnotateBookRequest, BookResult> {
        @Override
        public BookResult execute(AnnotateBookRequest input) {
            return BOOK;
        }
    }

    /** The last exception {@link CheckedBookRequest} threw, so a test can tell it was passed through, not wrapped. */
    public static final AtomicReference<@Nullable SynapseException> BLANK_TITLE = new AtomicReference<>();

    public record CheckedBookRequest(String title) {
        public CheckedBookRequest {
            if (title.isBlank()) {
                SynapseException blank = new SynapseException(SynapseErrors.CONFLICT);
                BLANK_TITLE.set(blank);
                throw blank;
            }
        }
    }

    /** Fails with an exception that isn't Jackson's and carries an internal message. */
    public static class ThrowingDeserializer extends ValueDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) {
            throw new IllegalArgumentException("SECRET-internal");
        }
    }

    public record CodedRequest(@JsonDeserialize(using = ThrowingDeserializer.class) String code, String title) {
    }

    public static class CodedOperation implements CommandOperation<CodedRequest, BookResult> {
        @Override
        public BookResult execute(CodedRequest input) {
            return BOOK;
        }
    }

    /** Reads a string in upper case. */
    public static class UpperCaseDeserializer extends ValueDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) {
            return parser.getString().toUpperCase(Locale.ROOT);
        }
    }

    public record ShoutedRequest(@JsonDeserialize(using = UpperCaseDeserializer.class) String code, String title) {
    }

    public static class ShoutedOperation implements CommandOperation<ShoutedRequest, BookResult> {
        @Override
        public BookResult execute(ShoutedRequest input) {
            return BOOK;
        }
    }

    /** Fails with an exception that isn't Jackson's and carries an internal message. */
    public static class ThrowingRecordDeserializer extends ValueDeserializer<ThrowingRecordRequest> {
        @Override
        public ThrowingRecordRequest deserialize(JsonParser parser, DeserializationContext context) {
            throw new IllegalStateException("SECRET-record");
        }
    }

    @JsonDeserialize(using = ThrowingRecordDeserializer.class)
    public record ThrowingRecordRequest(String title) {
    }

    public static class ThrowingRecordOperation implements CommandOperation<ThrowingRecordRequest, BookResult> {
        @Override
        public BookResult execute(ThrowingRecordRequest input) {
            return BOOK;
        }
    }

    /** A query record that rejects an inverted range, with an internal message. */
    public record RangeQuery(int from, int to) {
        public RangeQuery {
            if (from > to) {
                throw new IllegalArgumentException("SECRET range " + from + ">" + to);
            }
        }
    }

    public record RangeSearchRequest(@Nullable RangeQuery range, String title) {
    }

    public static class RangeSearchOperation implements CommandOperation<RangeSearchRequest, BookResult> {
        @Override
        public BookResult execute(RangeSearchRequest input) {
            return BOOK;
        }
    }

    public static class CheckedBookOperation implements CommandOperation<CheckedBookRequest, BookResult> {
        @Override
        public BookResult execute(CheckedBookRequest input) {
            return BOOK;
        }
    }

    public record PublishBookRequest(LocalDate publishedOn, @JsonProperty("isbn13") String isbn) {
    }

    public record AuditedBookRequest(String title, @JsonIgnore @Nullable String auditNote) {
    }

    /** Three components Jackson never reads from JSON: ignored on the class, ignored on the component, read-only. */
    @JsonIgnoreProperties({"secret"})
    public record GuardedRequest(String name, @JsonIgnore @Nullable String note,
            @JsonProperty(access = JsonProperty.Access.READ_ONLY) @Nullable String role, @Nullable String secret) {
    }

    public static class GuardedOperation implements CommandOperation<GuardedRequest, BookResult> {
        @Override
        public BookResult execute(GuardedRequest input) {
            return BOOK;
        }
    }

    public record ScheduleBookRequest(String title, @JsonFormat(pattern = "dd/MM/yyyy") LocalDate releaseOn) {
    }

    public static class ScheduleBookOperation implements CommandOperation<ScheduleBookRequest, BookResult> {
        @Override
        public BookResult execute(ScheduleBookRequest input) {
            return BOOK;
        }
    }

    /** Its compact constructor rejects a null id, as a record that checks its own invariants does. */
    public record StrictBookRequest(UUID id, String title) {
        public StrictBookRequest {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(title, "title");
        }
    }

    public static class StrictBookOperation implements CommandOperation<StrictBookRequest, BookResult> {
        @Override
        public BookResult execute(StrictBookRequest input) {
            return BOOK;
        }
    }

    /** Its compact constructor rejects a null date, so a date Jackson reads as null must never reach it. */
    public record DatedRequest(String title, LocalDate releaseOn) {
        public DatedRequest {
            Objects.requireNonNull(releaseOn, "releaseOn");
        }
    }

    public static class DatedOperation implements CommandOperation<DatedRequest, BookResult> {
        @Override
        public BookResult execute(DatedRequest input) {
            return BOOK;
        }
    }

    /** Reads only the title and copies; fails when the title is missing. */
    public static class CustomBookDeserializer extends ValueDeserializer<CustomBookRequest> {
        @Override
        public CustomBookRequest deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode tree = context.readTree(parser);
            return new CustomBookRequest(null, tree.required("title").asString(), tree.path("copies").asInt());
        }
    }

    @JsonDeserialize(using = CustomBookDeserializer.class)
    public record CustomBookRequest(@Nullable UUID id, String title, int copies) {
    }

    public static class CustomBookOperation implements CommandOperation<CustomBookRequest, BookResult> {
        @Override
        public BookResult execute(CustomBookRequest input) {
            return BOOK;
        }
    }

    /**
     * Read through a delegating factory, from a map with its own keys ({@code name}, {@code count}); its canonical
     * constructor's names ({@code title}, {@code copies}) are not what the mapper reads.
     */
    public record DelegatedBookRequest(@Nullable UUID id, String title, int copies) {

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static DelegatedBookRequest of(Map<String, Object> fields) {
            Object count = fields.get("count");
            return new DelegatedBookRequest(null, String.valueOf(fields.get("name")),
                    count instanceof Number number ? number.intValue() : 0);
        }
    }

    public static class DelegatedBookOperation implements CommandOperation<DelegatedBookRequest, BookResult> {
        @Override
        public BookResult execute(DelegatedBookRequest input) {
            return BOOK;
        }
    }

    public record PathDatedRequest(@JsonFormat(pattern = "dd/MM/yyyy") LocalDate day, String title) {
    }

    public static class PathDatedOperation implements CommandOperation<PathDatedRequest, BookResult> {
        @Override
        public BookResult execute(PathDatedRequest input) {
            return BOOK;
        }
    }

    /** Its compact constructor rejects a negative count, with a message that must never reach the caller. */
    public record CountedBookRequest(String title, int copies) {
        public CountedBookRequest {
            if (copies < 0) {
                throw new IllegalArgumentException("internal: copies " + copies + " below zero");
            }
        }
    }

    public static class CountedBookOperation implements CommandOperation<CountedBookRequest, BookResult> {
        @Override
        public BookResult execute(CountedBookRequest input) {
            return BOOK;
        }
    }

    /** A non-nullable component Jackson never reads: it can't be bound from the body. */
    public record HiddenRequest(String name, @JsonIgnore String internal) {
    }

    public static class HiddenOperation implements CommandOperation<HiddenRequest, BookResult> {
        @Override
        public BookResult execute(HiddenRequest input) {
            return BOOK;
        }
    }

    public record AliasedRequest(@JsonAlias("heading") String title) {
    }

    /** Two components with one JSON name: Jackson can't build a deserializer for it. */
    public record ClashRequest(@JsonProperty("x") String first, @JsonProperty("x") String second) {
    }

    public static class ClashOperation implements CommandOperation<ClashRequest, BookResult> {
        @Override
        public BookResult execute(ClashRequest input) {
            return BOOK;
        }
    }

    public static class AliasedOperation implements CommandOperation<AliasedRequest, BookResult> {
        @Override
        public BookResult execute(AliasedRequest input) {
            return BOOK;
        }
    }

    public static class AuditBookOperation implements CommandOperation<AuditedBookRequest, BookResult> {
        @Override
        public BookResult execute(AuditedBookRequest input) {
            return BOOK;
        }
    }

    /** Sort keys: the wire names are {@code title}, {@code year} and {@code authorName}. */
    public enum SortKey {
        TITLE,
        @JsonProperty("year")
        PUBLISHED_YEAR,
        AUTHOR_NAME
    }

    /** A filter and the paging parameters. */
    public record PagedBooksRequest(@Nullable String author, PageQuery page) {
    }

    public static class PagedBooksOperation implements QueryOperation<PagedBooksRequest, PageResult<BookResult>> {
        @Override
        public PageResult<BookResult> execute(PagedBooksRequest input) {
            return new PageResult<>(List.of(BOOK), null, false, null);
        }
    }

    /** Pages through everything: the paging parameters are the whole input. */
    public static class SearchAllOperation implements QueryOperation<PageQuery, PageResult<BookResult>> {
        @Override
        public PageResult<BookResult> execute(PageQuery input) {
            return new PageResult<>(List.of(BOOK), null, false, null);
        }
    }

    public record TwoPagesRequest(PageQuery a, PageQuery b) {
    }

    public static class TwoPagesOperation implements QueryOperation<TwoPagesRequest, PageResult<BookResult>> {
        @Override
        public PageResult<BookResult> execute(TwoPagesRequest input) {
            return new PageResult<>(List.of(BOOK), null, false, null);
        }
    }

    /** A filter named like a paging parameter. */
    public record SizeFilterRequest(@Nullable String size, PageQuery page) {
    }

    public static class SizeFilterOperation implements QueryOperation<SizeFilterRequest, PageResult<BookResult>> {
        @Override
        public PageResult<BookResult> execute(SizeFilterRequest input) {
            return new PageResult<>(List.of(BOOK), null, false, null);
        }
    }

    /** The same filter on a route that doesn't page. */
    public record SizeOnlyRequest(@Nullable String size) {
    }

    public static class SizeOnlyOperation implements QueryOperation<SizeOnlyRequest, List<BookResult>> {
        @Override
        public List<BookResult> execute(SizeOnlyRequest input) {
            return List.of(BOOK);
        }
    }

    /** A filter that a renamed paging parameter may clash with. */
    public record PageSizeRequest(@Nullable Integer pageSize, PageQuery page) {
    }

    public static class PageSizeOperation implements QueryOperation<PageSizeRequest, PageResult<BookResult>> {
        @Override
        public PageResult<BookResult> execute(PageSizeRequest input) {
            return new PageResult<>(List.of(BOOK), null, false, null);
        }
    }

    /** A query filter before the paging parameters, so its violations come first. */
    public record LimitedPageRequest(@Nullable Integer limit, PageQuery page) {
    }

    public static class LimitedPageOperation implements QueryOperation<LimitedPageRequest, PageResult<BookResult>> {
        @Override
        public PageResult<BookResult> execute(LimitedPageRequest input) {
            return new PageResult<>(List.of(BOOK), null, false, null);
        }
    }

    /** Filters built from the query string, one of them named like a paging parameter. */
    public record OffsetFilter(@Nullable String offset, @Nullable String genre) {
    }

    public record FilteredPageRequest(@Nullable OffsetFilter filter, PageQuery page) {
    }

    public static class FilteredPageOperation implements QueryOperation<FilteredPageRequest, PageResult<BookResult>> {
        @Override
        public PageResult<BookResult> execute(FilteredPageRequest input) {
            return new PageResult<>(List.of(BOOK), null, false, null);
        }
    }

    public static class OptionalPageOperation
            implements
            QueryOperation<PagedBooksRequest, Optional<PageResult<BookResult>>> {
        @Override
        public Optional<PageResult<BookResult>> execute(PagedBooksRequest input) {
            return Optional.empty();
        }
    }

    public static class ListOfPagesOperation
            implements
            QueryOperation<PagedBooksRequest, List<PageResult<BookResult>>> {
        @Override
        public List<PageResult<BookResult>> execute(PagedBooksRequest input) {
            return List.of();
        }
    }

    public record Author(String name) {
    }

    /** An output with a version an ETag can carry, and components of types it can't. */
    public record VersionedBook(UUID id, String title, long version, Author author, Optional<String> edition) {
    }

    private static final VersionedBook VERSIONED = new VersionedBook(new UUID(0, 1), "T", 1, new Author("A"),
            Optional.empty());

    public static class GetVersionedBookOperation implements QueryOperation<GetBookRequest, Optional<VersionedBook>> {
        @Override
        public Optional<VersionedBook> execute(GetBookRequest input) {
            return Optional.of(VERSIONED);
        }
    }

    public static class CreateVersionedBookOperation implements CommandOperation<CreateBookRequest, VersionedBook> {
        @Override
        public VersionedBook execute(CreateBookRequest input) {
            return VERSIONED;
        }
    }

    public static class ListVersionedBooksOperation implements QueryOperation<GetBookRequest, List<VersionedBook>> {
        @Override
        public List<VersionedBook> execute(GetBookRequest input) {
            return List.of(VERSIONED);
        }
    }

    public static class PageVersionedBooksOperation
            implements
            QueryOperation<PagedBooksRequest, PageResult<VersionedBook>> {
        @Override
        public PageResult<VersionedBook> execute(PagedBooksRequest input) {
            return new PageResult<>(List.of(VERSIONED), null, false, null);
        }
    }

    /** A write whose version comes from {@code If-Match}. */
    public record ReviseBookRequest(UUID id, @Nullable VersionCondition<Long> version, String title) {
    }

    public static class ReviseBookOperation implements CommandOperation<ReviseBookRequest, VersionedBook> {
        @Override
        public VersionedBook execute(ReviseBookRequest input) {
            return VERSIONED;
        }
    }

    /** Like {@link ReviseBookRequest}, with a version that can't be null. */
    public record StrictReviseRequest(UUID id, long version, String title) {
    }

    public static class StrictReviseOperation implements CommandOperation<StrictReviseRequest, VersionedBook> {
        @Override
        public VersionedBook execute(StrictReviseRequest input) {
            return VERSIONED;
        }
    }

    public record RemoveBookRequest(UUID id, @Nullable VersionCondition<Long> version) {
    }

    public static class RemoveBookOperation implements CommandOperation<RemoveBookRequest, VersionedBook> {
        @Override
        public VersionedBook execute(RemoveBookRequest input) {
            return VERSIONED;
        }
    }

    /** A write whose tag components are strings, bound from a header other than {@code If-None-Match}. */
    public record TaggedBookRequest(UUID id, @Nullable String etag, @Nullable String other) {
    }

    public static class TaggedBookOperation implements CommandOperation<TaggedBookRequest, VersionedBook> {
        @Override
        public VersionedBook execute(TaggedBookRequest input) {
            return VERSIONED;
        }
    }

    /** A read whose tag is a plain string, bound from {@code If-None-Match}. */
    public record TaggedReadRequest(UUID id, @Nullable String tag) {
    }

    public static class GetTaggedBookOperation implements QueryOperation<TaggedReadRequest, VersionedBook> {
        @Override
        public VersionedBook execute(TaggedReadRequest input) {
            return VERSIONED;
        }
    }

    /** A read whose tag is a version condition, which reads may bind too. */
    public record ConditionalReadRequest(UUID id, @Nullable VersionCondition<Long> version) {
    }

    public static class GetConditionalBookOperation
            implements
            QueryOperation<ConditionalReadRequest, VersionedBook> {
        @Override
        public VersionedBook execute(ConditionalReadRequest input) {
            return VERSIONED;
        }
    }

    /** A read whose version condition can't be null, which a read can't require. */
    public record StrictConditionalReadRequest(UUID id, VersionCondition<Long> version) {
    }

    public static class GetStrictConditionalBookOperation
            implements
            QueryOperation<StrictConditionalReadRequest, VersionedBook> {
        @Override
        public VersionedBook execute(StrictConditionalReadRequest input) {
            return VERSIONED;
        }
    }

    /** A query record with a version condition, which no query parameter can fill. */
    public record ConditionFilter(@Nullable VersionCondition<Long> version) {
    }

    public record ConditionFilterRequest(@Nullable ConditionFilter filter) {
    }

    public static class ConditionFilterOperation implements QueryOperation<ConditionFilterRequest, VersionedBook> {
        @Override
        public VersionedBook execute(ConditionFilterRequest input) {
            return VERSIONED;
        }
    }

    /** A required version condition: on a write, a request without the header is answered 428. */
    public record StrictConditionRequest(UUID id, VersionCondition<Long> version) {
    }

    public static class StrictConditionOperation implements CommandOperation<StrictConditionRequest, VersionedBook> {
        @Override
        public VersionedBook execute(StrictConditionRequest input) {
            return VERSIONED;
        }
    }

    /** A create-only {@code PUT}: {@code If-None-Match: *} binds {@code AnyVersion}. */
    public record PutBookRequest(UUID id, @Nullable VersionCondition<Long> unless, String title) {
    }

    public static class PutBookOperation implements CommandOperation<PutBookRequest, VersionedBook> {
        @Override
        public VersionedBook execute(PutBookRequest input) {
            return VERSIONED;
        }
    }

    /**
     * Read whole by its own deserializer, which leaves the id and the version to the other sources; {@code If-Match} is
     * required.
     */
    @JsonDeserialize(using = WholeRevisionDeserializer.class)
    public record WholeRevisionRequest(UUID id, VersionCondition<Long> version, String title) {
    }

    public static class WholeRevisionDeserializer extends ValueDeserializer<WholeRevisionRequest> {
        @Override
        @SuppressWarnings("NullAway") // The path and If-Match fill id and version once Jackson has read the body.
        public WholeRevisionRequest deserialize(JsonParser parser, DeserializationContext context) {
            return new WholeRevisionRequest(null, null, context.readTree(parser).required("title").asString());
        }
    }

    public static class WholeRevisionOperation implements CommandOperation<WholeRevisionRequest, VersionedBook> {
        @Override
        public VersionedBook execute(WholeRevisionRequest input) {
            return VERSIONED;
        }
    }

    /** As {@link WholeRevisionRequest}, but its constructor rejects a null version, so Jackson can't build it. */
    @JsonDeserialize(using = GuardedRevisionDeserializer.class)
    public record GuardedRevisionRequest(UUID id, VersionCondition<Long> version, String title) {
        public GuardedRevisionRequest {
            Objects.requireNonNull(version, "version");
        }
    }

    public static class GuardedRevisionDeserializer extends ValueDeserializer<GuardedRevisionRequest> {
        @Override
        @SuppressWarnings("NullAway") // Deliberately what a deserializer that ignores the other sources does.
        public GuardedRevisionRequest deserialize(JsonParser parser, DeserializationContext context) {
            return new GuardedRevisionRequest(null, null, context.readTree(parser).required("title").asString());
        }
    }

    public static class GuardedRevisionOperation implements CommandOperation<GuardedRevisionRequest, VersionedBook> {
        @Override
        public VersionedBook execute(GuardedRevisionRequest input) {
            return VERSIONED;
        }
    }

    /** A write that requires both {@code If-Match} and {@code If-None-Match}. */
    public record StrictBothRequest(UUID id, VersionCondition<Long> version, VersionCondition<Long> unless) {
    }

    public static class StrictBothOperation implements CommandOperation<StrictBothRequest, VersionedBook> {
        @Override
        public VersionedBook execute(StrictBothRequest input) {
            return VERSIONED;
        }
    }

    /** A {@code PUT} that requires {@code If-None-Match}: its condition can't be null. */
    public record StrictPutBookRequest(UUID id, VersionCondition<Long> unless, String title) {
    }

    public static class StrictPutBookOperation implements CommandOperation<StrictPutBookRequest, VersionedBook> {
        @Override
        public VersionedBook execute(StrictPutBookRequest input) {
            return VERSIONED;
        }
    }

    /** A write with a plain version type, which a write can't bind from {@code If-Match} or {@code If-None-Match}. */
    public record PlainVersionRequest(UUID id, @Nullable Long version, @Nullable String title) {
    }

    public static class PlainVersionOperation implements CommandOperation<PlainVersionRequest, VersionedBook> {
        @Override
        public VersionedBook execute(PlainVersionRequest input) {
            return VERSIONED;
        }
    }

    /**
     * Version conditions without a usable version type (raw, wildcard, {@code Object}, and one no string converts
     * to), and version conditions wrapped in another type.
     */
    @SuppressWarnings("rawtypes")
    public record LooseConditionRequest(UUID id, @Nullable VersionCondition raw,
            @Nullable VersionCondition<?> wildcard, @Nullable VersionCondition<Object> object,
            @Nullable VersionCondition<PairFilter> pair, @Nullable List<VersionCondition<Long>> list,
            @Nullable Optional<VersionCondition<Long>> optional) {
    }

    public static class LooseConditionOperation implements CommandOperation<LooseConditionRequest, VersionedBook> {
        @Override
        public VersionedBook execute(LooseConditionRequest input) {
            return VERSIONED;
        }
    }

    /**
     * Version types that a {@code String} is assignable to: the binder keeps the header's text as is, so only
     * {@code String} and {@code CharSequence} can equal the version the operation reads.
     */
    public record SupertypeConditionRequest(UUID id, @Nullable VersionCondition<Serializable> serializable,
            @Nullable VersionCondition<Comparable<?>> comparable, @Nullable VersionCondition<CharSequence> text,
            @Nullable VersionCondition<String> string) {
    }

    public static class SupertypeConditionOperation
            implements CommandOperation<SupertypeConditionRequest, VersionedBook> {
        @Override
        public VersionedBook execute(SupertypeConditionRequest input) {
            return VERSIONED;
        }
    }

    /** No constructor or factory method takes a String, so no conversion service can make one from a string. */
    public record PairFilter(int a, int b) {
    }

    /** One String component: its canonical constructor takes a String. */
    public record Isbn(String value) {
    }

    public record PairQuery(@Nullable PairFilter pair) {
    }

    public record ConvertRequest(@Nullable PairFilter filter, @Nullable Isbn isbn, @Nullable PairQuery nested) {
    }

    public static class ConvertOperation implements QueryOperation<ConvertRequest, BookResult> {
        @Override
        public BookResult execute(ConvertRequest input) {
            return BOOK;
        }
    }

    public record PathFilterRequest(PairFilter filter) {
    }

    public static class PathFilterOperation implements QueryOperation<PathFilterRequest, BookResult> {
        @Override
        public BookResult execute(PathFilterRequest input) {
            return BOOK;
        }
    }

    public record SimpleSourcesRequest(UUID id, @Nullable List<String> tags, @Nullable Long version,
            @Nullable LocalDate since) {
    }

    public static class SimpleSourcesOperation implements QueryOperation<SimpleSourcesRequest, BookResult> {
        @Override
        public BookResult execute(SimpleSourcesRequest input) {
            return BOOK;
        }
    }

    public static class PingOperation implements QueryOperation<Void, String> {
        @Override
        public String execute(Void input) {
            return "pong";
        }
    }

    /** An output with a page among its components. */
    public record BookShelf(String name, PageResult<BookResult> books) {
    }

    public static class GetBookShelfOperation implements QueryOperation<Void, BookShelf> {
        @Override
        public BookShelf execute(Void input) {
            return new BookShelf("s", PageResult.of(List.of(BOOK), false));
        }
    }

    public static class ListBookShelvesOperation implements QueryOperation<Void, List<Optional<BookShelf>>> {
        @Override
        public List<Optional<BookShelf>> execute(Void input) {
            return List.of();
        }
    }

    public static class PageOfBookShelvesOperation implements QueryOperation<PagedBooksRequest, PageResult<BookShelf>> {
        @Override
        public PageResult<BookShelf> execute(PagedBooksRequest input) {
            return PageResult.of(List.of(), false);
        }
    }

    /** A class, not a record, that a request can't put into a cursor digest. */
    public static final class Principal {

        private final String name;

        public Principal(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }
    }

    public record PrincipalPageRequest(@Nullable Principal principal, PageQuery page) {
    }

    public static class PrincipalPageOperation
            implements
            QueryOperation<PrincipalPageRequest, PageResult<BookResult>> {
        @Override
        public PageResult<BookResult> execute(PrincipalPageRequest input) {
            return PageResult.of(List.of(BOOK), false);
        }
    }

    public static class PrincipalBookOperation implements QueryOperation<PrincipalPageRequest, BookResult> {
        @Override
        public BookResult execute(PrincipalPageRequest input) {
            return BOOK;
        }
    }

    public record PrincipalRequest(@Nullable Principal principal) {
    }

    public static class PrincipalOnlyOperation implements QueryOperation<PrincipalRequest, BookResult> {
        @Override
        public BookResult execute(PrincipalRequest input) {
            return BOOK;
        }
    }

    public record Tagged(String tag, @Nullable List<Isbn> isbns) {
    }

    /** Every kind of component a cursor digest accepts. */
    public record DigestiblePageRequest(@Nullable Isbn isbn, @Nullable SortKey key, @Nullable List<String> tags,
            @Nullable Set<UUID> ids, @Nullable Map<String, LocalDate> dates, @Nullable Optional<Tagged> tagged,
            PageQuery page) {
    }

    public static class DigestiblePageOperation
            implements
            CommandOperation<DigestiblePageRequest, PageResult<BookResult>> {
        @Override
        public PageResult<BookResult> execute(DigestiblePageRequest input) {
            return PageResult.of(List.of(BOOK), false);
        }
    }

    /** A generic wrapper: {@code Holder<String>} and {@code Holder<Principal>} are different types. */
    public record Holder<T>(T value) {
    }

    /** A digestible holder, then one of a class the digest can't write. */
    public record HolderContext(Holder<String> region, Holder<Principal> who) {
    }

    public record HolderPageRequest(@Nullable HolderContext context, PageQuery page) {
    }

    public static class HolderPageOperation implements CommandOperation<HolderPageRequest, PageResult<BookResult>> {
        @Override
        public PageResult<BookResult> execute(HolderPageRequest input) {
            return PageResult.of(List.of(BOOK), false);
        }
    }

    /** A plain holder, then one of a page. */
    public record HolderOutput(Holder<String> label, Holder<PageResult<String>> page) {
    }

    public static class GetHolderOutputOperation implements QueryOperation<Void, HolderOutput> {
        @Override
        public HolderOutput execute(Void input) {
            return new HolderOutput(new Holder<>("l"), new Holder<>(PageResult.of(List.of(), false)));
        }
    }

    /** A genuinely recursive record. */
    public record TreeNode(String value, List<TreeNode> children) {
    }

    public record TreePageRequest(@Nullable TreeNode root, PageQuery page) {
    }

    public static class TreePageOperation implements CommandOperation<TreePageRequest, PageResult<TreeNode>> {
        @Override
        public PageResult<TreeNode> execute(TreePageRequest input) {
            return PageResult.of(List.of(), false);
        }
    }

    /**
     * A generic record that branches and grows: every level doubles the parameterizations to walk
     * ({@code Grow<List<T>>} and {@code Grow<Set<T>>}), and none repeats.
     */
    public record Grow<T>(T value, Optional<Grow<List<T>>> listed, Optional<Grow<Set<T>>> collected) {
    }

    public static class GetGrowOperation implements QueryOperation<Void, Grow<String>> {
        @Override
        public Grow<String> execute(Void input) {
            return new Grow<>("a", Optional.empty(), Optional.empty());
        }
    }

    /** A page of pages: the inner page would be written with its raw cursor. */
    public static class PageOfPagesOperation implements QueryOperation<PageQuery, PageResult<PageResult<String>>> {
        @Override
        public PageResult<PageResult<String>> execute(PageQuery input) {
            return PageResult.of(List.of(), false);
        }
    }

    /** Single and list values, from the query string and from headers, for the one-value rule. */
    public record RepeatedRequest(@Nullable Long id, @Nullable String q, @Nullable List<String> tag,
            @Nullable Long requestId, @Nullable List<String> labels, @Nullable IdFilter filter) {
    }

    /** A query record with a single-valued component. */
    public record IdFilter(@Nullable Long owner) {
    }

    public static class RepeatedOperation implements QueryOperation<RepeatedRequest, List<BookResult>> {
        @Override
        public List<BookResult> execute(RepeatedRequest input) {
            return List.of(BOOK);
        }
    }

    /** Read with a SNAKE_CASE mapper: the JSON names differ from the component names. */
    public record EditionRequest(@JsonAlias("year") Integer publishedYear, @JsonProperty("yr") Integer printYear) {
    }

    public static class EditionOperation implements CommandOperation<EditionRequest, BookResult> {
        @Override
        public BookResult execute(EditionRequest input) {
            return BOOK;
        }
    }

    /** Components whose declared source names differ from their names. */
    public record SourcedRequest(Long id, @Nullable Integer sort, @Nullable Long requestId, @Nullable Integer session,
            @Nullable String title) {
    }

    public static class SourcedOperation implements CommandOperation<SourcedRequest, BookResult> {
        @Override
        public BookResult execute(SourcedRequest input) {
            return BOOK;
        }
    }

    /** A list component, for the rule that an unsent collection binds empty, as a gRPC {@code repeated} field does. */
    public record LabelsRequest(List<String> tags) {
    }

    public static class LabelsOperation implements QueryOperation<LabelsRequest, BookResult> {
        @Override
        public BookResult execute(LabelsRequest input) {
            return BOOK;
        }
    }

    public static class LabelsCommandOperation implements CommandOperation<LabelsRequest, BookResult> {
        @Override
        public BookResult execute(LabelsRequest input) {
            return BOOK;
        }
    }

    /** A query record with a list component. */
    public record LabelFilter(@Nullable String owner, List<String> tag) {
    }

    public record LabelFilterRequest(LabelFilter filter) {
    }

    public static class LabelFilterOperation implements QueryOperation<LabelFilterRequest, BookResult> {
        @Override
        public BookResult execute(LabelFilterRequest input) {
            return BOOK;
        }
    }

    /** Every collection and map type the empty rule names, as top-level components. */
    public record ContainersRequest(Collection<String> all, Set<String> labels, SortedSet<String> sorted,
            NavigableSet<String> navigable, Map<String, Integer> counts, SortedMap<String, Integer> sortedCounts,
            NavigableMap<String, Integer> navigableCounts) {
    }

    public static class ContainersOperation implements CommandOperation<ContainersRequest, BookResult> {
        @Override
        public BookResult execute(ContainersRequest input) {
            return BOOK;
        }
    }

    /** A nested record with collection and map components, a {@code @Nullable} one included. */
    public record Writer(String name, List<String> aliases, @Nullable List<String> notes, Set<String> genres,
            Map<String, Integer> ranks, SortedSet<String> sorted, NavigableMap<String, Integer> shelves) {
    }

    public record WriterRequest(Writer writer) {
    }

    public static class WriterOperation implements CommandOperation<WriterRequest, BookResult> {
        @Override
        public BookResult execute(WriterRequest input) {
            return BOOK;
        }
    }

    /** A nested record whose list component is declared with a concrete type. */
    @SuppressWarnings("NonApiType") // The concrete type is the case under test.
    public record Pen(String name, ArrayList<String> aliases) {
    }

    public record PenRequest(Pen pen) {
    }

    public static class PenOperation implements CommandOperation<PenRequest, BookResult> {
        @Override
        public BookResult execute(PenRequest input) {
            return BOOK;
        }
    }

    public record NullableLabelsRequest(@Nullable List<String> tags) {
    }

    public static class NullableLabelsOperation implements CommandOperation<NullableLabelsRequest, BookResult> {
        @Override
        public BookResult execute(NullableLabelsRequest input) {
            return BOOK;
        }
    }

    /** An array component, which keeps {@code must not be null}. */
    public record CodesRequest(String[] codes) {
    }

    public static class CodesOperation implements CommandOperation<CodesRequest, BookResult> {
        @Override
        public BookResult execute(CodesRequest input) {
            return BOOK;
        }
    }

    /** A list component whose own null handling rejects null. */
    public record StrictLabelsRequest(@JsonSetter(nulls = Nulls.FAIL) List<String> tags) {
    }

    public static class StrictLabelsOperation implements CommandOperation<StrictLabelsRequest, BookResult> {
        @Override
        public BookResult execute(StrictLabelsRequest input) {
            return BOOK;
        }
    }

    /** Holds a record whose component rejects null. */
    public record StrictShelfRequest(StrictLabelsRequest shelf) {
    }

    public static class StrictShelfOperation implements CommandOperation<StrictShelfRequest, BookResult> {
        @Override
        public BookResult execute(StrictShelfRequest input) {
            return BOOK;
        }
    }

    /** A nested record with a non-null string, a primitive, a {@code @Nullable} component and a collection. */
    public record Dock(String warehouseCode, int bin, @Nullable String note, List<String> labels) {
    }

    /** Nested records at every depth: a component, list items, map values, set items and a nullable one. */
    public record ShipmentRequest(String sku, Dock origin, List<Dock> stops, Map<String, Dock> docksByName,
            Set<Dock> spares, @Nullable Dock backup) {
    }

    public static class ShipmentOperation implements CommandOperation<ShipmentRequest, BookResult> {
        @Override
        public BookResult execute(ShipmentRequest input) {
            return BOOK;
        }
    }

    /** A record bound to the whole body ({@code .body(...)}) next to a path variable. */
    public record MoveDockRequest(UUID id, Dock dock) {
    }

    public static class MoveDockOperation implements CommandOperation<MoveDockRequest, BookResult> {
        @Override
        public BookResult execute(MoveDockRequest input) {
            return BOOK;
        }
    }

    /** Reads a dock from its warehouse code alone. */
    public static class LenientDockDeserializer extends ValueDeserializer<Dock> {
        @Override
        public Dock deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode tree = context.readTree(parser);
            return new Dock(tree.path("warehouseCode").asString(), 0, null, List.of());
        }
    }

    /** Nested values Jackson doesn't read property by property, so their own deserializers decide what is needed. */
    public record OwnReadersRequest(CustomBookRequest custom, DelegatedBookRequest delegated,
            @JsonDeserialize(using = LenientDockDeserializer.class) Dock lenient,
            @JsonDeserialize(contentUsing = LenientDockDeserializer.class) List<Dock> lenientItems) {
    }

    public static class OwnReadersOperation implements CommandOperation<OwnReadersRequest, BookResult> {
        @Override
        public BookResult execute(OwnReadersRequest input) {
            return BOOK;
        }
    }

    /** Holds the values with their own readers one level down. */
    public record ReadersHolderRequest(OwnReadersRequest readers) {
    }

    public static class ReadersHolderOperation implements CommandOperation<ReadersHolderRequest, BookResult> {
        @Override
        public BookResult execute(ReadersHolderRequest input) {
            return BOOK;
        }
    }

    /** A whole-body list of nested records. */
    public record DocksRequest(List<Dock> docks) {
    }

    public static class DocksOperation implements CommandOperation<DocksRequest, BookResult> {
        @Override
        public BookResult execute(DocksRequest input) {
            return BOOK;
        }
    }

    /** Nested records inside an {@code Optional} and an array. */
    @SuppressWarnings("ArrayRecordComponent")
    public record YardRequest(String sku, Optional<Dock> spare, Dock[] berths) {
    }

    public static class YardOperation implements CommandOperation<YardRequest, BookResult> {
        @Override
        public BookResult execute(YardRequest input) {
            return BOOK;
        }
    }

    /** Two components under one JSON name: Jackson can't describe the record. */
    public record ClashingDock(@JsonProperty("code") String warehouseCode, @JsonProperty("code") String bin) {
    }

    /** A whole-body list of records Jackson can't describe, which no startup check reads. */
    public record ClashingDocksRequest(List<ClashingDock> docks) {
    }

    public static class ClashingDocksOperation implements CommandOperation<ClashingDocksRequest, BookResult> {
        @Override
        public BookResult execute(ClashingDocksRequest input) {
            return BOOK;
        }
    }

    /**
     * Items of every kind: non-null scalars, primitives and records, @Nullable ones (JSpecify's syntax), and
     * collections, which bind empty.
     */
    @SuppressWarnings("ArrayRecordComponent")
    public record ItemsRequest(List<String> tags, Map<String, String> labels, int[] counts, List<@Nullable Dock> gaps,
            Map<String, @Nullable String> notes, @Nullable Dock[] holes, List<List<Dock>> rows) {
    }

    public static class ItemsOperation implements CommandOperation<ItemsRequest, BookResult> {
        @Override
        public BookResult execute(ItemsRequest input) {
            return BOOK;
        }
    }

    /**
     * Free-form items: {@code JsonNode} and {@code Optional} item types are non-null like any other, and the
     * {@code @Nullable} ones keep a JSON null (as {@code NullNode}).
     */
    public record FreeFormRequest(Map<String, JsonNode> attrs, List<Optional<String>> opts,
            Map<String, @Nullable JsonNode> loose, List<@Nullable JsonNode> nodes) {
    }

    public static class FreeFormOperation implements CommandOperation<FreeFormRequest, BookResult> {
        @Override
        public BookResult execute(FreeFormRequest input) {
            return BOOK;
        }
    }

    /** A whole-body set of nested records ({@code .body(...)}): its items have no index. */
    public record SpareDocksRequest(Set<Dock> spares) {
    }

    public static class SpareDocksOperation implements CommandOperation<SpareDocksRequest, BookResult> {
        @Override
        public BookResult execute(SpareDocksRequest input) {
            return BOOK;
        }
    }

    /** A whole-body map of nested records ({@code .body(...)}): its values are named without the key. */
    public record DocksByNameRequest(Map<String, Dock> byName) {
    }

    public static class DocksByNameOperation implements CommandOperation<DocksByNameRequest, BookResult> {
        @Override
        public BookResult execute(DocksByNameRequest input) {
            return BOOK;
        }
    }

    /** A nested record with a non-null component Jackson never reads. */
    public record SealedDock(String warehouseCode, @JsonProperty(access = JsonProperty.Access.READ_ONLY) String seal) {
    }

    /** The sealed dock as a list item, a whole-body value and a map value's component. */
    public record SealedRequest(String sku, List<SealedDock> docks) {
    }

    public static class SealedOperation implements CommandOperation<SealedRequest, BookResult> {
        @Override
        public BookResult execute(SealedRequest input) {
            return BOOK;
        }
    }

    /** Two levels down, through a map value. */
    public record SealedYard(Map<String, SealedRequest> shipments) {
    }

    public static class SealedYardOperation implements CommandOperation<SealedYard, BookResult> {
        @Override
        public BookResult execute(SealedYard input) {
            return BOOK;
        }
    }

    /** A nested record whose ignored components are exempt: a @Nullable one and a collection. */
    public record LooseSealDock(String warehouseCode, @JsonIgnore @Nullable String seal,
            @JsonIgnore List<String> tags) {
    }

    public record LooseSealRequest(LooseSealDock dock) {
    }

    public static class LooseSealOperation implements CommandOperation<LooseSealRequest, BookResult> {
        @Override
        public BookResult execute(LooseSealRequest input) {
            return BOOK;
        }
    }

    /** A record that holds itself: the check visits each type once. */
    public record Branch(String name, List<Branch> children, @Nullable Branch parent) {
    }

    public record TreeRequest(Branch root) {
    }

    public static class TreeOperation implements CommandOperation<TreeRequest, BookResult> {
        @Override
        public BookResult execute(TreeRequest input) {
            return BOOK;
        }
    }
}
