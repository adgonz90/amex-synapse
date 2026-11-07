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
package io.americanexpress.synapse.api.grpc.internal.error;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.google.protobuf.Any;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.BadRequest;
import com.google.rpc.ErrorInfo;
import com.google.rpc.RetryInfo;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.protobuf.StatusProto;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import io.americanexpress.synapse.api.grpc.fixture.proto.Scalars;
import io.americanexpress.synapse.api.grpc.internal.edge.GrpcCallState;
import io.americanexpress.synapse.api.grpc.internal.mapping.MessageMapping;
import io.americanexpress.synapse.api.grpc.internal.mapping.RecordReader;
import io.americanexpress.synapse.core.autoconfigure.SynapseErrorProperties;
import io.americanexpress.synapse.core.error.ErrorMessages;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class GrpcStatusWriterTest {

    static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final StaticMessageSource messageSource = new StaticMessageSource();

    @Test
    void notFoundCarriesErrorInfo() throws Exception {
        StatusException written = writer(false)
                .write(new SynapseException(BookErrors.BOOK_NOT_FOUND, 42), state("c-1"));

        assertThat(written.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(written.getStatus().getDescription()).isNullOrEmpty();
        com.google.rpc.Status status = statusOf(written);
        assertThat(status.getCode()).isEqualTo(Status.Code.NOT_FOUND.value());
        assertThat(status.getMessage()).isEmpty();
        assertThat(status.getDetailsList()).hasSize(1);
        ErrorInfo info = status.getDetails(0).unpack(ErrorInfo.class);
        assertThat(info.getReason()).isEqualTo("BOOK_NOT_FOUND");
        assertThat(info.getDomain()).isEqualTo("books");
        assertThat(info.getMetadataMap()).isEqualTo(Map.of("correlationId", "c-1"));
    }

    @Test
    void detailOnlyWhenIncludeDetail() {
        SynapseException error = new SynapseException(BookErrors.BOOK_NOT_FOUND, 42);

        StatusException shown = writer(true).write(error, state("c-1"));
        assertThat(shown.getStatus().getDescription()).isEqualTo("Book 42 was not found");
        assertThat(statusOf(shown).getMessage()).isEqualTo("Book 42 was not found");

        StatusException hidden = writer(false).write(error, state("c-1"));
        assertThat(hidden.getStatus().getDescription()).isNullOrEmpty();
        assertThat(statusOf(hidden).getMessage()).isEmpty();
    }

    @Test
    void detailIsResolvedInTheCallersLocale() {
        messageSource.addMessage("BOOK_NOT_FOUND", Locale.FRENCH, "Livre {0} introuvable");
        GrpcCallState state = state("c-1");
        state.locale(Locale.FRENCH);

        StatusException written = writer(true).write(new SynapseException(BookErrors.BOOK_NOT_FOUND, 42), state);

        assertThat(written.getStatus().getDescription()).isEqualTo("Livre 42 introuvable");
    }

    @Test
    void violationsBecomeBadRequestWithFieldPaths() throws Exception {
        GrpcCallState state = state("c-1");
        state.fieldPaths(Map.of("publishedOn", "published_on", "authorName", "author.name"));
        SynapseException error = SynapseException
                .ofViolations(SynapseErrors.VALIDATION_FAILED,
                        List
                                .of(new Violation("publishedOn", "must not be null"),
                                        new Violation("title", "must not be blank")));

        StatusException written = writer(false).write(error, state);

        assertThat(written.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        com.google.rpc.Status status = statusOf(written);
        assertThat(status.getDetailsList()).hasSize(2);
        assertThat(status.getDetails(0).unpack(ErrorInfo.class).getReason()).isEqualTo("VALIDATION_FAILED");
        BadRequest badRequest = status.getDetails(1).unpack(BadRequest.class);
        assertThat(badRequest.getFieldViolationsList())
                .extracting(BadRequest.FieldViolation::getField, BadRequest.FieldViolation::getDescription)
                .containsExactly(tuple("published_on", "must not be null"),
                        tuple("title", "must not be blank"));
    }

    @Test
    void violationsInsideListsAndMapsUseProtoNames() throws Exception {
        GrpcCallState state = state("c-1");
        state
                .fieldPaths(MessageMapping
                        .reader(Scalars.getDescriptor(), Shelf.class, List.of(), List.of())
                        .fieldPaths());
        // As Jakarta Validation names them: an element by its index, a map value by its key.
        SynapseException error = SynapseException
                .ofViolations(SynapseErrors.VALIDATION_FAILED,
                        List
                                .of(new Violation("authors[0].countryCode", "must not be blank"),
                                        new Violation("authorsByRank[7].countryCode", "must not be blank"),
                                        new Violation("authorsByRank[7]", "must not be null"),
                                        new Violation("authors[1]", "must not be null"),
                                        new Violation("missing[0].countryCode", "must not be blank")));

        StatusException written = writer(false).write(error, state);

        // A list keeps its index; a map's key, the caller's data, is dropped; an unmapped path with brackets is cut
        // before its first bracket, which may open a key.
        BadRequest badRequest = statusOf(written).getDetails(1).unpack(BadRequest.class);
        assertThat(badRequest.getFieldViolationsList())
                .extracting(BadRequest.FieldViolation::getField)
                .containsExactly("authors[0].country_code", "authors_by_rank.country_code", "authors_by_rank",
                        "authors[1]", "missing");
    }

    @Test
    void noPartOfAMapKeyIsEverEchoed() throws Exception {
        GrpcCallState state = state("c-1");
        state
                .fieldPaths(MessageMapping
                        .reader(Scalars.getDescriptor(), Shelf.class, List.of(), List.of())
                        .fieldPaths());
        // Jakarta Validation writes a String key as it is, brackets included.
        List<String> keys = List.of("a]b", "[x]", "]", "a].countryCode[b", "0");
        List<Violation> violations = new ArrayList<>();
        for (String key : keys) {
            violations.add(new Violation("authorsByRank[" + key + "].countryCode", "must not be blank"));
            violations.add(new Violation("authorsByRank[" + key + "]", "must not be null"));
        }
        SynapseException error = SynapseException.ofViolations(SynapseErrors.VALIDATION_FAILED, violations);

        BadRequest badRequest = statusOf(writer(false).write(error, state)).getDetails(1).unpack(BadRequest.class);

        List<String> fields = badRequest
                .getFieldViolationsList()
                .stream()
                .map(BadRequest.FieldViolation::getField)
                .toList();
        assertThat(fields).hasSize(violations.size());
        assertThat(fields).allSatisfy(field -> {
            assertThat(field).doesNotContain("[").doesNotContain("]").doesNotContain("a]b").doesNotContain("x");
            assertThat(field).isIn("authors_by_rank.country_code", "authors_by_rank");
        });
    }

    @Test
    void aMapInAListAndAListInAMap() {
        // As MessageMapping lists them: a list element is [] and a map value {} in the proto path.
        Map<String, String> paths = Map
                .of("shelves", "shelves", "shelves[]", "shelves[]", "shelves[].byRank", "shelves[].by_rank",
                        "shelves[].byRank[]", "shelves[].by_rank{}", "shelves[].byRank[].countryCode",
                        "shelves[].by_rank{}.country_code", "rooms", "rooms", "rooms[]", "rooms{}",
                        "rooms[].tags", "rooms{}.tag_list", "rooms[].tags[]", "rooms{}.tag_list[]");

        assertThat(GrpcStatusWriter.protoPath("shelves[2].byRank[fr].countryCode", paths))
                .isEqualTo("shelves[2].by_rank.country_code");
        assertThat(GrpcStatusWriter.protoPath("shelves[2].byRank[a]b].countryCode", paths))
                .isEqualTo("shelves[2].by_rank.country_code");
        assertThat(GrpcStatusWriter.protoPath("shelves[2].byRank[fr]", paths)).isEqualTo("shelves[2].by_rank");
        assertThat(GrpcStatusWriter.protoPath("rooms[a]b].tags[3]", paths)).isEqualTo("rooms.tag_list[3]");
        assertThat(GrpcStatusWriter.protoPath("rooms[[x]].tags", paths)).isEqualTo("rooms.tag_list");
        // An index that isn't a number can't be a list's, so nothing after the first bracket is used.
        assertThat(GrpcStatusWriter.protoPath("shelves[x].byRank[fr].countryCode", paths)).isEqualTo("shelves");
        // Many brackets in nested keys: the search is bounded and fails closed.
        String brackets = "]".repeat(2_000);
        assertThat(GrpcStatusWriter
                .protoPath("rooms[" + brackets + "].tags[" + brackets + "].x[" + brackets + "]", paths))
                .isEqualTo("rooms");
    }

    @Test
    void aMapValueViolationIsNamedAsTheReaderNamesIt() throws Exception {
        RecordReader reader = MessageMapping.reader(Scalars.getDescriptor(), CodedShelf.class, List.of(), List.of());
        Scalars request = Scalars
                .newBuilder()
                .putAuthorsByRank(7,
                        io.americanexpress.synapse.api.grpc.fixture.proto.Author
                                .newBuilder()
                                .setName("a")
                                .setCountryCode("not-a-uuid")
                                .build())
                .build();
        String readerField;
        try {
            reader.read(DynamicMessage.parseFrom(Scalars.getDescriptor(), request.toByteString()));
            throw new AssertionError("the reader accepted a country code that isn't a UUID");
        } catch (SynapseException conversion) {
            readerField = conversion.violations().getFirst().field();
        }
        GrpcCallState state = state("c-1");
        state.fieldPaths(reader.fieldPaths());
        SynapseException validation = SynapseException
                .ofViolations(SynapseErrors.VALIDATION_FAILED,
                        List.of(new Violation("authorsByRank[7].countryCode", "must not be null")));

        BadRequest badRequest = statusOf(writer(false).write(validation, state)).getDetails(1).unpack(BadRequest.class);

        assertThat(readerField).isEqualTo("authors_by_rank.country_code");
        assertThat(badRequest.getFieldViolationsList())
                .extracting(BadRequest.FieldViolation::getField)
                .containsExactly(readerField);
    }

    @Test
    void violationsWithoutStateKeepTheirComponentPaths() throws Exception {
        SynapseException error = SynapseException
                .ofViolations(SynapseErrors.VALIDATION_FAILED,
                        List.of(new Violation("publishedOn", "must not be null")));

        StatusException written = writer(false).write(error, null);

        BadRequest badRequest = statusOf(written).getDetails(1).unpack(BadRequest.class);
        assertThat(badRequest.getFieldViolationsList())
                .extracting(BadRequest.FieldViolation::getField)
                .containsExactly("publishedOn");
    }

    @Test
    void negativeRetryDelayIsRejected() {
        GrpcStatusWriter writer = writer(false);
        SynapseException error = new SynapseException(SynapseErrors.UNAVAILABLE);

        assertThatThrownBy(() -> writer.write(error, null, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("retryDelay must not be negative but was PT-0.001S");
        assertThat(statusOf(writer.write(error, null, Duration.ZERO)).getDetailsList()).hasSize(2);
    }

    @Test
    void correlationIdMetadataKeyIsTheWireContract() {
        assertThat(GrpcStatusWriter.CORRELATION_ID_METADATA).isEqualTo("correlationId");
    }

    @Test
    void retryInfoWhenAsked() throws Exception {
        StatusException written = writer(false)
                .write(new SynapseException(SynapseErrors.UNAVAILABLE), state("c-1"), Duration.ofMillis(1500));

        assertThat(written.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        com.google.rpc.Status status = statusOf(written);
        assertThat(status.getDetailsList()).hasSize(2);
        assertThat(status.getDetails(0).unpack(ErrorInfo.class).getReason()).isEqualTo("UNAVAILABLE");
        RetryInfo retry = status.getDetails(1).unpack(RetryInfo.class);
        assertThat(retry.getRetryDelay().getSeconds()).isEqualTo(1);
        assertThat(retry.getRetryDelay().getNanos()).isEqualTo(500_000_000);

        StatusException withoutRetry = writer(false).write(new SynapseException(SynapseErrors.UNAVAILABLE), null);
        assertThat(statusOf(withoutRetry).getDetailsList())
                .extracting(Any::getTypeUrl)
                .noneMatch(url -> url.endsWith("RetryInfo"));
    }

    @Test
    void newCorrelationIdWithoutState() throws Exception {
        StatusException written = writer(false).write(new SynapseException(SynapseErrors.INTERNAL), null);

        ErrorInfo info = statusOf(written).getDetails(0).unpack(ErrorInfo.class);
        assertThat(info.getMetadataOrThrow("correlationId")).matches(UUID_PATTERN);
    }

    @Test
    void stateWithoutCorrelationIdGetsTheGeneratedOne() throws Exception {
        GrpcCallState state = new GrpcCallState(null, Map.of());

        StatusException written = writer(false).write(new SynapseException(SynapseErrors.INTERNAL), state);

        String correlationId = statusOf(written)
                .getDetails(0)
                .unpack(ErrorInfo.class)
                .getMetadataOrThrow("correlationId");
        assertThat(correlationId).matches(UUID_PATTERN);
        // Recorded on the call, so the correlation trailer the edge adds carries the same id.
        assertThat(state.correlationId()).isEqualTo(correlationId);
    }

    @Test
    void usesTheStatusOfTheFailedOperation() {
        GrpcErrorStatuses statuses = new GrpcErrorStatuses(Map.of(),
                Map.of("get-book", Map.of("BOOK_NOT_FOUND", Status.Code.FAILED_PRECONDITION)));
        GrpcStatusWriter writer = new GrpcStatusWriter(statuses, new ErrorMessages(messageSource),
                new SynapseErrorProperties(false, null), "books");
        GrpcCallState state = state("c-1");
        state.operationName("get-book");

        assertThat(writer.write(new SynapseException(BookErrors.BOOK_NOT_FOUND, 42), state).getStatus().getCode())
                .isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(
                writer.write(new SynapseException(BookErrors.BOOK_NOT_FOUND, 42), state("c-2")).getStatus().getCode())
                .isEqualTo(Status.Code.NOT_FOUND);
    }

    @Test
    void badRequestKeepsWholeViolationsWithinTheTrailerBudget() throws Exception {
        List<Violation> many = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            many.add(new Violation("authors[" + i + "].country_code", "is not a valid value"));
        }

        StatusException written = writer(true)
                .write(SynapseException.ofViolations(SynapseErrors.VALIDATION_FAILED, many), state("c-1"));

        assertThat(written.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        Any detail = statusOf(written).getDetails(1);
        assertThat(Base64.getEncoder().encode(detail.toByteArray()).length).isLessThanOrEqualTo(4096);
        List<BadRequest.FieldViolation> kept = detail.unpack(BadRequest.class).getFieldViolationsList();
        assertThat(kept).hasSizeBetween(1, 999);
        assertThat(kept)
                .extracting(BadRequest.FieldViolation::getField, BadRequest.FieldViolation::getDescription)
                .containsExactlyElementsOf(many
                        .subList(0, kept.size())
                        .stream()
                        .map(violation -> tuple(violation.field(), violation.message()))
                        .toList());
        Metadata trailers = Objects.requireNonNull(written.getTrailers());
        long size = 0;
        for (String key : trailers.keys()) {
            // grpc-java counts each entry as its name, its value and 32 bytes; binary values travel in base64.
            Metadata.Key<byte[]> binary = Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER);
            for (byte[] value : Objects.requireNonNull(trailers.getAll(binary))) {
                size += key.length() + Base64.getEncoder().encode(value).length + 32;
            }
        }
        assertThat(size).isLessThan(8192);
    }

    private GrpcStatusWriter writer(boolean includeDetail) {
        return new GrpcStatusWriter(new GrpcErrorStatuses(Map.of(), Map.of()), new ErrorMessages(messageSource),
                new SynapseErrorProperties(includeDetail, null), "books");
    }

    static GrpcCallState state(@Nullable String correlationId) {
        GrpcCallState state = new GrpcCallState(null, Map.of());
        if (correlationId != null) {
            state.correlationId(correlationId);
        }
        return state;
    }

    static com.google.rpc.Status statusOf(StatusException written) {
        return Objects.requireNonNull(StatusProto.fromThrowable(written), "no com.google.rpc.Status in the trailers");
    }

    static ErrorInfo errorInfo(StatusException written) {
        try {
            return statusOf(written).getDetails(0).unpack(ErrorInfo.class);
        } catch (InvalidProtocolBufferException ex) {
            throw new AssertionError(ex);
        }
    }

    record Author(String name, String countryCode) {
    }

    record Shelf(List<Author> authors, Map<Integer, Author> authorsByRank) {
    }

    record CodedAuthor(String name, UUID countryCode) {
    }

    record CodedShelf(Map<Integer, CodedAuthor> authorsByRank) {
    }
}
