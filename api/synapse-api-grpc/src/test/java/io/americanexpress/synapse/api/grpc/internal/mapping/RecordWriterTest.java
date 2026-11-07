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

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Empty;
import com.google.protobuf.InvalidProtocolBufferException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;

import io.americanexpress.synapse.api.grpc.fixture.proto.Author;
import io.americanexpress.synapse.api.grpc.fixture.proto.Item;
import io.americanexpress.synapse.api.grpc.fixture.proto.ListTagsResponse;
import io.americanexpress.synapse.api.grpc.fixture.proto.Node;
import io.americanexpress.synapse.api.grpc.fixture.proto.PlainNumbers;
import io.americanexpress.synapse.api.grpc.fixture.proto.Scalars;
import io.americanexpress.synapse.api.grpc.internal.binding.FieldOverride;
import io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.AuthorRec;
import io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.CountryCode;
import io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.Genre;
import io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.ItemRec;
import io.americanexpress.synapse.api.grpc.internal.mapping.RecordReaderTest.AllScalars;
import io.americanexpress.synapse.api.grpc.internal.mapping.RecordReaderTest.Containers;
import io.americanexpress.synapse.api.grpc.internal.mapping.RecordReaderTest.Flat;
import io.americanexpress.synapse.api.grpc.internal.mapping.RecordReaderTest.NodeRec;
import io.americanexpress.synapse.api.grpc.internal.mapping.RecordReaderTest.Wrappers;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;

