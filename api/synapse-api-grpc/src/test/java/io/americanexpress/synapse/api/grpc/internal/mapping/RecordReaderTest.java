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
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.protobuf.BoolValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Empty;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.StringValue;
import com.google.protobuf.Timestamp;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import io.americanexpress.synapse.api.grpc.fixture.proto.Author;
import io.americanexpress.synapse.api.grpc.fixture.proto.CreateItemRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.Item;
import io.americanexpress.synapse.api.grpc.fixture.proto.Node;
import io.americanexpress.synapse.api.grpc.fixture.proto.Scalars;
import io.americanexpress.synapse.api.grpc.internal.binding.ContextSource;
import io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.AuthorRec;
import io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.CountryCode;
import io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.CreateItemRec;
import io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.Genre;
import io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.ItemRec;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.core.internal.binding.ComponentRefs;
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
                new Violation("genre", MISSING), new Violation("author", MISSING));
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
                                Scalars.newBuilder().setUint64Value(1L).setFixed32Value(-1).build()))))
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
        assertThatExceptionOfType(com.google.protobuf.InvalidProtocolBufferException.class)
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
        assertThat(reader.read(dynamic(Item.newBuilder().setName("n").build()))).isEqualTo(new Flat("n", ""));
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
                .isThrownBy(() -> reader.read(dynamic(Author.getDefaultInstance())))
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
                .isThrownBy(() -> reader.read(dynamic(Author.getDefaultInstance())))
                .satisfies(e -> assertThat(e.code()).isEqualTo(SynapseErrors.NOT_FOUND.code()));
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
}
