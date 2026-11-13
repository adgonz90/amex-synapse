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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.google.protobuf.Descriptors.OneofDescriptor;
import com.google.protobuf.DynamicMessage;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;

import io.americanexpress.synapse.api.grpc.fixture.proto.Choice;
import io.americanexpress.synapse.api.grpc.fixture.proto.CreateItemRequest;
import io.americanexpress.synapse.api.grpc.fixture.proto.Item;
import io.americanexpress.synapse.api.grpc.fixture.proto.ListTagsResponse;
import io.americanexpress.synapse.api.grpc.fixture.proto.NoteChoice;
import io.americanexpress.synapse.api.grpc.fixture.proto.PlainNumbers;
import io.americanexpress.synapse.api.grpc.fixture.proto.Scalars;
import io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.AuthorRec;
import io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.Genre;
import io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.ItemRec;
import io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.Shelf;

import static io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.dynamic;
import static io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.item;
import static io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.override;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class MessageMappingTest {

    private static final String ITEM = "synapse.fixture.v1.Item";

    private static final String UNMAPPABLE = "Change the component type, or map another field with .map(...).";

    @Test
    void nullableComponentNeedsPresence() {
        assertFails(() -> MessageMapping.reader(PlainNumbers.getDescriptor(), Year.class, List.of(), List.of()),
                "Year.year is @Nullable but synapse.fixture.v1.PlainNumbers.year has no presence",
                "Use a wrapper type or proto3 optional for the field, or make the component non-null.");
        assertFails(
                () -> MessageMapping
                        .writer(PlainNumbers.getDescriptor(), ResolvableType.forClass(Year.class), List.of()),
                "Year.year is @Nullable but synapse.fixture.v1.PlainNumbers.year has no presence",
                "Use a wrapper type or proto3 optional for the field, or make the component non-null.");
    }

    @Test
    void oneofMemberNeedsAnExplicitMap() {
        String problem = ITEM + ".sku is part of oneof lookup";
        String action = "Map it explicitly with .map(\"sku\", ...).";

        assertFails(() -> MessageMapping.reader(Item.getDescriptor(), ItemRec.class, List.of(), List.of()), problem,
                action);
        assertFails(
                () -> MessageMapping.writer(Item.getDescriptor(), ResolvableType.forClass(ItemRec.class), List.of()),
                problem, action);
        assertThatCode(() -> MessageMapping
                .reader(Item.getDescriptor(), ItemRec.class,
                        List.of(override("sku", ItemRec::sku)), List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void renamedFieldWithMap() {
        RecordReader reader = MessageMapping
                .reader(Item.getDescriptor(), Published.class,
                        List.of(override("created_at", Published::publishedOn)), List.of());

        assertThat(reader.read(dynamic(item()))).isEqualTo(new Published("Dune", MappingFixtures.CREATED));
        assertThat(reader.fieldPaths()).containsEntry("publishedOn", "created_at");
    }

    @Test
    void outputComponentWithoutFieldFails() {
        assertFails(() -> MessageMapping.writer(Item.getDescriptor(), ResolvableType.forClass(Extra.class), List.of()),
                "Extra.extra has no field in " + ITEM, "Add the field to the message, or map it with .map(...).");
        assertFails(() -> MessageMapping.writer(Item.getDescriptor(), ResolvableType.forClass(Loose.class), List.of()),
                "Loose.extra has no field in " + ITEM, "Add the field to the message, or map it with .map(...).");
    }

    @Test
    void nonNullInputWithoutSourceFails() {
        assertFails(() -> MessageMapping.reader(Item.getDescriptor(), Extra.class, List.of(), List.of()),
                "Extra.extra has no source",
                "Add the field to " + ITEM + ", map one with .map(...), or set it with .context(...).");
    }

    @Test
    void nullableInputWithoutSourceFails() {
        assertFails(() -> MessageMapping.reader(Item.getDescriptor(), Loose.class, List.of(), List.of()),
                "Loose.extra has no source",
                "Add the field to " + ITEM + ", map one with .map(...), or set it with .context(...).");
    }

    @Test
    void unmappableTypeFails() {
        assertFails(() -> MessageMapping.reader(Item.getDescriptor(), Threaded.class, List.of(), List.of()),
                "Threaded.author can't be mapped to " + ITEM + ".author (synapse.fixture.v1.Author)", UNMAPPABLE);
    }

    @Test
    void componentOfTheWrongShapeFails() {
        assertUnmappable(Boxed.class, "Boxed.name can't be mapped to " + ITEM + ".name (string)");
        assertUnmappable(Tagged.class, "Tagged.tags can't be mapped to " + ITEM + ".tags (repeated string)");
        assertUnmappable(Counted.class, "Counted.counts can't be mapped to " + ITEM + ".counts (map<string, int64>)");
        assertUnmappable(Narrow.class, "Narrow.version can't be mapped to " + ITEM + ".version (int64)");
        assertUnmappable(Opt.class, "Opt.name can't be mapped to " + ITEM + ".name (string)");
        assertUnmappable(When.class, "When.createdAt can't be mapped to " + ITEM
                + ".created_at (google.protobuf.Timestamp)");
        assertUnmappable(Rated.class, "Rated.rating can't be mapped to " + ITEM
                + ".rating (google.protobuf.Int32Value)");
        assertUnmappable(Raw.class, "Raw.tags can't be mapped to " + ITEM + ".tags (repeated string)");
        assertUnmappable(Flag.class, "Flag.version can't be mapped to " + ITEM + ".version (int64)");
        assertFails(() -> MessageMapping.reader(Scalars.getDescriptor(), Scalar.class, List.of(), List.of()),
                "Scalar.boolValue can't be mapped to synapse.fixture.v1.Scalars.bool_value (bool)", UNMAPPABLE);
    }

    @Test
    void extraResponseFieldsAreAllowed() {
        RecordWriter writer = MessageMapping
                .writer(Item.getDescriptor(), ResolvableType.forClass(IdOnly.class), List.of());

        assertThat(writer.write(new IdOnly("a")).getAllFields()).hasSize(1);
    }

    @Test
    void mapPathThatNamesNoFieldFails() {
        String action = "Use a field path of " + ITEM + ", dotted for nested messages.";

        assertFails(() -> MessageMapping
                .writer(Item.getDescriptor(), ResolvableType.forClass(IdOnly.class),
                        List.of(override("nope", IdOnly::id))),
                ".map(\"nope\", ...) names no field of " + ITEM, action);
        assertFails(() -> MessageMapping
                .writer(Item.getDescriptor(), ResolvableType.forClass(IdOnly.class),
                        List.of(override("tags.name", IdOnly::id))),
                ".map(\"tags.name\", ...) names no field of " + ITEM,
                action);
        assertFails(() -> MessageMapping
                .writer(Item.getDescriptor(), ResolvableType.forClass(IdOnly.class),
                        List.of(override("author.nope", IdOnly::id))),
                ".map(\"author.nope\", ...) names no field of " + ITEM,
                action);
    }

    @Test
    void listOutputNeedsASingleRepeatedField() {
        assertFails(() -> MessageMapping.writer(Item.getDescriptor(), list(String.class), List.of()),
                ITEM + " must have a single field, a repeated one, for the output List<String>",
                "Give the response message a single repeated field, or return a record from the operation.");
        assertFails(() -> MessageMapping.writer(PlainNumbers.getDescriptor(), list(Integer.class), List.of()),
                "synapse.fixture.v1.PlainNumbers must have a single field, a repeated one, for the output "
                        + "List<Integer>",
                "Give the response message a single repeated field, or return a record from the operation.");
        assertFails(() -> MessageMapping.writer(ListTagsResponse.getDescriptor(), list(AuthorRec.class), List.of()),
                "the output List<AuthorRec> can't be mapped to synapse.fixture.v1.ListTagsResponse.tags "
                        + "(repeated string)",
                UNMAPPABLE);
    }

    @Test
    void outputThatIsNotARecordFails() {
        assertFails(() -> MessageMapping.writer(Item.getDescriptor(), ResolvableType.forClass(String.class), List.of()),
                "the output String can't be mapped to " + ITEM,
                "Return a record, a List, an Optional or Void from the operation.");
        assertFails(() -> MessageMapping
                .writer(Item.getDescriptor(),
                        ResolvableType.forClassWithGenerics(Optional.class, String.class), List.of()),
                "the output String can't be mapped to " + ITEM,
                "Return a record, a List, an Optional or Void from the operation.");
    }

    @Test
    void inputThatIsNotARecordFails() {
        assertFails(() -> MessageMapping.reader(Item.getDescriptor(), String.class, List.of(), List.of()),
                "the input String can't be mapped to " + ITEM,
                "Declare the operation's input as a record, or as Void if it takes none.");
    }

    @Test
    void enumConstantWithoutValueFails() {
        assertFails(() -> MessageMapping.writer(Item.getDescriptor(), ResolvableType.forClass(Mood.class), List.of()),
                "Mood.genre can't be mapped to " + ITEM + ".genre (synapse.fixture.v1.Genre): no value for "
                        + "WideGenre.POETRY",
                "Add a value for every constant of WideGenre, named like the constant or GENRE_<constant>, or "
                        + "change the component type.");
    }

    @Test
    void nullableEnumNeedsPresenceOrAnUnspecifiedZero() {
        assertFails(() -> MessageMapping.reader(Scalars.getDescriptor(), ShelfRec.class, List.of(), List.of()),
                "ShelfRec.shelf is @Nullable but synapse.fixture.v1.Scalars.shelf has no presence",
                "Use a wrapper type or proto3 optional for the field, or make the component non-null.");
        RecordReader reader = MessageMapping.reader(Item.getDescriptor(), NullableGenre.class, List.of(), List.of());

        assertThat(reader.read(dynamic(Item.getDefaultInstance()))).isEqualTo(new NullableGenre(null));
        assertThat(reader.read(dynamic(item()))).isEqualTo(new NullableGenre(Genre.FICTION));
    }

    @Test
    void nullableComponentsShareAOneofAndTheOneSetIsWritten() {
        RecordWriter writer = MessageMapping
                .writer(Item.getDescriptor(), ResolvableType.forClass(Both.class), bothOverrides());
        OneofDescriptor lookup = Item.getDescriptor().getOneofs().getFirst();

        DynamicMessage bySku = writer.write(new Both("s-1", null));
        DynamicMessage byBarcode = writer.write(new Both(null, "b-1"));

        assertThat(bySku.getOneofFieldDescriptor(lookup).getName()).isEqualTo("sku");
        assertThat(bySku.getField(lookup.getField(0))).isEqualTo("s-1");
        assertThat(byBarcode.getOneofFieldDescriptor(lookup).getName()).isEqualTo("barcode");
        assertThat(byBarcode.getField(lookup.getField(1))).isEqualTo("b-1");
        assertThatCode(() -> MessageMapping.reader(Item.getDescriptor(), Both.class, bothOverrides(), List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void noComponentSetLeavesTheOneofUnset() {
        RecordWriter writer = MessageMapping
                .writer(Item.getDescriptor(), ResolvableType.forClass(Both.class), bothOverrides());

        DynamicMessage written = writer.write(new Both(null, null));

        assertThat(written.hasOneof(Item.getDescriptor().getOneofs().getFirst())).isFalse();
    }

    @Test
    void twoComponentsSetInOneOneofFailTheWrite() {
        RecordWriter members = MessageMapping
                .writer(Item.getDescriptor(), ResolvableType.forClass(Both.class), bothOverrides());
        RecordWriter messageMembers = MessageMapping
                .writer(Choice.getDescriptor(), ResolvableType.forClass(BothPicks.class),
                        List.of(override("first", BothPicks::first), override("second", BothPicks::second)));
        AuthorRec ada = new AuthorRec("Ada", MappingFixtures.CountryCode.valueOf("GB"));

        // Writing both would silently keep only the last member, so the call fails (INTERNAL at the edge).
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> members.write(new Both("s-1", "b-1")))
                .withMessage("Both.sku and Both.barcode both have a value, but they write members of oneof " + ITEM
                        + ".lookup, which holds one");
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> messageMembers.write(new BothPicks(ada, ada)))
                .withMessage("BothPicks.first and BothPicks.second both have a value, but they write members of "
                        + "oneof synapse.fixture.v1.Choice.pick, which holds one");
        DynamicMessage first = messageMembers.write(new BothPicks(ada, null));
        assertThat(first.getOneofFieldDescriptor(Choice.getDescriptor().getOneofs().getFirst()).getName())
                .isEqualTo("first");
    }

    @Test
    void componentsInsideOneMemberMaySetItTogether() {
        RecordWriter writer = MessageMapping
                .writer(NoteChoice.getDescriptor(), ResolvableType.forClass(Notes.class),
                        List
                                .of(override("first.title", Notes::firstTitle),
                                        override("first.text", Notes::firstText),
                                        override("second.title", Notes::secondTitle)));

        DynamicMessage first = writer.write(new Notes("t", "x", null));

        assertThat(first.getOneofFieldDescriptor(NoteChoice.getDescriptor().getOneofs().getFirst()).getName())
                .isEqualTo("first");
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> writer.write(new Notes("t", null, "s")))
                .withMessage("Notes.firstTitle and Notes.secondTitle both have a value, but they write members of "
                        + "oneof synapse.fixture.v1.NoteChoice.pick, which holds one");
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> writer.write(new Notes("t", "x", "s")))
                .withMessage("Notes.firstTitle, Notes.firstText and Notes.secondTitle all have a value, but they write "
                        + "members of oneof synapse.fixture.v1.NoteChoice.pick, which holds one");
    }

    @Test
    void sharedOneofInANestedRecordIsCheckedThere() {
        // CreateItemRequest.item is an Item: the oneof group belongs to the nested record's mapping.
        RecordWriter writer = MessageMapping
                .writer(CreateItemRequest.getDescriptor(), ResolvableType.forClass(Wrapped.class), bothOverrides());

        DynamicMessage one = writer.write(new Wrapped(new Both(null, "b-1")));

        DynamicMessage item = (DynamicMessage) one.getField(CreateItemRequest.getDescriptor().findFieldByName("item"));
        assertThat(item.getOneofFieldDescriptor(Item.getDescriptor().getOneofs().getFirst()).getName())
                .isEqualTo("barcode");
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> writer.write(new Wrapped(new Both("s-1", "b-1"))))
                .withMessage("Both.sku and Both.barcode both have a value, but they write members of oneof " + ITEM
                        + ".lookup, which holds one");
    }

    private static List<io.americanexpress.synapse.api.grpc.internal.binding.FieldOverride> bothOverrides() {
        return List.of(override("sku", Both::sku), override("barcode", Both::barcode));
    }

    @Test
    void enumConstantMatchingTheUnspecifiedValueFails() {
        assertFails(
                () -> MessageMapping
                        .writer(Item.getDescriptor(), ResolvableType.forClass(Unspecified.class), List.of()),
                "Unspecified.genre can't be mapped to " + ITEM + ".genre (synapse.fixture.v1.Genre): "
                        + "WithUnspecified.UNSPECIFIED matches the unspecified value GENRE_UNSPECIFIED",
                "Remove WithUnspecified.UNSPECIFIED: the unspecified value maps to null.");
    }

    @Test
    void nonNullComponentInASharedOneofFails() {
        // A component that always has a value would always clear the other members, so the binding can't be right.
        String action = "Declare each component that writes a member of the oneof @Nullable, so that at most one has "
                + "a value, or map only one component to the oneof.";

        assertFails(() -> MessageMapping
                .writer(Choice.getDescriptor(), ResolvableType.forClass(Picks.class),
                        List
                                .of(override("first.name", Picks::firstName), override("second.country_code",
                                        Picks::secondCountry))),
                "Picks.firstName and Picks.secondCountry both write oneof synapse.fixture.v1.Choice.pick, and "
                        + "Picks.firstName is not @Nullable",
                action);
        assertFails(() -> MessageMapping
                .writer(Choice.getDescriptor(), ResolvableType.forClass(Picked.class),
                        List.of(override("first", Picked::first), override("second.name", Picked::secondName))),
                "Picked.first and Picked.secondName both write oneof synapse.fixture.v1.Choice.pick, and "
                        + "Picked.secondName is not @Nullable",
                action);
    }

    @Test
    void voidInputTakesOnlyAMessageWithoutFields() {
        assertFails(() -> MessageMapping.reader(Item.getDescriptor(), Void.class, List.of(), List.of()),
                "the input Void can't be mapped to " + ITEM + ", which has fields",
                "Use google.protobuf.Empty as the request, or declare a record input for its fields.");
    }

    @Test
    void overlappingOutputFieldsFail() {
        List<io.americanexpress.synapse.api.grpc.internal.binding.FieldOverride> overrides = List
                .of(override("author.name", Overlap::authorName));

        assertFails(
                () -> MessageMapping.writer(Item.getDescriptor(), ResolvableType.forClass(Overlap.class), overrides),
                "Overlap.author and Overlap.authorName both write " + ITEM + ".author",
                "Map each component to its own field.");
        assertFails(() -> MessageMapping
                .writer(Item.getDescriptor(), ResolvableType.forClass(Twice.class),
                        List.of(override("name", Twice::title))),
                "Twice.name and Twice.title both write " + ITEM + ".name",
                "Map each component to its own field.");
        assertThatCode(() -> MessageMapping.reader(Item.getDescriptor(), Overlap.class, overrides, List.of()))
                .doesNotThrowAnyException();
    }

    private static ResolvableType list(Class<?> element) {
        return ResolvableType.forClassWithGenerics(List.class, element);
    }

    private static void assertUnmappable(Class<?> recordType, String problem) {
        assertFails(() -> MessageMapping.reader(Item.getDescriptor(), recordType, List.of(), List.of()), problem,
                UNMAPPABLE);
    }

    private static void assertFails(ThrowingCallable mapping, String problem, String action) {
        assertThatExceptionOfType(MappingException.class)
                .isThrownBy(mapping)
                .satisfies(e -> {
                    assertThat(e.problem()).isEqualTo(problem);
                    assertThat(e.action()).isEqualTo(action);
                    assertThat(e).hasMessage(problem);
                });
    }

    /** A Java enum with a constant the proto enum lacks. */
    enum WideGenre {
        FICTION, POETRY
    }

    record Year(@Nullable Integer year) {
    }

    record Published(String name, @Nullable Instant publishedOn) {
    }

    record Extra(String name, String extra) {
    }

    record Loose(String name, @Nullable String extra) {
    }

    record Threaded(String name, Thread author) {
    }

    record IdOnly(String id) {
    }

    record Name(String value) {
    }

    record Boxed(Name name) {
    }

    record Tagged(String tags) {
    }

    record Counted(List<Long> counts) {
    }

    record Narrow(int version) {
    }

    record Opt(Optional<String> name) {
    }

    record When(String createdAt) {
    }

    record Rated(@Nullable String rating) {
    }

    @SuppressWarnings("rawtypes")
    record Raw(List tags) {
    }

    record Flag(boolean version) {
    }

    record Scalar(String boolValue) {
    }

    record Mood(WideGenre genre) {
    }

    record ShelfRec(@Nullable Shelf shelf) {
    }

    record NullableGenre(@Nullable Genre genre) {
    }

    record Overlap(AuthorRec author, String authorName) {
    }

    record Twice(String name, String title) {
    }

    record Both(@Nullable String sku, @Nullable String barcode) {
    }

    record Picks(String firstName, String secondCountry) {
    }

    record Picked(@Nullable AuthorRec first, String secondName) {
    }

    record BothPicks(@Nullable AuthorRec first, @Nullable AuthorRec second) {
    }

    record Wrapped(Both item) {
    }

    record Notes(@Nullable String firstTitle, @Nullable String firstText, @Nullable String secondTitle) {
    }

    /** A Java enum with a constant that matches the proto enum's unspecified zero value. */
    enum WithUnspecified {
        FICTION, UNSPECIFIED
    }

    record Unspecified(WithUnspecified genre) {
    }
}
