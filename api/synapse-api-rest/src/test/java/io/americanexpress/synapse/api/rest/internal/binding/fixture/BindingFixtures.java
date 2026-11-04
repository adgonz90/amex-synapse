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

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;
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

    public static class PingOperation implements QueryOperation<Void, String> {
        @Override
        public String execute(Void input) {
            return "pong";
        }
    }
}
