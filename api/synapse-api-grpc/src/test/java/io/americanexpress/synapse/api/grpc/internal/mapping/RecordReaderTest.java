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
package io.americanexpress.synapse.api.grpc.internal.mapping;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.protobuf.BoolValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Empty;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.StringValue;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import io.americanexpress.synapse.api.grpc.fixture.proto.Author;
import io.americanexpress.synapse.api.grpc.fixture.proto.CreateItemRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.DeleteDocRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.GetDocRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.Item;
import io.americanexpress.synapse.api.grpc.fixture.proto.ListEtagRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.ListPagedBooksRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.Node;
import io.americanexpress.synapse.api.grpc.fixture.proto.NumberEtagRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.OneofEtagRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.OptionalEtagRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.OptionalPagesRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.PageOnlyRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.PagedUpdateRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.PutDocRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.RenameDocRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.Scalars;
import io.americanexpress.synapse.api.grpc.fixture.proto.Titled;
import io.americanexpress.synapse.api.grpc.fixture.proto.UpdateDocRequest;
import io.americanexpress.synapse.api.grpc.internal.binding.ContextSource;
import io.americanexpress.synapse.api.grpc.internal.binding.SortKeys;
import io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.AuthorRec;
import io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.CountryCode;
import io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.CreateItemRec;
import io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.Genre;
import io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.ItemRec;
import io.americanexpress.synapse.core.conditional.VersionCondition;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.core.internal.binding.ComponentRefs;
import io.americanexpress.synapse.core.internal.conditional.VersionTokens;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.SortOrder;
import io.americanexpress.synapse.test.operation.RequestContexts;

