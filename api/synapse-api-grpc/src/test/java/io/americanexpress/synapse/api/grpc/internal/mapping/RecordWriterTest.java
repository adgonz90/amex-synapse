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
import io.americanexpress.synapse.api.grpc.fixture.proto.Doc;
import io.americanexpress.synapse.api.grpc.fixture.proto.Int64TotalResponse;
import io.americanexpress.synapse.api.grpc.fixture.proto.Item;
import io.americanexpress.synapse.api.grpc.fixture.proto.ListPagedBooksResponse;
import io.americanexpress.synapse.api.grpc.fixture.proto.ListTagsResponse;
import io.americanexpress.synapse.api.grpc.fixture.proto.Node;
import io.americanexpress.synapse.api.grpc.fixture.proto.NumberDoc;
import io.americanexpress.synapse.api.grpc.fixture.proto.OneofEtagDoc;
import io.americanexpress.synapse.api.grpc.fixture.proto.PageWithoutTotalResponse;
import io.americanexpress.synapse.api.grpc.fixture.proto.PagedBook;
import io.americanexpress.synapse.api.grpc.fixture.proto.PlainDoc;
import io.americanexpress.synapse.api.grpc.fixture.proto.PlainNumbers;
import io.americanexpress.synapse.api.grpc.fixture.proto.Scalars;
import io.americanexpress.synapse.api.grpc.fixture.proto.StampedDoc;
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
import io.americanexpress.synapse.core.binding.ComponentRef;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.core.internal.binding.ComponentRefs;
import io.americanexpress.synapse.core.internal.binding.ResolvedComponent;
import io.americanexpress.synapse.core.internal.conditional.EntityTags;
import io.americanexpress.synapse.core.paging.PageResult;

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
    void etagWritesTheStrongTag() throws InvalidProtocolBufferException {
        RecordWriter writer = docs(DocView::version);

        Doc written = Doc.parseFrom(writer.write(new DocView("d", "t", 7L)).toByteString());

        assertThat(written.getEtag()).isEqualTo("\"7\"").isEqualTo(EntityTags.strong("7"));
        assertThat(written.getVersion()).isEqualTo(7L);
        assertThat(written.getId()).isEqualTo("d");
        // Without .etag the field stays unset, and so it does for a null component or a null output.
        assertThat(Doc
                .parseFrom(MessageMapping
                        .writer(Doc.getDescriptor(), ResolvableType.forClass(DocView.class), List.of())
                        .write(new DocView("d", "t", 7L))
                        .toByteString())
                .getEtag()).isEmpty();
        assertThat(Doc.parseFrom(docs(Labeled::label).write(new Labeled("d", "t", 1L, null)).toByteString()).getEtag())
                .isEmpty();
        assertThat(Doc.parseFrom(docs(Labeled::label).write(new Labeled("d", "t", 1L, "v1")).toByteString()))
                .satisfies(doc -> {
                    assertThat(doc.getEtag()).isEqualTo("\"v1\"");
                    assertThat(doc.getLabel()).isEqualTo("v1");
                });
        assertThat(docs(DocView::version).write(null))
                .isEqualTo(DynamicMessage.getDefaultInstance(Doc.getDescriptor()));
    }

    @Test
    void etagValueOutsideEtagcIsAnOperationBug() {
        RecordWriter writer = docs(Labeled::label);

        for (String bad : List.of("", "a b", "a\"b", "aĀ")) {
            assertThatIllegalStateException()
                    .as(bad)
                    .isThrownBy(() -> writer.write(new Labeled("d", "t", 1L, bad)))
                    .withMessageStartingWith("The etag value computed from the operation's output is not a valid "
                            + "entity tag: The version token ");
        }
        assertThatIllegalStateException()
                .isThrownBy(() -> writer.write(new Labeled("d", "t", 1L, "a b")))
                .withMessage("The etag value computed from the operation's output is not a valid entity tag: The "
                        + "version token has a character an entity tag can't carry (a double quote, a space, a "
                        + "control character, DEL or a character above U+00FF)");
    }

    @Test
    void etagIsTheSharedVersionTokenInEveryLocale() throws InvalidProtocolBufferException {
        // The token REST sends for the same value (SynapseRestAutoConfigurationTest), MVC's format notwithstanding.
        RecordWriter writer = MessageMapping
                .writer(StampedDoc.getDescriptor(), ResolvableType.forClass(Stamped.class), List.of(),
                        ComponentRefs.resolve(Stamped::modified));
        Locale saved = Locale.getDefault();
        try {
            for (Locale locale : List.of(Locale.US, Locale.GERMANY, Locale.forLanguageTag("ar-EG"))) {
                Locale.setDefault(locale);
                StampedDoc written = StampedDoc
                        .parseFrom(writer
                                .write(new Stamped("d", LocalDateTime.of(2026, 11, 7, 15, 4, 5)))
                                .toByteString());
                assertThat(written.getEtag()).as(locale.toLanguageTag()).isEqualTo("\"2026-11-07T15:04:05\"");
                assertThat(written.getModified()).isEqualTo("2026-11-07T15:04:05");
            }
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void etagInAOneofIsRejected() {
        assertThatExceptionOfType(MappingException.class)
                .isThrownBy(() -> MessageMapping
                        .writer(OneofEtagDoc.getDescriptor(), ResolvableType.forClass(DocView.class), List.of(),
                                ComponentRefs.resolve(DocView::version)))
                .satisfies(e -> {
                    assertThat(e.problem())
                            .isEqualTo(".etag(...) writes the response's string field etag, but "
                                    + "synapse.fixture.v1.OneofEtagDoc.etag is part of oneof pick");
                    assertThat(e.action())
                            .isEqualTo("Move synapse.fixture.v1.OneofEtagDoc.etag out of oneof pick, or remove "
                                    + ".etag(...).");
                });
    }

    @Test
    void etagNeedsAStringResponseFieldThatNoComponentWrites() {
        ResolvedComponent version = ComponentRefs.resolve(DocView::version);
        assertThatExceptionOfType(MappingException.class)
                .isThrownBy(() -> MessageMapping
                        .writer(PlainDoc.getDescriptor(),
                                ResolvableType.forClass(DocView.class), List.of(), version))
                .satisfies(e -> assertThat(e.problem())
                        .isEqualTo(
                                ".etag(...) writes the response's string field etag, but synapse.fixture.v1.PlainDoc "
                                        + "has no string field etag"));
        assertThatExceptionOfType(MappingException.class)
                .isThrownBy(() -> MessageMapping
                        .writer(Doc.getDescriptor(), ResolvableType.forClass(Tagged.class),
                                List.of(), ComponentRefs.resolve(Tagged::version)))
                .satisfies(e -> {
                    assertThat(e.problem())
                            .isEqualTo("Tagged.etag and .etag(...) both write synapse.fixture.v1.Doc.etag");
                    assertThat(e.action()).isEqualTo("Map Tagged.etag to another field, or remove .etag(...).");
                });
        assertThatExceptionOfType(MappingException.class)
                .isThrownBy(() -> MessageMapping
                        .writer(NumberDoc.getDescriptor(),
                                ResolvableType.forClass(DocView.class), List.of(), version))
                .satisfies(e -> {
                    assertThat(e.problem())
                            .isEqualTo(".etag(...) writes the response's string field etag, but "
                                    + "synapse.fixture.v1.NumberDoc.etag is int64, not string");
                    assertThat(e.action())
                            .isEqualTo("Declare synapse.fixture.v1.NumberDoc.etag as string, or remove .etag(...).");
                });
    }

    /** A doc versioned by a {@code LocalDateTime}, written as its ISO string and as the etag. */
    record Stamped(String id, LocalDateTime modified) {
    }

    private static <T> RecordWriter docs(ComponentRef<T, ?> etag) {
        Class<?> type = ComponentRefs.resolve(etag).recordType();
        return MessageMapping
                .writer(Doc.getDescriptor(), ResolvableType.forClass(type), List.of(),
                        ComponentRefs.resolve(etag));
    }

    /** {@code synapse.fixture.v1.Doc} without its etag, which {@code .etag(DocView::version)} fills. */
    record DocView(String id, String text, long version) {
    }

    /** A doc whose etag is its optional label. */
    record Labeled(String id, String text, long version, @Nullable String label) {
    }

    /** A doc with a component of its own named {@code etag}. */
    record Tagged(String id, long version, String etag) {
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
    void pageResultWritesItemsTokenHasNextAndTotal() throws InvalidProtocolBufferException {
        RecordWriter writer = MessageMapping
                .writer(ListPagedBooksResponse.getDescriptor(),
                        ResolvableType.forClassWithGenerics(PageResult.class, Book.class), List.of());
        PageResult<Book> page = PageResult
                .ofCursor(List.of(new Book("b1", "Dune", 1965), new Book("b2", "Solaris", 1961)), "raw-cursor")
                .withTotal(42);

        // The page's own nextCursor is the operation's raw cursor: the caller passes the token that wraps it.
        ListPagedBooksResponse written = ListPagedBooksResponse
                .parseFrom(writer.writePage(page, "wrapped-token").toByteString());

        assertThat(writer.pages()).isTrue();
        assertThat(written)
                .isEqualTo(ListPagedBooksResponse
                        .newBuilder()
                        .addBooks(PagedBook.newBuilder().setId("b1").setTitle("Dune").setPublishedYear(1965))
                        .addBooks(PagedBook.newBuilder().setId("b2").setTitle("Solaris").setPublishedYear(1961))
                        .setNextPageToken("wrapped-token")
                        .setHasNext(true)
                        .setTotalSize(42)
                        .build());
        assertThat(written.toString()).doesNotContain("raw-cursor");
    }

    @Test
    void hasNextIsThePagesOwnWhateverTheToken() throws InvalidProtocolBufferException {
        RecordWriter writer = MessageMapping
                .writer(ListPagedBooksResponse.getDescriptor(),
                        ResolvableType.forClassWithGenerics(PageResult.class, Book.class), List.of());

        // An offset page past the cap: another page follows, but no token reaches it (REST: hasNext, no Link).
        ListPagedBooksResponse capped = ListPagedBooksResponse
                .parseFrom(writer
                        .writePage(PageResult.of(List.of(new Book("b1", "Dune", 1965)), true), null)
                        .toByteString());

        assertThat(capped.getHasNext()).isTrue();
        assertThat(capped.getNextPageToken()).isEmpty();
    }

    @Test
    void lastPageAndUnknownTotalLeaveTheirFieldsUnset() throws InvalidProtocolBufferException {
        RecordWriter writer = MessageMapping
                .writer(ListPagedBooksResponse.getDescriptor(),
                        ResolvableType.forClassWithGenerics(PageResult.class, Book.class), List.of());

        ListPagedBooksResponse last = ListPagedBooksResponse
                .parseFrom(writer.writePage(PageResult.ofCursor(List.of(), null), null).toByteString());

        assertThat(last.getBooksList()).isEmpty();
        assertThat(last.getNextPageToken()).isEmpty();
        assertThat(last.getHasNext()).isFalse();
        assertThat(last.hasTotalSize()).isFalse();
        assertThat(writer.writePage(null, null))
                .isEqualTo(DynamicMessage.getDefaultInstance(ListPagedBooksResponse.getDescriptor()));
    }

    @Test
    void responseWithoutTotalSizeDropsTheTotal() throws InvalidProtocolBufferException {
        RecordWriter writer = MessageMapping
                .writer(PageWithoutTotalResponse.getDescriptor(),
                        ResolvableType.forClassWithGenerics(PageResult.class, Book.class), List.of());

        PageWithoutTotalResponse written = PageWithoutTotalResponse
                .parseFrom(writer
                        .writePage(PageResult.ofCursor(List.of(new Book("b1", "Dune", 1965)), null).withTotal(1), "t")
                        .toByteString());

        assertThat(written.getBooksCount()).isOne();
        assertThat(written.getNextPageToken()).isEqualTo("t");
    }

    @Test
    void totalBeyondAnInt32TotalSizeIsUnknownAndAnInt64OneCarriesIt() throws InvalidProtocolBufferException {
        RecordWriter int32 = MessageMapping
                .writer(ListPagedBooksResponse.getDescriptor(),
                        ResolvableType.forClassWithGenerics(PageResult.class, Book.class), List.of());
        RecordWriter int64 = MessageMapping
                .writer(Int64TotalResponse.getDescriptor(),
                        ResolvableType.forClassWithGenerics(PageResult.class, Book.class), List.of());
        PageResult<Book> huge = PageResult.of(List.<Book>of(), false).withTotal(1L << 40);

        // The call succeeds, as over REST; an int32 field can't carry the total, so it is left unknown.
        assertThat(ListPagedBooksResponse.parseFrom(int32.writePage(huge, null).toByteString()).hasTotalSize())
                .isFalse();
        assertThat(Int64TotalResponse.parseFrom(int64.writePage(huge, null).toByteString()).getTotalSize())
                .isEqualTo(1L << 40);
    }

    @Test
    void pageIsNeverWrittenAsARecord() {
        RecordWriter pages = MessageMapping
                .writer(ListPagedBooksResponse.getDescriptor(),
                        ResolvableType.forClassWithGenerics(PageResult.class, Book.class), List.of());
        RecordWriter items = MessageMapping
                .writer(Item.getDescriptor(), ResolvableType.forClass(ItemRec.class), itemOverrides());

        assertThatIllegalStateException()
                .isThrownBy(() -> pages.write(PageResult.ofCursor(List.of(), "raw-cursor")))
                .withMessage("A page is written with writePage, which takes the wrapped cursor");
        assertThatIllegalStateException()
                .isThrownBy(() -> items.writePage(PageResult.of(List.of(), false), null))
                .withMessage("This writer doesn't write pages");
        assertThat(items.pages()).isFalse();
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

    /** {@code paging.proto}'s {@code PagedBook}. */
    record Book(String id, String title, int publishedYear) {
    }
}