import static io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.item;
import static io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.itemOverrides;
import static io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.itemRecord;
import static io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.override;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class RecordWriterTest {

    @Test
    void writesEveryBuiltInType() throws InvalidProtocolBufferException {
        RecordWriter writer = MessageMapping
                .writer(Item.getDescriptor(), ResolvableType.forClass(ItemRec.class), itemOverrides());

        DynamicMessage written = writer.write(itemRecord());

        assertThat(Item.parseFrom(written.toByteString())).isEqualTo(item());
        assertThat(MessageMapping
                .reader(Item.getDescriptor(), ItemRec.class, itemOverrides(), List.of())
                .read(written)).isEqualTo(itemRecord());
        assertThat(writer.fieldPaths())
                .containsEntry("createdAt", "created_at")
                .containsEntry("author.countryCode", "author.country_code");
    }

    @Test
    void nullComponentsLeaveTheirFieldsUnset() throws InvalidProtocolBufferException {
        RecordWriter writer = MessageMapping
                .writer(Item.getDescriptor(), ResolvableType.forClass(ItemRec.class), itemOverrides());
        ItemRec record = itemRecord();
        ItemRec partial = new ItemRec(record.id(), record.name(), null, null, null, null, record.genre(),
                record.tags(), record.counts(), record.author(), null, record.version());

        Item written = Item.parseFrom(writer.write(partial).toByteString());

        assertThat(written)
                .isEqualTo(item()
                        .toBuilder()
                        .clearCreatedAt()
                        .clearTtl()
                        .clearRating()
                        .clearNote()
                        .clearSku()
                        .build());
    }

    @Test
    void javaTimeIsReadInIsoOnly() {
        List<FieldOverride> overrides = List
                .of(override("name", Dates::date), override("note", Dates::dateTime),
                        override("id", Dates::time));
        RecordReader reader = MessageMapping.reader(Item.getDescriptor(), Dates.class, overrides, List.of());
        Item localStyle = Item.newBuilder().setName("11/6/26").setNote("2026-11-06T10:15:30").setId("10:15:30").build();
        Locale previous = Locale.getDefault();
        try {
            // The locale whose short style "11/6/26" is: only ISO may read it.
            Locale.setDefault(Locale.US);
            assertThatExceptionOfType(SynapseException.class)
                    .isThrownBy(() -> reader.read(MappingFixtures.dynamic(localStyle)))
                    .satisfies(e -> assertThat(e.violations())
                            .containsExactly(new Violation("name", "is not a valid value")));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void instantOnAStringFieldIsReadInIsoOnly() throws InvalidProtocolBufferException {
        List<FieldOverride> overrides = List.of(override("name", Stamp::at));
        RecordReader reader = MessageMapping.reader(Item.getDescriptor(), Stamp.class, overrides, List.of());

        // Epoch milliseconds and RFC 1123 are what Spring's own Instant formatter also accepts.
        for (String notIso : List.of("1700000000000", "Tue, 3 Jun 2008 11:05:30 GMT")) {
            Item item = Item.newBuilder().setName(notIso).build();
            assertThatExceptionOfType(SynapseException.class)
                    .as(notIso)
                    .isThrownBy(() -> reader.read(MappingFixtures.dynamic(item)))
                    .satisfies(e -> assertThat(e.violations())
                            .containsExactly(new Violation("name", "is not a valid value")));
        }
        Item iso = Item.newBuilder().setName("2026-11-06T10:15:30Z").build();
        assertThat(reader.read(MappingFixtures.dynamic(iso)))
                .isEqualTo(new Stamp(java.time.Instant.parse("2026-11-06T10:15:30Z")));
        RecordWriter writer = MessageMapping
                .writer(Item.getDescriptor(), ResolvableType.forClass(Stamp.class), overrides);
        assertThat(Item
                .parseFrom(writer
                        .write(new Stamp(java.time.Instant.parse("2026-11-06T10:15:30Z")))
                        .toByteString())
                .getName()).isEqualTo("2026-11-06T10:15:30Z");
    }

    @Test
    void javaTimeIsWrittenInIsoWhateverTheLocale() throws InvalidProtocolBufferException {
        List<FieldOverride> overrides = List
                .of(override("name", Dates::date), override("note", Dates::dateTime),
                        override("id", Dates::time));
        RecordWriter writer = MessageMapping
                .writer(Item.getDescriptor(), ResolvableType.forClass(Dates.class), overrides);
        Dates dates = new Dates(LocalDate.of(2026, 11, 6), LocalDateTime.of(2026, 11, 6, 10, 15, 30),
                LocalTime.of(10, 15, 30));
        Locale previous = Locale.getDefault();
        Item written;
        try {
            Locale.setDefault(Locale.GERMANY);
            written = Item.parseFrom(writer.write(dates).toByteString());
        } finally {
            Locale.setDefault(previous);
        }

        assertThat(written.getName()).isEqualTo("2026-11-06");
        assertThat(written.getNote()).isEqualTo("2026-11-06T10:15:30");
        assertThat(written.getId()).isEqualTo("10:15:30");
        assertThat(MessageMapping
                .reader(Item.getDescriptor(), Dates.class, overrides, List.of())
                .read(MappingFixtures.dynamic(written))).isEqualTo(dates);
    }

    @Test
    void voidAndEmpty() {
        RecordWriter writer = MessageMapping
                .writer(Empty.getDescriptor(), ResolvableType.forClass(Void.class), List.of());

        assertThat(writer.write(null)).isEqualTo(DynamicMessage.getDefaultInstance(Empty.getDescriptor()));
        assertThat(writer.fieldPaths()).isEmpty();
    }

    @Test
    void nullOutputIsTheEmptyMessage() {
        RecordWriter writer = MessageMapping
                .writer(Item.getDescriptor(), ResolvableType.forClass(ItemRec.class), itemOverrides());

        assertThat(writer.write(null)).isEqualTo(DynamicMessage.getDefaultInstance(Item.getDescriptor()));
    }

    @Test
    void listOutputUsesTheOnlyRepeatedField() throws InvalidProtocolBufferException {
        RecordWriter writer = MessageMapping
                .writer(ListTagsResponse.getDescriptor(),
                        ResolvableType.forClassWithGenerics(List.class, String.class),
                        List.of());

        DynamicMessage written = writer.write(List.of("classic", "space"));

        assertThat(ListTagsResponse.parseFrom(written.toByteString()))
                .isEqualTo(ListTagsResponse.newBuilder().addTags("classic").addTags("space").build());
        assertThat(writer.write(null)).isEqualTo(DynamicMessage.getDefaultInstance(ListTagsResponse.getDescriptor()));
    }

    @Test
    void optionalOutputWritesItsValue() throws InvalidProtocolBufferException {
        RecordWriter writer = MessageMapping
                .writer(Item.getDescriptor(),
                        ResolvableType.forClassWithGenerics(Optional.class, ItemRec.class),
                        itemOverrides());

        assertThat(Item.parseFrom(writer.write(itemRecord()).toByteString())).isEqualTo(item());
    }

    @Test
    void writesEveryScalarAndWrapper() throws InvalidProtocolBufferException {
        RecordWriter scalars = MessageMapping
                .writer(Scalars.getDescriptor(), ResolvableType.forClass(AllScalars.class), List.of());
        RecordWriter wrappers = MessageMapping
                .writer(Scalars.getDescriptor(), ResolvableType.forClass(Wrappers.class), List.of());

        Scalars written = Scalars
                .parseFrom(scalars.write(RecordReaderTest.allScalars()).toByteString())
                .toBuilder()
                .mergeFrom(Scalars.parseFrom(wrappers.write(RecordReaderTest.wrappers()).toByteString()))
                .build();

        assertThat(written).isEqualTo(RecordReaderTest.scalars());
    }

    @Test
    void writesContainersOfRecords() throws InvalidProtocolBufferException {
        RecordWriter writer = MessageMapping
                .writer(Scalars.getDescriptor(), ResolvableType.forClass(Containers.class), List.of());
        Containers containers = new Containers(List.of(new AuthorRec("a", CountryCode.valueOf("US"))),
                Map.of(2, new AuthorRec("c", CountryCode.valueOf("FR"))), List.of(Genre.SCIENCE, Genre.FICTION));

        Scalars written = Scalars.parseFrom(writer.write(containers).toByteString());

        assertThat(written)
                .isEqualTo(Scalars
                        .newBuilder()
                        .addAuthors(Author.newBuilder().setName("a").setCountryCode("US"))
                        .putAuthorsByRank(2, Author.newBuilder().setName("c").setCountryCode("FR").build())
                        .addGenres(io.americanexpress.synapse.api.grpc.fixture.proto.Genre.GENRE_SCIENCE)
                        .addGenres(io.americanexpress.synapse.api.grpc.fixture.proto.Genre.GENRE_FICTION)
                        .build());
    }

    @Test
    void negativeDurationKeepsOneSign() throws InvalidProtocolBufferException {
        RecordWriter writer = MessageMapping
                .writer(Item.getDescriptor(), ResolvableType.forClass(Ttl.class), List.of());

        Item written = Item.parseFrom(writer.write(new Ttl(Duration.ofMillis(-1500))).toByteString());

        assertThat(written.getTtl())
                .isEqualTo(
                        com.google.protobuf.Duration.newBuilder().setSeconds(-1).setNanos(-500_000_000).build());
        assertThat(MessageMapping
                .reader(Item.getDescriptor(), Ttl.class, List.of(), List.of())
                .read(MappingFixtures.dynamic(written))).isEqualTo(new Ttl(Duration.ofMillis(-1500)));
    }

    @Test
    void flattenedPathWritesANestedField() throws InvalidProtocolBufferException {
        RecordWriter writer = MessageMapping
                .writer(Item.getDescriptor(), ResolvableType.forClass(Flat.class),
                        List.of(override("author.country_code", Flat::country)));

        Item written = Item.parseFrom(writer.write(new Flat("Dune", "US")).toByteString());

        assertThat(written)
                .isEqualTo(Item
                        .newBuilder()
                        .setName("Dune")
                        .setAuthor(Author.newBuilder().setCountryCode("US"))
                        .build());
        assertThat(writer.fieldPaths()).containsEntry("country", "author.country_code");
    }

    @Test
    void recursiveRecordsFollowTheMessage() throws InvalidProtocolBufferException {
        RecordWriter writer = MessageMapping
                .writer(Node.getDescriptor(), ResolvableType.forClass(NodeRec.class), List.of());

        Node written = Node.parseFrom(writer.write(new NodeRec("a", new NodeRec("b", null))).toByteString());

        assertThat(written).isEqualTo(Node.newBuilder().setName("a").setNext(Node.newBuilder().setName("b")).build());
    }

    @Test
    void timestampOutsideTheProtobufRangeFails() {
        RecordWriter writer = MessageMapping
                .writer(Item.getDescriptor(), ResolvableType.forClass(Created.class), List.of());

        assertThatIllegalStateException()
                .isThrownBy(() -> writer.write(new Created(java.time.Instant.parse("+10000-01-01T00:00:00Z"))))
                .withMessage("Created.createdAt is out of range for synapse.fixture.v1.Item.created_at "
                        + "(google.protobuf.Timestamp)");
        assertThatIllegalStateException()
                .isThrownBy(() -> writer.write(new Created(java.time.Instant.parse("0000-12-31T23:59:59Z"))));
        assertThat(writer
                .write(new Created(java.time.Instant.parse("9999-12-31T23:59:59.999999999Z")))
                .getAllFields()).hasSize(1);
    }

    @Test
    void valueOutOfTheFieldRangeFails() {
        RecordWriter signed = MessageMapping
                .writer(PlainNumbers.getDescriptor(), ResolvableType.forClass(WideYear.class), List.of());
        RecordWriter unsigned = MessageMapping
                .writer(Scalars.getDescriptor(), ResolvableType.forClass(Unsigned.class), List.of());

        assertThatIllegalStateException()
                .isThrownBy(() -> signed.write(new WideYear(1L << 40)))
                .withMessage("WideYear.year is out of range for synapse.fixture.v1.PlainNumbers.year (int32)");
        assertThatIllegalStateException()
                .isThrownBy(() -> unsigned.write(new Unsigned(-1, 0L, 0L)))
                .withMessage("Unsigned.uint32Value is out of range for synapse.fixture.v1.Scalars.uint32_value "
                        + "(uint32)");
        assertThatIllegalStateException()
                .isThrownBy(() -> unsigned.write(new Unsigned(0, 1L << 33, 0L)))
                .withMessage("Unsigned.fixed32Value is out of range for synapse.fixture.v1.Scalars.fixed32_value "
                        + "(fixed32)");
        assertThatIllegalStateException()
                .isThrownBy(() -> unsigned.write(new Unsigned(0, 0L, -1L)))
                .withMessage("Unsigned.uint64Value is out of range for synapse.fixture.v1.Scalars.uint64_value "
                        + "(uint64)");
        assertThat(signed
                .write(new WideYear(-5L))
                .getField(MappingFixtures
                        .field(PlainNumbers.getDescriptor(),
                                "year")))
                .isEqualTo(-5);
    }

    @Test
    void nullElementFails() {
        RecordWriter writer = MessageMapping
                .writer(ListTagsResponse.getDescriptor(),
                        ResolvableType.forClassWithGenerics(List.class, String.class),
                        List.of());

        assertThatIllegalStateException()
                .isThrownBy(() -> writer.write(Arrays.asList("a", null)))
                .withMessage(
                        "the output List<String> holds a null element for synapse.fixture.v1.ListTagsResponse.tags");
    }

    @Test
    void nullMapValueFails() {
        RecordWriter writer = MessageMapping
                .writer(Scalars.getDescriptor(), ResolvableType.forClass(Containers.class), List.of());
        Map<Integer, @Nullable AuthorRec> byRank = new java.util.HashMap<>();
        byRank.put(1, null);
        Containers containers = new Containers(List.of(), castMap(byRank), List.of());

        assertThatIllegalStateException()
                .isThrownBy(() -> writer.write(containers))
                .withMessage("Containers.authorsByRank holds a null element for "
                        + "synapse.fixture.v1.Scalars.authors_by_rank");
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, AuthorRec> castMap(Map<Integer, @Nullable AuthorRec> map) {
        return (Map<Integer, AuthorRec>) (Map<?, ?>) map;
    }

    record Dates(LocalDate date, LocalDateTime dateTime, LocalTime time) {
    }

    record Created(java.time.@Nullable Instant createdAt) {
    }

    record Stamp(java.time.Instant at) {
    }

    record Ttl(@Nullable Duration ttl) {
    }

    record WideYear(long year) {
    }

    record Unsigned(int uint32Value, long fixed32Value, long uint64Value) {
    }
}