import static io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.dynamic;
import static io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.item;
import static io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.itemOverrides;
import static io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.itemRecord;
import static io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.override;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class RecordReaderTest {

    private static final String INVALID = "is not a valid value";

    private static final String MISSING = "must not be null";

    private final RecordReader items = MessageMapping
            .reader(Item.getDescriptor(), ItemRec.class, itemOverrides(),
                    List.of());

    @Test
    void readsEveryBuiltInType() {
        Object read = items.read(dynamic(item()));

        assertThat(read).isEqualTo(itemRecord());
    }

    @Test
    void absentWrapperAndOptionalAreNull() {
        Item item = item().toBuilder().clearCreatedAt().clearTtl().clearRating().clearNote().clearSku().build();

        ItemRec read = (ItemRec) Objects.requireNonNull(items.read(dynamic(item)));

        assertThat(read.createdAt()).isNull();
        assertThat(read.ttl()).isNull();
        assertThat(read.rating()).isNull();
        assertThat(read.note()).isNull();
        assertThat(read.sku()).isNull();
    }

    @Test
    void enumPrefixAndUnspecified() {
        Item science = item()
                .toBuilder()
                .setGenre(io.americanexpress.synapse.api.grpc.fixture.proto.Genre.GENRE_SCIENCE)
                .build();
        Item unspecified = item().toBuilder().clearGenre().build();

        assertThat(((ItemRec) Objects.requireNonNull(items.read(dynamic(science)))).genre()).isEqualTo(Genre.SCIENCE);
        assertViolations(() -> items.read(dynamic(unspecified)), new Violation("genre", MISSING));
    }

    @Test
    void unknownEnumNumberIsInvalid() {
        Item unknown = item().toBuilder().setGenreValue(9).build();

        assertViolations(() -> items.read(dynamic(unknown)), new Violation("genre", INVALID));
    }

    @Test
    void invalidUuidIsAViolationWithTheProtoPath() {
        Item invalid = item().toBuilder().setId("x").build();

        assertViolations(() -> items.read(dynamic(invalid)), new Violation("id", INVALID));
    }

    @Test
    void nestedViolationUsesTheDottedProtoPath() {
        Item invalid = item().toBuilder().setAuthor(Author.newBuilder().setName("A").setCountryCode("usa")).build();

        assertViolations(() -> items.read(dynamic(invalid)), new Violation("author.country_code", INVALID));
    }

    @Test
    void missingValuesAreViolationsInComponentOrder() {
        Item empty = Item.newBuilder().setName("n").build();

        assertViolations(() -> items.read(dynamic(empty)), new Violation("id", MISSING),
                new Violation("genre", MISSING), new Violation("author", MISSING), new Violation("version", MISSING));
    }

    @Test
    void unsentOptionalRequiredScalarIsMustNotBeNull() {
        // Pin (R-11): with presence, an unsent required scalar is missing, as REST reports an absent property.
        RecordReader reader = MessageMapping.reader(Titled.getDescriptor(), Title.class, List.of(), List.of());

        assertViolations(() -> reader.read(dynamic(Titled.getDefaultInstance())), new Violation("title", MISSING));
        assertThat(reader.read(dynamic(Titled.newBuilder().setTitle("").build()))).isEqualTo(new Title(""));
    }

    @Test
    void contextSourceFillsAComponent() {
        RecordReader reader = MessageMapping
                .reader(CreateItemRequest.getDescriptor(), CreateItemRec.class,
                        itemOverrides(),
                        List.of(new ContextSource(ComponentRefs.resolve(CreateItemRec::channel), context -> "web")));
        DynamicMessage request = dynamic(CreateItemRequest.newBuilder().setItem(item()).build());

        Object read = RequestContexts.with().call(() -> Objects.requireNonNull(reader.read(request)));

        assertThat(read).isEqualTo(new CreateItemRec(itemRecord(), "web"));
    }

    @Test
    void nullFromANonNullContextSourceIsMissing() {
        RecordReader reader = MessageMapping
                .reader(CreateItemRequest.getDescriptor(), CreateItemRec.class,
                        itemOverrides(),
                        List.of(new ContextSource(ComponentRefs.resolve(CreateItemRec::channel), context -> null)));
        DynamicMessage request = dynamic(CreateItemRequest.newBuilder().setItem(item()).build());

        assertViolations(() -> RequestContexts.with().call(() -> reader.read(request)),
                new Violation("channel", MISSING));
    }

    @Test
    void nestedRecordPathsStartAtTheRequest() {
        RecordReader reader = MessageMapping
                .reader(CreateItemRequest.getDescriptor(), CreateItemRec.class,
                        itemOverrides(),
                        List.of(new ContextSource(ComponentRefs.resolve(CreateItemRec::channel), context -> null)));
        Item invalid = item().toBuilder().setAuthor(Author.newBuilder().setName("A").setCountryCode("usa")).build();
        DynamicMessage request = dynamic(CreateItemRequest.newBuilder().setItem(invalid).build());

        assertViolations(() -> RequestContexts.with().call(() -> reader.read(request)),
                new Violation("item.author.country_code", INVALID),
                new Violation("channel", MISSING));
        assertThat(reader.fieldPaths())
                .containsEntry("item", "item")
                .containsEntry("item.createdAt", "item.created_at")
                .containsEntry("item.author.countryCode", "item.author.country_code")
                .doesNotContainKey("channel");
    }

    @Test
    void voidAndEmpty() {
        RecordReader reader = MessageMapping.reader(Empty.getDescriptor(), Void.class, List.of(), List.of());

        assertThat(reader.read(dynamic(Empty.getDefaultInstance()))).isNull();
        assertThat(reader.fieldPaths()).isEmpty();
    }

    @Test
    void readsEveryScalarAndWrapper() {
        RecordReader reader = MessageMapping.reader(Scalars.getDescriptor(), AllScalars.class, List.of(), List.of());

        Object read = reader.read(dynamic(scalars()));

        assertThat(read).usingRecursiveComparison().isEqualTo(allScalars());
        assertThat(MessageMapping
                .reader(Scalars.getDescriptor(), Wrappers.class, List.of(), List.of())
                .read(dynamic(scalars()))).usingRecursiveComparison().isEqualTo(wrappers());
    }

    @Test
    void absentWrappersAreNull() {
        RecordReader reader = MessageMapping.reader(Scalars.getDescriptor(), Wrappers.class, List.of(), List.of());

        Object read = reader.read(dynamic(Scalars.getDefaultInstance()));

        assertThat(read)
                .usingRecursiveComparison()
                .isEqualTo(new Wrappers(null, null, null, null, null, null, null, null, null, null));
    }

    @Test
    void unsignedValuesOutOfTheJavaRangeAreInvalid() {
        RecordReader reader = MessageMapping.reader(Scalars.getDescriptor(), Unsigned.class, List.of(), List.of());
        Scalars large = Scalars.newBuilder().setUint32Value(-1).setFixed32Value(-1).setUint64Value(-1L).build();
        Scalars small = Scalars.newBuilder().setUint32Value(5).setFixed32Value(6).setUint64Value(7L).build();

        assertViolations(() -> reader.read(dynamic(large)), new Violation("uint32_value", INVALID),
                new Violation("uint64_value", INVALID));
        assertThat(reader.read(dynamic(small))).isEqualTo(new Unsigned(5, 6L, 7L));
        assertThat(((Unsigned) Objects
                .requireNonNull(reader
                        .read(dynamic(
                                Scalars
                                        .newBuilder()
                                        .setUint32Value(0)
                                        .setUint64Value(1L)
                                        .setFixed32Value(-1)
                                        .build()))))
                .fixed32Value())
                .isEqualTo(4_294_967_295L);
    }

    @Test
    void invalidTimestampIsInvalid() {
        RecordReader reader = MessageMapping.reader(Scalars.getDescriptor(), Times.class, List.of(), List.of());
        Scalars invalid = Scalars.newBuilder().addTimes(Timestamp.newBuilder().setNanos(-1)).build();

        assertViolations(() -> reader.read(dynamic(invalid)), new Violation("times[0]", INVALID));
    }

    @Test
    void timestampOutsideTheProtobufRangeIsInvalid() {
        RecordReader reader = MessageMapping.reader(Scalars.getDescriptor(), Times.class, List.of(), List.of());
        Scalars invalid = Scalars
                .newBuilder()
                .addTimes(Timestamp.newBuilder().setSeconds(253_402_300_799L))
                .addTimes(Timestamp.newBuilder().setSeconds(253_402_300_800L))
                .addTimes(Timestamp.newBuilder().setSeconds(-62_135_596_800L))
                .addTimes(Timestamp.newBuilder().setSeconds(-62_135_596_801L))
                .build();

        assertViolations(() -> reader.read(dynamic(invalid)), new Violation("times[1]", INVALID),
                new Violation("times[3]", INVALID));
    }

    @Test
    void invalidDurationIsInvalid() {
        RecordReader reader = MessageMapping
                .reader(Item.getDescriptor(), RecordWriterTest.Ttl.class, List.of(),
                        List.of());

        for (com.google.protobuf.Duration invalid : List
                .of(com.google.protobuf.Duration.newBuilder().setSeconds(1).setNanos(-1).build(),
                        com.google.protobuf.Duration.newBuilder().setSeconds(-1).setNanos(1).build(),
                        com.google.protobuf.Duration.newBuilder().setNanos(1_000_000_000).build(),
                        com.google.protobuf.Duration.newBuilder().setNanos(-1_000_000_000).build())) {
            assertViolations(() -> reader.read(dynamic(Item.newBuilder().setTtl(invalid).build())),
                    new Violation("ttl", INVALID));
        }
        assertThat(reader
                .read(dynamic(Item
                        .newBuilder()
                        .setTtl(com.google.protobuf.Duration.newBuilder().setSeconds(-1).setNanos(-5))
                        .build())))
                .isEqualTo(new RecordWriterTest.Ttl(java.time.Duration.ofSeconds(-1, -5)));
    }

    @Test
    void requestNestingIsBoundedByTheParserBeforeTheReader() throws Exception {
        RecordReader reader = MessageMapping.reader(Node.getDescriptor(), NodeRec.class, List.of(), List.of());

        Object read = reader.read(dynamic(chain(90)));

        int depth = 0;
        for (NodeRec node = (NodeRec) read; node != null; node = node.next()) {
            depth++;
        }
        assertThat(depth).isEqualTo(90);
        com.google.protobuf.ByteString tooDeep = chain(150).toByteString();
        assertThatExceptionOfType(InvalidProtocolBufferException.class)
                .isThrownBy(() -> DynamicMessage.parseFrom(Node.getDescriptor(), tooDeep));
    }

    @Test
    void onlyConversionFailuresBecomeViolations() {
        FieldConverter.Reading reading = new FieldConverter.Reading();

        assertThat(reading.convert(throwing(new IllegalArgumentException("x")), "v", "a"))
                .isSameAs(
                        FieldConverter.FAILED);
        assertThat(reading.convert(throwing(new java.time.DateTimeException("x")), "v", "b"))
                .isSameAs(
                        FieldConverter.FAILED);
        assertThat(reading.convert(throwing(new ArithmeticException("x")), "v", "c")).isSameAs(FieldConverter.FAILED);
        assertThat(reading
                .convert(throwing(new org.springframework.core.convert.ConverterNotFoundException(null,
                        org.springframework.core.convert.TypeDescriptor.valueOf(String.class))), "v", "d"))
                .isSameAs(FieldConverter.FAILED);
        assertThat(reading.violations()).extracting(Violation::field).containsExactly("a", "b", "c", "d");
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> reading.convert(throwing(new NullPointerException("bug")), "v", "e"));
        assertThatExceptionOfType(ClassCastException.class)
                .isThrownBy(() -> reading.convert(throwing(new ClassCastException("bug")), "v", "f"));
        assertThat(reading.violations()).hasSize(4);
    }

    private static Node chain(int length) {
        Node node = Node.newBuilder().setName("n" + length).build();
        for (int i = length - 1; i > 0; i--) {
            node = Node.newBuilder().setName("n" + i).setNext(node).build();
        }
        return node;
    }

    private static FieldConverter throwing(RuntimeException failure) {
        return new FieldConverter() {

            @Override
            public @Nullable Object read(Object value, FieldConverter.Reading reading, String path) {
                throw failure;
            }

            @Override
            public @Nullable Object write(Object value) {
                throw failure;
            }
        };
    }

    @Test
    void violationsStopAtFifty() {
        RecordReader reader = MessageMapping.reader(Scalars.getDescriptor(), Times.class, List.of(), List.of());
        Scalars.Builder many = Scalars.newBuilder();
        for (int i = 0; i < 5_000; i++) {
            many.addTimes(Timestamp.newBuilder().setNanos(-1));
        }
        DynamicMessage request = dynamic(many.build());

        assertTimeout(java.time.Duration.ofSeconds(5), () -> assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> reader.read(request))
                .satisfies(e -> {
                    assertThat(e.code()).isEqualTo(SynapseErrors.VALIDATION_FAILED.code());
                    assertThat(e.violations()).hasSize(50);
                    assertThat(e.violations().getFirst()).isEqualTo(new Violation("times[0]", INVALID));
                    assertThat(e.violations().getLast()).isEqualTo(new Violation("times[49]", INVALID));
                }));
    }

    @Test
    void nestedViolationsStopAtFifty() {
        RecordReader reader = MessageMapping.reader(Scalars.getDescriptor(), Containers.class, List.of(), List.of());
        Scalars.Builder many = Scalars.newBuilder();
        for (int i = 0; i < 60; i++) {
            many.addAuthors(Author.newBuilder().setName("a"));
        }

        // REST answers the same shape the same way (RequestBinderTest.violationsStopAtFifty): the first 50 elements'
        // unsent required field, then reading stops.
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> reader.read(dynamic(many.build())))
                .satisfies(e -> {
                    assertThat(e.code()).isEqualTo(SynapseErrors.VALIDATION_FAILED.code());
                    assertThat(e.violations()).hasSize(50).allMatch(v -> v.message().equals(MISSING));
                    assertThat(e.violations().getFirst()).isEqualTo(new Violation("authors[0].country_code", MISSING));
                    assertThat(e.violations().getLast()).isEqualTo(new Violation("authors[49].country_code", MISSING));
                });
    }

    @Test
    void elementViolationsCarryTheirIndexOrTheMapField() {
        RecordReader reader = MessageMapping.reader(Scalars.getDescriptor(), Containers.class, List.of(), List.of());
        Scalars invalid = Scalars
                .newBuilder()
                .addAuthors(Author.newBuilder().setName("a").setCountryCode("US"))
                .addAuthors(Author.newBuilder().setName("b").setCountryCode("usa"))
                .putAuthorsByRank(1, Author.newBuilder().setName("c").setCountryCode("x").build())
                .addGenres(io.americanexpress.synapse.api.grpc.fixture.proto.Genre.GENRE_FICTION)
                .addGenres(io.americanexpress.synapse.api.grpc.fixture.proto.Genre.GENRE_UNSPECIFIED)
                .build();

        assertViolations(() -> reader.read(dynamic(invalid)), new Violation("authors[1].country_code", INVALID),
                new Violation("authors_by_rank.country_code", INVALID), new Violation("genres[1]", MISSING));
    }

    @Test
    void unsetMapValueReadsAsAnEmptyMessage() throws InvalidProtocolBufferException {
        RecordReader reader = MessageMapping.reader(Scalars.getDescriptor(), Containers.class, List.of(), List.of());
        FieldDescriptor byRank = Scalars.getDescriptor().findFieldByName("authors_by_rank");
        FieldDescriptor value = byRank.getMessageType().findFieldByNumber(2);
        // authors_by_rank (field 31, length-delimited) holding one entry with its key (7) and no value.
        DynamicMessage keyOnly = DynamicMessage
                .parseFrom(Scalars.getDescriptor(), new byte[]{(byte) 0xFA, 0x01, 0x02, 0x08, 0x07});

        // Pin: protobuf-java fills a map entry's missing value with its default when it parses it, so hasField can't
        // tell an unset value from an empty one, and REST's {"7": null} (byName must not be null) has no gRPC
        // counterpart: the value is a message whose fields are unsent, reported by those fields.
        assertThat(((Message) ((List<?>) keyOnly.getField(byRank)).getFirst()).hasField(value)).isTrue();
        assertViolations(() -> reader.read(keyOnly), new Violation("authors_by_rank.name", MISSING),
                new Violation("authors_by_rank.country_code", MISSING));
        assertViolations(
                () -> reader
                        .read(dynamic(Scalars.newBuilder().putAuthorsByRank(7, Author.getDefaultInstance()).build())),
                new Violation("authors_by_rank.name", MISSING), new Violation("authors_by_rank.country_code", MISSING));
    }

    @Test
    void readsContainersOfRecords() {
        RecordReader reader = MessageMapping.reader(Scalars.getDescriptor(), Containers.class, List.of(), List.of());
        Scalars scalars = Scalars
                .newBuilder()
                .addAuthors(Author.newBuilder().setName("a").setCountryCode("US"))
                .putAuthorsByRank(2, Author.newBuilder().setName("c").setCountryCode("FR").build())
                .addGenres(io.americanexpress.synapse.api.grpc.fixture.proto.Genre.GENRE_SCIENCE)
                .build();

        assertThat(reader.read(dynamic(scalars)))
                .isEqualTo(new Containers(
                        List.of(new AuthorRec("a", CountryCode.valueOf("US"))),
                        Map.of(2, new AuthorRec("c", CountryCode.valueOf("FR"))), List.of(Genre.SCIENCE)));
        assertThat(reader.fieldPaths())
                .containsEntry("authors", "authors")
                .containsEntry("genres", "genres")
                .doesNotContainKey("authors.countryCode");
    }

    @Test
    void elementsOfListsAndMapsAreListedWithBrackets() {
        RecordReader reader = MessageMapping.reader(Scalars.getDescriptor(), Containers.class, List.of(), List.of());

        assertThat(reader.fieldPaths())
                .containsEntry("authors[]", "authors[]")
                .containsEntry("authors[].countryCode", "authors[].country_code")
                // A map's key is marked {}: it is dropped from the name, as the reader drops it.
                .containsEntry("authorsByRank[]", "authors_by_rank{}")
                .containsEntry("authorsByRank[].countryCode", "authors_by_rank{}.country_code")
                .containsEntry("genres[]", "genres[]");
    }

    @Test
    void flattenedPathReadsANestedField() {
        RecordReader reader = MessageMapping
                .reader(Item.getDescriptor(), Flat.class,
                        List.of(override("author.country_code", Flat::country)), List.of());

        assertThat(reader.read(dynamic(item()))).isEqualTo(new Flat("Dune", "US"));
        // An unsent country_code, inside an unsent author, is missing: it has presence.
        assertViolations(() -> reader.read(dynamic(Item.newBuilder().setName("n").build())),
                new Violation("author.country_code", MISSING));
        assertThat(reader.fieldPaths()).containsEntry("country", "author.country_code");
    }

    @Test
    void recursiveRecordsFollowTheMessage() {
        RecordReader reader = MessageMapping.reader(Node.getDescriptor(), NodeRec.class, List.of(), List.of());
        Node chain = Node.newBuilder().setName("a").setNext(Node.newBuilder().setName("b")).build();

        assertThat(reader.read(dynamic(chain))).isEqualTo(new NodeRec("a", new NodeRec("b", null)));
        assertThat(reader.fieldPaths()).containsOnly(Map.entry("name", "name"), Map.entry("next", "next"));
    }

    @Test
    void nestedRecordThatRejectsItsValuesIsInvalid() {
        RecordReader reader = MessageMapping.reader(Item.getDescriptor(), Checked.class, List.of(), List.of());
        Item item = Item.newBuilder().setName("n").setAuthor(Author.newBuilder().setName("")).build();

        assertViolations(() -> reader.read(dynamic(item)), new Violation("author", INVALID));
    }

    @Test
    void rootRecordThatRejectsItsValuesIsValidationFailedWithoutViolations() {
        RecordReader reader = MessageMapping.reader(Author.getDescriptor(), CheckedAuthor.class, List.of(), List.of());

        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> reader.read(dynamic(Author.newBuilder().setName("").build())))
                .satisfies(e -> {
                    assertThat(e.code()).isEqualTo(SynapseErrors.VALIDATION_FAILED.code());
                    assertThat(e.violations()).isEmpty();
                    assertThat(e).hasCauseInstanceOf(IllegalArgumentException.class);
                });
    }

    @Test
    void synapseExceptionFromARecordReachesTheCaller() {
        RecordReader reader = MessageMapping
                .reader(Author.getDescriptor(), SelfReporting.class, List.of(),
                        List.of());

        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> reader.read(dynamic(Author.newBuilder().setName("").build())))
                .satisfies(e -> assertThat(e.code()).isEqualTo(SynapseErrors.NOT_FOUND.code()));
    }

    @Test
    void etagFieldBindsVersion() {
        RecordReader deletions = deletions();

        assertThat(deletions.read(deletion("\"5\""))).isEqualTo(new DocDeletion("d", VersionCondition.of(5L)));
        assertThat(deletions.read(deletion(" \"5\" "))).isEqualTo(new DocDeletion("d", VersionCondition.of(5L)));
        assertThat(deletions.fieldPaths()).containsEntry("version", "etag");
        // A String version keeps the opaque value as it is.
        RecordReader labels = MessageMapping
                .reader(DeleteDocRequest.getDescriptor(), Labeled.class, List.of(),
                        List.of());
        assertThat(labels.read(deletion("\"v-5\""))).isEqualTo(new Labeled("d", VersionCondition.of("v-5")));
    }

    @Test
    void starBindsAnyVersion() {
        assertThat(deletions().read(deletion("*"))).isEqualTo(new DocDeletion("d", VersionCondition.any()));
        assertThat(updates().read(update("*"))).isEqualTo(new DocUpdate("d", "t", VersionCondition.any()));
    }

    @Test
    void emptyEtagIsAbsent() {
        assertThat(updates().read(update(""))).isEqualTo(new DocUpdate("d", "t", null));
        assertThat(updates().ifMatchSent(update(""))).isFalse();
        assertThat(updates().ifMatchSent(update("\"1\""))).isTrue();
        assertThat(updates().ifMatchSent(update("*"))).isTrue();
        // A request without a VersionCondition has no etag to send.
        assertThat(items.ifMatchSent(dynamic(item()))).isFalse();

        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> deletions().read(deletion("")))
                .satisfies(e -> {
                    assertThat(e.code()).isEqualTo(SynapseErrors.PRECONDITION_REQUIRED.code());
                    assertThat(e.violations())
                            .containsExactly(new Violation("etag", "is required; send the etag from a read of this "
                                    + "resource, or * for any current version"));
                });
        // An unset proto3 optional etag is absent too.
        RecordReader optional = MessageMapping
                .reader(OptionalEtagRequest.getDescriptor(), DocDeletion.class,
                        List.of(), List.of());
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> optional.read(dynamic(OptionalEtagRequest.newBuilder().setId("d").build())))
                .satisfies(e -> assertThat(e.code()).isEqualTo(SynapseErrors.PRECONDITION_REQUIRED.code()));
        assertThat(optional.read(dynamic(OptionalEtagRequest.newBuilder().setId("d").setEtag("\"2\"").build())))
                .isEqualTo(new DocDeletion("d", VersionCondition.of(2L)));
    }

    @Test
    void weakEtagIsPreconditionFailed() {
        for (RecordReader reader : List.of(deletions(), updates())) {
            assertThatExceptionOfType(SynapseException.class)
                    .isThrownBy(() -> reader.read(deletionOrUpdate(reader, "W/\"5\"")))
                    .satisfies(e -> {
                        assertThat(e.code()).isEqualTo(SynapseErrors.PRECONDITION_FAILED.code());
                        assertThat(e.violations()).isEmpty();
                    });
        }
    }

    @Test
    void twoTagsAreInvalidInput() {
        assertViolations(() -> deletions().read(deletion("\"1\", \"2\"")),
                new Violation("etag", "must be a single entity tag"));
    }

    @Test
    void malformedEtagIsInvalidInputBeforeAnyPrecondition() {
        for (String malformed : List.of("5", "\"5", "W/5", "*, \"5\"", " ")) {
            assertViolations(() -> deletions().read(deletion(malformed)),
                    new Violation("etag", "must be an entity tag"));
        }
        // A tag whose value isn't a version, weak or not, is invalid: the 400 comes before the weak tag's failure.
        assertViolations(() -> deletions().read(deletion("\"five\"")), new Violation("etag", INVALID));
        assertViolations(() -> deletions().read(deletion("W/\"five\"")), new Violation("etag", INVALID));
        assertViolations(() -> deletions().read(deletion("\"\"")), new Violation("etag", INVALID));
    }

    @Test
    void versionConditionNeedsAVersionType() {
        // Core's version token codec reads the version, on gRPC as on REST: a type a string converts to is not enough.
        for (Class<?> record : List.of(StampVersioned.class, UuidVersioned.class)) {
            String type = record == StampVersioned.class ? "Stamp" : "UUID";
            assertThatExceptionOfType(MappingException.class)
                    .isThrownBy(() -> MessageMapping
                            .reader(DeleteDocRequest.getDescriptor(), record, List.of(),
                                    List.of()))
                    .satisfies(e -> {
                        assertThat(e.problem())
                                .isEqualTo(record.getSimpleName() + ".version is a VersionCondition<" + type
                                        + ">, which is not a version type");
                        assertThat(e.action()).isEqualTo("Declare a version type: " + VersionTokens.TYPES + ".");
                    });
        }
    }

    @Test
    void etagVersionIsAPlainDecimalToken() {
        // Not the lenient number parsing of a conversion service (Long.decode reads 0x10, +5 and octal 007).
        for (String token : List.of("\"0x10\"", "\"+5\"", "\"007\"", "\"#10\"")) {
            assertViolations(() -> deletions().read(deletion(token)), new Violation("etag", INVALID));
        }
        assertThat(deletions().read(deletion("\"16\""))).isEqualTo(new DocDeletion("d", VersionCondition.of(16L)));
        RecordReader dated = MessageMapping
                .reader(DeleteDocRequest.getDescriptor(), DateVersioned.class, List.of(),
                        List.of());
        assertThat(dated.read(deletion("\"2026-11-07\"")))
                .isEqualTo(new DateVersioned("d", VersionCondition.of(LocalDate.of(2026, 11, 7))));
        assertViolations(() -> dated.read(deletion("\"07/11/2026\"")), new Violation("etag", INVALID));
    }

    @Test
    void etagFieldOfTheWrongShapeIsNamed() {
        String prefix = "DocDeletion.version is a VersionCondition, but synapse.fixture.v1.";
        assertReaderFails(NumberEtagRequest.getDescriptor(), prefix + "NumberEtagRequest.etag is int64, not string",
                "Declare synapse.fixture.v1.NumberEtagRequest.etag as string: it carries the version as an entity "
                        + "tag, such as \"5\" or *.");
        assertReaderFails(ListEtagRequest.getDescriptor(),
                prefix + "ListEtagRequest.etag is repeated string, not string",
                "Declare synapse.fixture.v1.ListEtagRequest.etag as string: it carries the version as an entity tag, "
                        + "such as \"5\" or *.");
        assertReaderFails(OneofEtagRequest.getDescriptor(), prefix + "OneofEtagRequest.etag is part of oneof pick",
                "Move synapse.fixture.v1.OneofEtagRequest.etag out of oneof pick: it carries the version as an "
                        + "entity tag, such as \"5\" or *.");
    }

    @Test
    void ifNoneMatchBindsWithItsOwnRules() {
        // REST's If-None-Match on a write: parsed like If-Match, except that a weak tag binds its value.
        RecordReader puts = MessageMapping
                .reader(PutDocRequest.getDescriptor(), PutDocRec.class,
                        List.of(override("if_none_match", PutDocRec::unless)), List.of());

        assertThat(puts.read(put("*"))).isEqualTo(new PutDocRec("d", "t", VersionCondition.any()));
        assertThat(puts.read(put("\"3\""))).isEqualTo(new PutDocRec("d", "t", VersionCondition.of(3L)));
        assertThat(puts.read(put("W/\"3\""))).isEqualTo(new PutDocRec("d", "t", VersionCondition.of(3L)));
        assertThat(puts.read(put(""))).isEqualTo(new PutDocRec("d", "t", null));
        assertThat(puts.fieldPaths()).containsEntry("unless", "if_none_match");
        // Only an If-Match etag turns a STALE_VERSION into PRECONDITION_FAILED.
        assertThat(puts.ifMatchSent(put("*"))).isFalse();
        assertViolations(() -> puts.read(put("\"1\", \"2\"")),
                new Violation("if_none_match", "must be a single entity tag"));
    }

    @Test
    void eachMissingRequiredConditionHasItsOwnAdvice() {
        RecordReader strict = MessageMapping
                .reader(PutDocRequest.getDescriptor(), StrictPutDocRec.class,
                        List.of(override("if_none_match", StrictPutDocRec::unless)), List.of());
        RecordReader both = MessageMapping
                .reader(UpdateDocRequest.getDescriptor(), BothRec.class,
                        List.of(override("if_none_match", BothRec::unless)), List.of());
        Violation ifMatch = new Violation("etag",
                "is required; send the etag from a read of this resource, or * for any current version");
        Violation ifNoneMatch = new Violation("if_none_match",
                "is required; send * to write only if the resource doesn't exist, or the etag the resource must not "
                        + "have");

        assertPreconditionRequired(() -> strict.read(put("")), ifNoneMatch);
        // As REST answers a request without either required header: both violations, If-Match first.
        assertPreconditionRequired(() -> both.read(update("")), ifMatch, ifNoneMatch);
    }

    @Test
    void anUnboundTokenOnAWriteIsPreconditionFailed() {
        // As REST answers an If-Match or If-None-Match that a write route doesn't bind: the condition can't be
        // verified, and ignoring it would make the write unconditional.
        RecordReader renames = MessageMapping
                .reader(RenameDocRequest.getDescriptor(), RenameRec.class, List.of(),
                        List.of());
        DynamicMessage conditional = dynamic(RenameDocRequest
                .newBuilder()
                .setId("d")
                .setText("t")
                .setEtag("\"1\"")
                .build());
        DynamicMessage unconditional = dynamic(RenameDocRequest.newBuilder().setId("d").setText("t").build());
        DynamicMessage ifNoneMatch = dynamic(UpdateDocRequest
                .newBuilder()
                .setId("d")
                .setText("t")
                .setIfNoneMatch("*")
                .build());

        assertPreconditionFailed(() -> renames.read(conditional));
        assertPreconditionFailed(() -> updates().read(ifNoneMatch));
        assertThat(renames.read(unconditional)).isEqualTo(new RenameRec("d", "t"));
        // On a read, REST ignores an unbound If-Match, and so does gRPC.
        RecordReader read = MessageMapping
                .reader(RenameDocRequest.getDescriptor(), RenameRec.class, List.of(),
                        List.of(), false);
        assertThat(read.read(conditional)).isEqualTo(new RenameRec("d", "t"));
    }

    @Test
    void aWeakEtagBindsOnARead() {
        // REST checks a weak If-Match on writes only.
        RecordReader read = MessageMapping
                .reader(UpdateDocRequest.getDescriptor(), DocUpdate.class, List.of(),
                        List.of(), false);

        assertThat(read.read(update("W/\"5\""))).isEqualTo(new DocUpdate("d", "t", VersionCondition.of(5L)));
        assertThat(read.ifMatchSent(update("W/\"5\""))).isTrue();
    }

    @Test
    void onlyAVersionConditionBindsATokenField() {
        assertReaderFails(RenameDocRequest.getDescriptor(), PlainEtagRec.class,
                "PlainEtagRec.etag is mapped to synapse.fixture.v1.RenameDocRequest.etag, which carries an entity "
                        + "tag: only a VersionCondition binds from it",
                "Declare PlainEtagRec.etag as a VersionCondition, or map it to another field with .map(...).");
    }

    private static DynamicMessage put(String ifNoneMatch) {
        return dynamic(PutDocRequest.newBuilder().setId("d").setText("t").setIfNoneMatch(ifNoneMatch).build());
    }

    private static void assertPreconditionRequired(Executable read, Violation... expected) {
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(read::execute)
                .satisfies(e -> {
                    assertThat(e.code()).isEqualTo(SynapseErrors.PRECONDITION_REQUIRED.code());
                    assertThat(e.violations()).containsExactly(expected);
                });
    }

    private static void assertPreconditionFailed(Executable read) {
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(read::execute)
                .satisfies(e -> {
                    assertThat(e.code()).isEqualTo(SynapseErrors.PRECONDITION_FAILED.code());
                    assertThat(e.violations()).isEmpty();
                });
    }

    private static void assertReaderFails(com.google.protobuf.Descriptors.Descriptor message, Class<?> record,
            String problem, String action) {
        assertThatExceptionOfType(MappingException.class)
                .isThrownBy(() -> MessageMapping.reader(message, record, List.of(), List.of()))
                .satisfies(e -> {
                    assertThat(e.problem()).isEqualTo(problem);
                    assertThat(e.action()).isEqualTo(action);
                });
    }

    private static void assertReaderFails(com.google.protobuf.Descriptors.Descriptor message, String problem,
            String action) {
        assertThatExceptionOfType(MappingException.class)
                .isThrownBy(() -> MessageMapping.reader(message, DocDeletion.class, List.of(), List.of()))
                .satisfies(e -> {
                    assertThat(e.problem()).isEqualTo(problem);
                    assertThat(e.action()).isEqualTo(action);
                });
    }

    @Test
    void versionConditionOutsideTheInputRecordCantBeMapped() {
        assertThatExceptionOfType(MappingException.class)
                .isThrownBy(() -> MessageMapping
                        .reader(CreateItemRequest.getDescriptor(), Wrapping.class, List.of(),
                                List.of()))
                .satisfies(e -> assertThat(e.problem())
                        .isEqualTo("Nested.version is a VersionCondition outside the input record Wrapping"));
        assertThatExceptionOfType(MappingException.class)
                .isThrownBy(() -> MessageMapping
                        .reader(GetDocRequest.getDescriptor(), DocDeletion.class, List.of(),
                                List.of()))
                .satisfies(e -> assertThat(e.problem())
                        .isEqualTo("DocDeletion.version is a VersionCondition, but synapse.fixture.v1.GetDocRequest "
                                + "has no string field etag"));
    }

    @Test
    void pageSizeAndTokenBindPageQuery() {
        RecordReader reader = listings(null);

        assertThat(reader.read(listing(builder -> builder.setPageSize(5))))
                .isEqualTo(new Listing("s", PageQuery.first(5)));
        // The caller's token, as sent: the edge unwraps it into the operation's cursor (PageCursors.unwrap).
        assertThat(reader.read(listing(builder -> builder.setPageSize(5).setPageToken("token-1"))))
                .isEqualTo(new Listing("s", PageQuery.ofCursor("token-1", 5, List.of())));
    }

    @Test
    void zeroPageSizeIsTheDefault() {
        assertThat(listings(null).read(listing(builder -> builder.setPageSize(0))))
                .isEqualTo(new Listing("s", PageQuery.first(20)));
        RecordReader seven = MessageMapping
                .reader(ListPagedBooksRequest.getDescriptor(), Listing.class, List.of(), List.of(), false,
                        new PagingRules(7, 10, 0, null));
        assertThat(seven.read(listing(builder -> builder))).isEqualTo(new Listing("s", PageQuery.first(7)));
    }

    @Test
    void skipBindsAnOffset() {
        RecordReader reader = listings(null);

        assertThat(reader.read(listing(builder -> builder.setSkip(40))))
                .isEqualTo(new Listing("s", PageQuery.ofOffset(40, 20, List.of())));
        assertThat(reader.read(listing(builder -> builder.setSkip(1000))))
                .isEqualTo(new Listing("s", PageQuery.ofOffset(1000, 20, List.of())));
        assertViolations(() -> reader.read(listing(builder -> builder.setSkip(1001))),
                new Violation("skip", "must be less than or equal to 1000"));
        assertViolations(() -> reader.read(listing(builder -> builder.setSkip(-1))),
                new Violation("skip", "must be greater than or equal to 0"));
        assertViolations(() -> reader.read(listing(builder -> builder.setSkip(3).setPageToken("t"))),
                new Violation("page_token", "must not be combined with skip"));
    }

    @Test
    void pageSizeOverTheCapIsAViolation() {
        RecordReader reader = listings(null);

        assertViolations(() -> reader.read(listing(builder -> builder.setPageSize(101))),
                new Violation("page_size", "must be less than or equal to 100"));
        assertViolations(() -> reader.read(listing(builder -> builder.setPageSize(-1))),
                new Violation("page_size", "must be greater than or equal to 0"));
        // In REST's order: cursor, offset, size, sort.
        assertViolations(() -> listings(SortKeys.of(BookKey.class))
                .read(listing(builder -> builder.setSkip(2000).setPageSize(500).setOrderBy("x"))),
                new Violation("skip", "must be less than or equal to 1000"),
                new Violation("page_size", "must be less than or equal to 100"),
                new Violation("order_by", "has a key that is not allowed (allowed: title, published_year)"));
    }

    @Test
    void orderByUsesAip132() {
        RecordReader reader = listings(SortKeys.of(BookKey.class));
        List<SortOrder> expected = List
                .of(new SortOrder("TITLE", SortOrder.Direction.ASC),
                        new SortOrder("PUBLISHED_YEAR", SortOrder.Direction.DESC));

        assertThat(reader.read(listing(builder -> builder.setOrderBy("title, published_year desc"))))
                .isEqualTo(new Listing("s", new PageQuery(null, null, 20, expected)));
        // Spaces are insignificant, and asc may be spelled out.
        assertThat(reader.read(listing(builder -> builder.setOrderBy("  title asc ,published_year   desc "))))
                .isEqualTo(new Listing("s", new PageQuery(null, null, 20, expected)));
        assertThat(reader.read(listing(builder -> builder.setOrderBy("   "))))
                .isEqualTo(new Listing("s", PageQuery.first(20)));
    }

    @Test
    void unknownOrderByKeyIsInvalid() {
        RecordReader reader = listings(SortKeys.of(BookKey.class));

        // VALIDATION_FAILED, as REST's {sort, has a key that is not allowed (...)}, named after the gRPC field.
        assertViolations(() -> reader.read(listing(builder -> builder.setOrderBy("author"))),
                new Violation("order_by", "has a key that is not allowed (allowed: title, published_year)"));
        // Keys are the lower-case constant names: neither REST's camelCase nor the constant itself.
        assertViolations(() -> reader.read(listing(builder -> builder.setOrderBy("publishedYear,TITLE"))),
                new Violation("order_by", "has a key that is not allowed (allowed: title, published_year)"));
        assertViolations(() -> reader.read(listing(builder -> builder.setOrderBy("title,,published_year"))),
                new Violation("order_by", "is not a valid value"));
        assertViolations(() -> reader.read(listing(builder -> builder.setOrderBy("title up, title desc x"))),
                new Violation("order_by", "is not a valid value"));
        assertViolations(() -> reader.read(listing(builder -> builder.setOrderBy("title, title desc, author"))),
                new Violation("order_by", "names a key more than once"),
                new Violation("order_by", "has a key that is not allowed (allowed: title, published_year)"));
        // Without .sortable: REST's {sort, is not supported}, one text on both protocols.
        assertViolations(() -> listings(null).read(listing(builder -> builder.setOrderBy("title"))),
                new Violation("order_by", "is not supported"));
        // Only U+0020 separates words, and asc/desc are lower case: anything else is not a valid value.
        assertViolations(() -> reader.read(listing(builder -> builder.setOrderBy("title\tdesc"))),
                new Violation("order_by", "is not a valid value"));
        assertViolations(() -> reader.read(listing(builder -> builder.setOrderBy("title DESC"))),
                new Violation("order_by", "is not a valid value"));
    }

    @Test
    void pageFieldsWithPresenceFollowRestsRules() {
        RecordReader sortable = MessageMapping
                .reader(OptionalPagesRequest.getDescriptor(), Listing.class, List.of(), List.of(), false,
                        new PagingRules(20, 100, 1000, SortKeys.of(BookKey.class)));
        RecordReader unsorted = MessageMapping
                .reader(OptionalPagesRequest.getDescriptor(), Listing.class, List.of(), List.of(), false,
                        new PagingRules(20, 100, 1000, null));

        // Unset fields are the defaults, as absent parameters are over REST.
        assertThat(sortable.read(optional(builder -> builder))).isEqualTo(new Listing("s", PageQuery.first(20)));
        // A sent 0 is a value: size=0 is a violation, offset=0 is offset 0.
        assertViolations(() -> sortable.read(optional(builder -> builder.setPageSize(0))),
                new Violation("page_size", "must be greater than or equal to 1"));
        assertViolations(() -> sortable.read(optional(builder -> builder.setPageSize(-1))),
                new Violation("page_size", "must be greater than or equal to 1"));
        assertThat(sortable.read(optional(builder -> builder.setSkip(0))))
                .isEqualTo(new Listing("s", PageQuery.ofOffset(0, 20, List.of())));
        // A sent skip with a token, even 0, as REST's cursor with any offset.
        assertViolations(() -> sortable.read(optional(builder -> builder.setSkip(0).setPageToken("t"))),
                new Violation("page_token", "must not be combined with skip"));
        // A sent empty token or sort, as REST's cursor= and sort=.
        assertViolations(() -> sortable.read(optional(builder -> builder.setPageToken(""))),
                new Violation("page_token", "is not a valid value"));
        assertViolations(() -> sortable.read(optional(builder -> builder.setOrderBy(""))),
                new Violation("order_by", "is not a valid value"));
        assertViolations(() -> unsorted.read(optional(builder -> builder.setOrderBy(""))),
                new Violation("order_by", "is not supported"));
    }

    private static DynamicMessage optional(java.util.function.UnaryOperator<OptionalPagesRequest.Builder> customizer) {
        return dynamic(customizer.apply(OptionalPagesRequest.newBuilder().setShelf("s")).build());
    }

    @Test
    void pageQueryInputReadsThePageFields() {
        RecordReader reader = MessageMapping
                .reader(PageOnlyRequest.getDescriptor(), PageQuery.class, List.of(), List.of(), false,
                        PagingRules.defaults());

        assertThat(reader.read(dynamic(PageOnlyRequest.newBuilder().setPageSize(3).setPageToken("t").build())))
                .isEqualTo(PageQuery.ofCursor("t", 3, List.of()));
        assertViolations(() -> reader.read(dynamic(PageOnlyRequest.newBuilder().setPageSize(101).build())),
                new Violation("page_size", "must be less than or equal to 100"));
        assertThat(reader.fieldPaths())
                .containsExactlyInAnyOrderEntriesOf(Map.of("cursor", "page_token", "size", "page_size"));
    }

    @Test
    void pageFieldsNameThePageQueryComponents() {
        // The pipeline names a PageQuery's violations by component (page.size); the edge reports the field.
        assertThat(listings(SortKeys.of(BookKey.class)).fieldPaths())
                .containsEntry("shelf", "shelf")
                .containsEntry("page.cursor", "page_token")
                .containsEntry("page.offset", "skip")
                .containsEntry("page.size", "page_size")
                .containsEntry("page.sort", "order_by");
    }

    @Test
    void pageQueryOutsideTheInputRecordCantBeMapped() {
        assertThatExceptionOfType(MappingException.class)
                .isThrownBy(() -> MessageMapping
                        .reader(CreateItemRequest.getDescriptor(), PageWrapping.class, List.of(), List.of()))
                .satisfies(e -> assertThat(e.problem())
                        .isEqualTo("PagedNested.page is a PageQuery outside the input record PageWrapping"));
    }

    @Test
    void theTokenIsUnwrappedAfterTheViolationsAndBeforeAnUnverifiableCondition() {
        RecordReader reader = MessageMapping
                .reader(PagedUpdateRequest.getDescriptor(), PagedUpdate.class, List.of(), List.of(), true,
                        PagingRules.defaults());
        RecordReader strict = MessageMapping
                .reader(PagedUpdateRequest.getDescriptor(), StrictPagedUpdate.class, List.of(), List.of(), true,
                        PagingRules.defaults());
        SynapseException rejected = SynapseException
                .ofViolations(SynapseErrors.VALIDATION_FAILED, List.of(new Violation("page_token", "rejected")));
        DynamicMessage weak = dynamic(
                PagedUpdateRequest.newBuilder().setShelf("s").setPageToken("t").setEtag("W/\"1\"").build());
        DynamicMessage missing = dynamic(PagedUpdateRequest.newBuilder().setShelf("s").setPageToken("t").build());

        // REST: the cursor's 400 comes before the 412 of an If-Match it can't verify...
        assertViolations(() -> reader.read(weak, input -> {
            throw rejected;
        }), new Violation("page_token", "rejected"));
        assertPreconditionFailed(() -> reader.read(weak, input -> input));
        // ...and after the 428 of a required If-Match, which the input can't be built without.
        assertPreconditionRequired(() -> strict.read(missing, input -> {
            throw rejected;
        }), new Violation("etag", MessageMapping.IF_MATCH_REQUIRED));
        // The unwrapped input is the one returned.
        PagedUpdate unwrapped = new PagedUpdate("s", PageQuery.ofCursor("raw", 20, List.of()), null);
        assertThat(reader.read(missing, input -> unwrapped)).isSameAs(unwrapped);
    }

    private static RecordReader listings(@Nullable SortKeys keys) {
        return MessageMapping
                .reader(ListPagedBooksRequest.getDescriptor(), Listing.class, List.of(), List.of(), false,
                        new PagingRules(20, 100, 1000, keys));
    }

    private static DynamicMessage listing(
            java.util.function.UnaryOperator<ListPagedBooksRequest.Builder> customizer) {
        return dynamic(customizer.apply(ListPagedBooksRequest.newBuilder().setShelf("s")).build());
    }

    private static RecordReader deletions() {
        return MessageMapping.reader(DeleteDocRequest.getDescriptor(), DocDeletion.class, List.of(), List.of());
    }

    private static RecordReader updates() {
        return MessageMapping.reader(UpdateDocRequest.getDescriptor(), DocUpdate.class, List.of(), List.of());
    }

    private static DynamicMessage deletion(String etag) {
        return dynamic(DeleteDocRequest.newBuilder().setId("d").setEtag(etag).build());
    }

    private static DynamicMessage update(String etag) {
        return dynamic(UpdateDocRequest.newBuilder().setId("d").setText("t").setEtag(etag).build());
    }

    private static DynamicMessage deletionOrUpdate(RecordReader reader, String etag) {
        return reader.fieldPaths().containsKey("text") ? update(etag) : deletion(etag);
    }

    private static void assertViolations(Executable read, Violation... expected) {
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(read::execute)
                .satisfies(e -> {
                    assertThat(e.code()).isEqualTo(SynapseErrors.VALIDATION_FAILED.code());
                    assertThat(e.violations()).containsExactly(expected);
                });
    }

    static Scalars scalars() {
        return Scalars
                .newBuilder()
                .setInt32Value(-1)
                .setSint32Value(-2)
                .setSfixed32Value(-3)
                .setUint32Value(4)
                .setFixed32Value(5)
                .setInt64Value(-6L)
                .setSint64Value(-7L)
                .setSfixed64Value(-8L)
                .setUint64Value(9L)
                .setFixed64Value(10L)
                .setBoolValue(true)
                .setDoubleValue(1.5)
                .setFloatValue(2.5f)
                .setBytesValue(ByteString.copyFromUtf8("raw"))
                .setDoubleWrapper(DoubleValue.of(3.5))
                .setFloatWrapper(FloatValue.of(4.5f))
                .setInt64Wrapper(Int64Value.of(11L))
                .setUint64Wrapper(UInt64Value.of(12L))
                .setInt32Wrapper(Int32Value.of(13))
                .setUint32Wrapper(UInt32Value.of(14))
                .setBoolWrapper(BoolValue.of(false))
                .setStringWrapper(StringValue.of(MappingFixtures.ID.toString()))
                .setBytesWrapper(BytesValue.of(ByteString.copyFromUtf8("wrapped")))
                .addTimes(Timestamp.newBuilder().setSeconds(MappingFixtures.CREATED.getEpochSecond()))
                .setOptionalGenre(io.americanexpress.synapse.api.grpc.fixture.proto.Genre.GENRE_SCIENCE)
                .setShelf(io.americanexpress.synapse.api.grpc.fixture.proto.Shelf.BOTTOM)
                .build();
    }

    static AllScalars allScalars() {
        return new AllScalars(-1, -2, -3L, 4, 5L, -6L, -7L, -8L, 9L, 10L, true, 1.5, 2.5f,
                "raw".getBytes(StandardCharsets.UTF_8),
                List.of(Instant.ofEpochSecond(MappingFixtures.CREATED.getEpochSecond())), MappingFixtures.Shelf.BOTTOM);
    }

    static Wrappers wrappers() {
        return new Wrappers(3.5, 4.5f, 11L, 12L, 13, 14, false, MappingFixtures.ID,
                "wrapped".getBytes(StandardCharsets.UTF_8),
                Genre.SCIENCE);
    }

    /** Every scalar of {@code Scalars}, a repeated well-known type and an enum named like its constants. */
    @SuppressWarnings("ArrayRecordComponent")
    record AllScalars(int int32Value, int sint32Value, long sfixed32Value, int uint32Value, long fixed32Value,
            long int64Value, Long sint64Value, long sfixed64Value, long uint64Value, Long fixed64Value,
            boolean boolValue, Double doubleValue, float floatValue, byte[] bytesValue, List<Instant> times,
            MappingFixtures.Shelf shelf) {
    }

    /** The wrappers and the proto3 optional enum of {@code Scalars}. */
    @SuppressWarnings("ArrayRecordComponent")
    record Wrappers(@Nullable Double doubleWrapper, @Nullable Float floatWrapper, @Nullable Long int64Wrapper,
            @Nullable Long uint64Wrapper, @Nullable Integer int32Wrapper, @Nullable Integer uint32Wrapper,
            @Nullable Boolean boolWrapper, java.util.@Nullable UUID stringWrapper, byte @Nullable [] bytesWrapper,
            @Nullable Genre optionalGenre) {
    }

    record Unsigned(int uint32Value, long fixed32Value, long uint64Value) {
    }

    record Times(List<Instant> times) {
    }

    record Containers(List<AuthorRec> authors, Map<Integer, AuthorRec> authorsByRank, List<Genre> genres) {
    }

    record Title(String title) {
    }

    record Flat(String name, String country) {
    }

    record NodeRec(String name, @Nullable NodeRec next) {
    }

    record Checked(String name, CheckedAuthor author) {
    }

    record CheckedAuthor(String name) {

        CheckedAuthor {
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Empty name");
            }
        }
    }

    record SelfReporting(String name) {

        SelfReporting {
            throw new SynapseException(SynapseErrors.NOT_FOUND);
        }
    }

    /** A required condition, from {@code DeleteDocRequest.etag}. */
    record DocDeletion(String id, VersionCondition<Long> version) {
    }

    /** An optional condition, from {@code UpdateDocRequest.etag}. */
    record DocUpdate(String id, String text, @Nullable VersionCondition<Long> version) {
    }

    /** A condition on a string version. */
    record Labeled(String id, VersionCondition<String> version) {
    }

    /** A condition on a version type no string converts to. */
    record StampVersioned(String id, VersionCondition<Stamp> version) {
    }

    /** A value with no string form: no constructor or factory method takes a string. */
    record Stamp(long value) {
    }

    /** A condition on a UUID, which a string converts to, but which is not a version type. */
    record UuidVersioned(String id, VersionCondition<java.util.UUID> version) {
    }

    /** A condition on a {@code LocalDate}: ISO-8601 tokens only. */
    record DateVersioned(String id, VersionCondition<LocalDate> version) {
    }

    /** A create-only or unless-at-version put: If-None-Match semantics, from {@code if_none_match}. */
    record PutDocRec(String id, String text, @Nullable VersionCondition<Long> unless) {
    }

    /** A put that requires {@code if_none_match}. */
    record StrictPutDocRec(String id, String text, VersionCondition<Long> unless) {
    }

    /** One condition per field: If-Match from {@code etag}, If-None-Match from {@code if_none_match}. */
    record BothRec(String id, String text, VersionCondition<Long> version, VersionCondition<Long> unless) {
    }

    /** A write without a condition, although its request has an {@code etag} field. */
    record RenameRec(String id, String text) {
    }

    /** A plain component named like the token field. */
    record PlainEtagRec(String id, String text, String etag) {
    }

    /** A condition inside a nested record ({@code CreateItemRequest.item}). */
    record Wrapping(Nested item) {
    }

    record Nested(String id, VersionCondition<Long> version) {
    }

    /** The sort keys of {@code paging.proto}'s books. */
    enum BookKey {
        TITLE, PUBLISHED_YEAR
    }

    /** A filter and a page, from {@code ListPagedBooksRequest}. */
    record Listing(String shelf, PageQuery page) {
    }

    /** A paged write with an optional If-Match, from {@code PagedUpdateRequest}. */
    record PagedUpdate(String shelf, PageQuery page, @Nullable VersionCondition<Long> version) {
    }

    /** A paged write with a required If-Match. */
    record StrictPagedUpdate(String shelf, PageQuery page, VersionCondition<Long> version) {
    }

    /** A page inside a nested record ({@code CreateItemRequest.item}). */
    record PageWrapping(PagedNested item) {
    }

    record PagedNested(String id, PageQuery page) {
    }
}
