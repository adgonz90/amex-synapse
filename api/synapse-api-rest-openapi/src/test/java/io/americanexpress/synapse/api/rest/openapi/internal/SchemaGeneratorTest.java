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

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Month;
import java.time.Year;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.exc.InvalidDefinitionException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Article;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Author;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Bag;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Book;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Built;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Chain1;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Clash;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Constrained;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Converted;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Crate;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Customer;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Deep;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.DelFactory;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Entry;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Envelope;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Gaps;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Grow;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Journal;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Ledger;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.LedgerInput;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Level;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Node;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Odd;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Pair;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Parsed;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Point;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Query;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Reconverted;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Shelf;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Simple;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Sort;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Span;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Sparse;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Stamp;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Token;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Unknown;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Wire;
import io.americanexpress.synapse.api.rest.openapi.internal.SchemaFixtures.Wrapped;
import io.americanexpress.synapse.core.internal.binding.ResolvedComponent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class SchemaGeneratorTest {

    private static final String REF = "#/components/schemas/";

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static final SchemaGenerator.Limits DEFAULTS = SchemaGenerator.Limits.DEFAULT;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final SchemaGenerator generator = new SchemaGenerator(MAPPER, VALIDATOR);

    @Test
    void deepGenericsStayBounded() {
        // Every step nests the type one level deeper, so the types grow in size, not in number: without the size
        // limits the walk exhausts the heap (the review reproduced it at 1 and 4 GB).
        SchemaGenerator deep = new SchemaGenerator(MAPPER, VALIDATOR);

        Schema<?> schema = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> deep.output(ResolvableType.forClassWithGenerics(Deep.class, String.class)));

        assertThat(schema.get$ref()).isEqualTo(REF + "DeepOfString");
        assertThat(deep.undescribed())
                .hasSize(1)
                .allSatisfy(type -> assertThat(type).startsWith(Deep.class.getCanonicalName() + "<"));
        assertThat(deep.components())
                .hasSizeLessThan(DEFAULTS.nameLength())
                .allSatisfy((name, component) -> assertThat(name).hasSizeLessThanOrEqualTo(DEFAULTS.nameLength()));
        assertThat(deep.schemaCount()).isLessThanOrEqualTo(DEFAULTS.schemas());
    }

    @Test
    void aNameOverTheLimitIsEmptyAndReported() {
        SchemaGenerator generator = limited(DEFAULTS.types(), 20, DEFAULTS.schemasPerType(), DEFAULTS.schemas());

        Schema<?> envelope = generator
                .output(ResolvableType
                        .forClassWithGenerics(Envelope.class,
                                ResolvableType.forClassWithGenerics(Pair.class, Book.class, Author.class)));

        assertThat(envelope.get$ref()).isNull();
        assertThat(envelope.getTypes()).isNull();
        assertThat(generator.components()).isEmpty();
        assertThat(generator.undescribed())
                .containsExactly(Envelope.class.getCanonicalName() + "<"
                        + Pair.class.getCanonicalName() + "<" + Book.class.getCanonicalName() + ", "
                        + Author.class.getCanonicalName() + ">>");
    }

    @Test
    void aTypeOverTheSchemaLimitIsEmptyAndReported() {
        SchemaGenerator generator = limited(DEFAULTS.types(), DEFAULTS.nameLength(), 4, DEFAULTS.schemas());

        Schema<?> shelf = generator.output(ResolvableType.forClass(Shelf.class));

        // Shelf needs 5 schemas (its object, 3 refs and an array) and Book 4 (its object and 3 properties). The walk of
        // Shelf stops, and the Book it had reached is not documented either.
        assertThat(shelf.get$ref()).isEqualTo(REF + "Shelf");
        assertThat(generator.components()).containsOnlyKeys("Shelf");
        assertThat(component(generator, "Shelf").getTypes()).isNull();
        assertThat(component(generator, "Shelf").getProperties()).isNull();
        assertThat(generator.undescribed()).containsExactly(Shelf.class.getCanonicalName());
        assertThat(generator.output(ResolvableType.forClass(Book.class)).get$ref()).isEqualTo(REF + "Book");
        assertThat(component(generator, "Book").getProperties()).containsOnlyKeys("id", "title", "year");
    }

    @Test
    void theTotalSchemaLimitStopsTheWalk() {
        SchemaGenerator generator = limited(DEFAULTS.types(), DEFAULTS.nameLength(), 20, 40);

        generator.output(ResolvableType.forClassWithGenerics(Grow.class, String.class));

        assertThat(generator.schemaCount()).isLessThanOrEqualTo(40 + 20);
        assertThat(generator.undescribed())
                .hasSize(1)
                .allSatisfy(type -> assertThat(type).startsWith(Grow.class.getCanonicalName() + "<"));
        assertThat(generator.output(ResolvableType.forClass(Author.class)).get$ref()).isNull();
    }

    @Test
    void typesAreDescribedAsJacksonWritesThem() {
        generator.output(ResolvableType.forClass(Wire.class));

        // What Jackson 3 writes, with the defaults Boot's mapper starts from.
        JsonNode written = MAPPER
                .valueToTree(new Wire(Year.of(2026), Month.MARCH, DayOfWeek.MONDAY, 'x', 'y',
                        OptionalInt.of(3), OptionalLong.empty(), OptionalDouble.of(1.5),
                        Map.of(UUID.fromString("00000000-0000-0000-0000-000000000001"), "a"), Map.of(7, 8L),
                        Level.HIGH));
        assertThat(written.get("year").isInt()).isTrue();
        assertThat(written.get("month").intValue()).isEqualTo(3);
        assertThat(written.get("day").asString()).isEqualTo("MONDAY");
        assertThat(written.get("letter").asString()).isEqualTo("x");
        assertThat(written.get("count").intValue()).isEqualTo(3);
        assertThat(written.get("total").isNull()).isTrue();
        assertThat(written.get("ratio").doubleValue()).isEqualTo(1.5);
        assertThat(written.get("byId").get("00000000-0000-0000-0000-000000000001").asString()).isEqualTo("a");
        assertThat(written.get("byNumber").get("7").longValue()).isEqualTo(8L);
        assertThat(written.get("level").intValue()).isEqualTo(1);

        Schema<?> wire = component("Wire");
        assertThat(describe(property(wire, "year"))).isEqualTo("integer");
        assertThat(describe(property(wire, "month"))).isEqualTo("$Month");
        assertThat(component("Month").getTypes()).containsExactly("integer");
        List<Object> months = List.copyOf(component("Month").getEnum());
        assertThat(months).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
        assertThat(describe(property(wire, "day"))).isEqualTo("$DayOfWeek");
        List<Object> days = List.copyOf(component("DayOfWeek").getEnum());
        assertThat(days)
                .containsExactly("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY",
                        "SUNDAY");
        for (String letter : List.of("letter", "boxedLetter")) {
            assertThat(describe(property(wire, letter))).isEqualTo("string");
            assertThat(property(wire, letter).getMinLength()).isEqualTo(1);
            assertThat(property(wire, letter).getMaxLength()).isEqualTo(1);
        }
        assertThat(describe(property(wire, "count"))).isEqualTo("integer,null/int32");
        assertThat(describe(property(wire, "total"))).isEqualTo("integer,null/int64");
        assertThat(describe(property(wire, "ratio"))).isEqualTo("number,null/double");
        assertThat(describe(property(wire, "byId"))).isEqualTo("object{string}");
        assertThat(describe(property(wire, "byNumber"))).isEqualTo("object{integer/int64}");
        assertThat(describe(property(wire, "level"))).isEqualTo("$Level");
        assertThat(wire.getRequired()).doesNotContain("count", "total", "ratio").contains("year", "letter", "byId");
        assertThat(generator.undescribed()).isEmpty();
    }

    @Test
    void recordsWrittenOrReadByTheirOwnCodeAreEmptyAndReported() {
        JsonNode token = MAPPER.valueToTree(new Token("t"));
        JsonNode wrapped = MAPPER.valueToTree(new Wrapped("w"));
        assertThat(token.asString()).isEqualTo("t");
        assertThat(wrapped.asString()).isEqualTo("w");
        assertThat(MAPPER.readValue("\"p\"", Parsed.class)).isEqualTo(new Parsed("p"));

        Schema<?> tokenOutput = generator.output(ResolvableType.forClass(Token.class));
        Schema<?> wrappedOutput = generator.output(ResolvableType.forClass(Wrapped.class));
        Schema<?> wrappedInput = generator.input(ResolvableType.forClass(Wrapped.class));
        Schema<?> parsedInput = generator.input(ResolvableType.forClass(Parsed.class));
        Schema<?> parsedOutput = generator.output(ResolvableType.forClass(Parsed.class));

        assertThat(component(name(tokenOutput)).getTypes()).isNull();
        assertThat(component(name(wrappedOutput)).getTypes()).isNull();
        assertThat(component(name(wrappedInput)).getProperties()).containsOnlyKeys("inner");
        assertThat(component(name(parsedInput)).getTypes()).isNull();
        assertThat(component(name(parsedOutput)).getProperties()).containsOnlyKeys("text");
        assertThat(generator.undescribed())
                .containsExactlyInAnyOrder(Token.class.getCanonicalName(),
                        Wrapped.class.getCanonicalName(), Parsed.class.getCanonicalName());
    }

    @Test
    void aRecordJacksonDoesNotReadPropertyByPropertyIsAnEmptyInput() {
        // Jackson reads a @JsonValue record from its value alone, never from an object.
        assertThat(MAPPER.readValue("\"t\"", Token.class)).isEqualTo(new Token("t"));
        assertThatExceptionOfType(MismatchedInputException.class)
                .isThrownBy(() -> MAPPER.readValue("{\"value\":\"t\"}", Token.class));

        Schema<?> input = generator.input(ResolvableType.forClass(Token.class));

        assertThat(component(name(input)).getTypes()).isNull();
        assertThat(component(name(input)).getProperties()).isNull();
        assertThat(generator.undescribed()).containsExactly(Token.class.getCanonicalName());
    }

    @Test
    void aRecordJacksonReadsByPropertyWithoutReadingAnyIsAnObject() {
        // Jackson reads Stamp from an object and never sets its read-only component.
        assertThat(MAPPER.readValue("{\"at\":\"2026-01-01T00:00:00Z\"}", Stamp.class)).isEqualTo(new Stamp(null));

        Schema<?> input = generator.input(ResolvableType.forClass(Stamp.class));
        Schema<?> token = generator.input(ResolvableType.forClass(Token.class));

        assertThat(component(name(input)).getTypes()).containsExactly("object");
        assertThat(component(name(input)).getProperties()).isNull();
        assertThat(component(name(token)).getTypes()).isNull();
        assertThat(generator.undescribed()).containsExactly(Token.class.getCanonicalName());
    }

    @Test
    void recordsReadThroughADelegateOrABuilderAreEmptyInputsAndReported() {
        // Jackson reads both from an object, but not by their components.
        assertThat(MAPPER.readValue("{\"x\":\"1\",\"y\":\"2\"}", DelFactory.class)).isEqualTo(new DelFactory("1", "2"));
        assertThat(MAPPER.readValue("{\"a\":\"1\"}", Built.class)).isEqualTo(new Built("1"));

        Schema<?> delegated = generator.input(ResolvableType.forClass(DelFactory.class));
        Schema<?> built = generator.input(ResolvableType.forClass(Built.class));

        for (Schema<?> input : List.of(delegated, built)) {
            assertThat(component(name(input)).getTypes()).isNull();
            assertThat(component(name(input)).getProperties()).isNull();
        }
        assertThat(generator.undescribed())
                .containsExactlyInAnyOrder(DelFactory.class.getCanonicalName(), Built.class.getCanonicalName());
        // Their output is written property by property.
        assertThat(component(name(generator.output(ResolvableType.forClass(DelFactory.class)))).getProperties())
                .containsOnlyKeys("a", "b");
    }

    @Test
    void aJacksonFailureKeepsItsCause() {
        SchemaGenerator inputs = new SchemaGenerator(MAPPER, VALIDATOR);

        inputs.input(ResolvableType.forClassWithGenerics(Grow.class, String.class));

        String type = Grow.class.getCanonicalName() + "<java.lang.String>";
        assertThat(inputs.reasons()).containsOnlyKeys(type);
        SchemaGenerator.Reason reason = Objects.requireNonNull(inputs.reasons().get(type));
        assertThat(reason.text()).isEqualTo("Jackson failed on it: java.lang.StackOverflowError");
        assertThat(reason.cause()).isInstanceOf(StackOverflowError.class);
    }

    @Test
    void aJacksonFailureNamesTheExceptionAndItsMessage() {
        // Jackson refuses the type when it writes it, too.
        assertThatExceptionOfType(InvalidDefinitionException.class)
                .isThrownBy(() -> MAPPER.writeValueAsString(new Clash("a", "b")))
                .withMessageContaining("Conflicting getter definitions for property \"x\"");

        generator.output(ResolvableType.forClass(Clash.class));

        SchemaGenerator.Reason reason = Objects.requireNonNull(generator.reasons().get(Clash.class.getCanonicalName()));
        assertThat(reason.cause()).isInstanceOf(IllegalArgumentException.class);
        assertThat(reason.text())
                .isEqualTo("Jackson failed on it: java.lang.IllegalArgumentException: "
                        + Objects.requireNonNull(reason.cause()).getMessage())
                .contains("Conflicting getter definitions for property \"x\"");
        assertThat(generator.undescribed()).containsExactly(Clash.class.getCanonicalName());
    }

    @Test
    void limitsGiveTheirReason() {
        SchemaGenerator budgeted = limited(3, DEFAULTS.nameLength(), DEFAULTS.schemasPerType(), DEFAULTS.schemas());
        budgeted.output(ResolvableType.forClass(Chain1.class));
        SchemaGenerator named = limited(DEFAULTS.types(), 20, DEFAULTS.schemasPerType(), DEFAULTS.schemas());
        named
                .output(ResolvableType
                        .forClassWithGenerics(Envelope.class,
                                ResolvableType.forClassWithGenerics(Pair.class, Book.class, Author.class)));
        SchemaGenerator sized = limited(DEFAULTS.types(), DEFAULTS.nameLength(), 4, DEFAULTS.schemas());
        sized.output(ResolvableType.forClass(Shelf.class));

        assertThat(budgeted.reasons().get(SchemaFixtures.class.getCanonicalName() + ".Chain4"))
                .extracting(SchemaGenerator.Reason::text)
                .isEqualTo("the schema budget was reached (3 types, 200000 schemas), so every type after it is "
                        + "documented as {} too");
        assertThat(named.reasons().values())
                .singleElement()
                .extracting(SchemaGenerator.Reason::text)
                .isEqualTo("its schema name would be longer than 20 characters");
        assertThat(sized.reasons().get(Shelf.class.getCanonicalName()))
                .extracting(SchemaGenerator.Reason::text)
                .isEqualTo("its schema needs more than 4 schemas");
        // A type the generator has no schema for needs no reason.
        generator.output(ResolvableType.forClass(Unknown.class));
        assertThat(generator.undescribed()).contains("java.lang.Object", "java.lang.Thread");
        assertThat(generator.reasons()).isEmpty();
    }

    @Test
    void aJacksonLookupThatFailsMakesThatTypeEmptyAndReported() {
        // Jackson's own deserializer lookup recurses without end on these growing generics.
        for (Class<?> growing : List.of(Deep.class, Grow.class)) {
            SchemaGenerator inputs = new SchemaGenerator(MAPPER, VALIDATOR);

            Schema<?> input = assertTimeoutPreemptively(Duration.ofSeconds(5),
                    () -> inputs.input(ResolvableType.forClassWithGenerics(growing, String.class)));

            assertThat(component(inputs, name(input)).getTypes()).as(growing.getSimpleName()).isNull();
            assertThat(inputs.undescribed())
                    .as(growing.getSimpleName())
                    .containsExactly(growing.getCanonicalName() + "<java.lang.String>");
            // Only that type: the generator goes on describing others.
            assertThat(component(inputs, name(inputs.input(ResolvableType.forClass(Author.class)))).getProperties())
                    .containsOnlyKeys("name");
        }
    }

    @Test
    void recordsWithConvertersOrAnArrayShapeAreEmptyAndReported() {
        assertThat(MAPPER.valueToTree(new Converted("c")).asString()).isEqualTo("c");
        assertThat(MAPPER.readValue("\"r\"", Reconverted.class)).isEqualTo(new Reconverted("r"));
        assertThat(MAPPER.writeValueAsString(new Point(1, 2))).isEqualTo("[1,2]");
        assertThat(MAPPER.readValue("[1,2]", Point.class)).isEqualTo(new Point(1, 2));

        Schema<?> convertedOutput = generator.output(ResolvableType.forClass(Converted.class));
        Schema<?> convertedInput = generator.input(ResolvableType.forClass(Converted.class));
        Schema<?> reconvertedInput = generator.input(ResolvableType.forClass(Reconverted.class));
        Schema<?> reconvertedOutput = generator.output(ResolvableType.forClass(Reconverted.class));
        Schema<?> pointOutput = generator.output(ResolvableType.forClass(Point.class));
        Schema<?> pointInput = generator.input(ResolvableType.forClass(Point.class));

        assertThat(component(name(convertedOutput)).getTypes()).isNull();
        assertThat(component(name(convertedInput)).getProperties()).containsOnlyKeys("text");
        assertThat(component(name(reconvertedInput)).getTypes()).isNull();
        assertThat(component(name(reconvertedOutput)).getProperties()).containsOnlyKeys("text");
        assertThat(component(name(pointOutput)).getTypes()).isNull();
        assertThat(component(name(pointInput)).getTypes()).isNull();
        assertThat(generator.undescribed())
                .containsExactlyInAnyOrder(Converted.class.getCanonicalName(),
                        Reconverted.class.getCanonicalName(), Point.class.getCanonicalName());
    }

    @Test
    void aConfiguredArrayShapeWinsOverTheAnnotation() {
        JsonMapper arrays = JsonMapper
                .builder()
                .withConfigOverride(Span.class,
                        override -> override.setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.ARRAY)))
                .build();
        // Jackson lets the per-type config override win over the class annotation.
        assertThat(arrays.writeValueAsString(new Span(1, 2))).isEqualTo("[1,2]");
        assertThat(arrays.readValue("[1,2]", Span.class)).isEqualTo(new Span(1, 2));
        assertThat(MAPPER.writeValueAsString(new Span(1, 2))).isEqualTo("{\"left\":1,\"right\":2}");
        SchemaGenerator configured = new SchemaGenerator(arrays, VALIDATOR);

        Schema<?> output = configured.output(ResolvableType.forClass(Span.class));
        Schema<?> input = configured.input(ResolvableType.forClass(Span.class));

        assertThat(component(configured, name(output)).getTypes()).isNull();
        assertThat(component(configured, name(input)).getTypes()).isNull();
        assertThat(configured.undescribed()).containsExactly(Span.class.getCanonicalName());
        // Without the override, the annotation's object shape is documented.
        assertThat(component(name(generator.output(ResolvableType.forClass(Span.class)))).getProperties())
                .containsOnlyKeys("left", "right");
    }

    @Test
    void propertiesTheMapperMayLeaveOutAreNotRequiredInOutputs() {
        Schema<?> output = generator.output(ResolvableType.forClass(Sparse.class));
        Schema<?> input = generator.input(ResolvableType.forClass(Sparse.class));

        assertThat(MAPPER.writeValueAsString(new Sparse("", "t"))).isEqualTo("{\"title\":\"t\"}");
        assertThat(output.get$ref()).isEqualTo(REF + "Sparse");
        assertThat(input.get$ref()).isEqualTo(REF + "SparseInput");
        assertThat(component("Sparse").getRequired()).containsExactly("title");
        assertThat(component("SparseInput").getRequired()).containsExactly("note", "title");

        SchemaGenerator nonEmpty = new SchemaGenerator(JsonMapper
                .builder()
                .changeDefaultPropertyInclusion(
                        inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_EMPTY))
                .build(), VALIDATOR);
        nonEmpty.output(ResolvableType.forClass(Author.class));
        assertThat(component(nonEmpty, "Author").getRequired()).isNull();
    }

    @Test
    void simpleTypesHaveTheirTypesAndFormats() {
        generator.output(ResolvableType.forClass(Simple.class));

        Schema<?> simple = component("Simple");
        assertThat(simple.getSpecVersion()).isEqualTo(SpecVersion.V31);
        assertThat(describedProperties(simple))
                .containsExactly(entry("text", "string"),
                        entry("id", "string/uuid"), entry("link", "string/uri"), entry("flag", "boolean"),
                        entry("boxedFlag", "boolean"), entry("tiny", "integer/int32"), entry("small", "integer/int32"),
                        entry("count", "integer/int32"), entry("boxedCount", "integer/int32"),
                        entry("big", "integer/int64"),
                        entry("huge", "integer"), entry("ratio", "number/float"), entry("precise", "number/double"),
                        entry("amount", "number"), entry("instant", "string/date-time"),
                        entry("offset", "string/date-time"),
                        entry("zoned", "string/date-time"), entry("day", "string/date"),
                        entry("span", "string/duration"),
                        entry("time", "string"), entry("bytes", "string/byte"), entry("words", "array[string]"),
                        entry("list", "array[string]"), entry("numbers", "array[integer/int32]"),
                        entry("tags", "array[string] unique"), entry("counts", "object{integer/int64}"),
                        entry("maybe", "string,null"));
        assertThat(simple.getRequired()).hasSize(26).doesNotContain("maybe");
        assertThat(generator.undescribed()).isEmpty();
    }

    @Test
    void recordsBecomeComponentsWithRequired() {
        Schema<?> book = generator.output(ResolvableType.forClass(Book.class));

        assertThat(book.get$ref()).isEqualTo(REF + "Book");
        assertThat(generator.components()).containsOnlyKeys("Book");
        Schema<?> component = component("Book");
        assertThat(component.getTypes()).containsExactly("object");
        assertThat(describedProperties(component))
                .containsExactly(entry("id", "string/uuid"),
                        entry("title", "string"), entry("year", "integer,null/int32"));
        assertThat(property(component, "year").getTypes()).containsExactly("integer", "null");
        assertThat(component.getRequired()).containsExactly("id", "title");
    }

    @Test
    @SuppressWarnings("unchecked") // swagger types getAnyOf() as a List of the raw Schema
    void nullableReferenceUsesAnyOf() {
        generator.output(ResolvableType.forClass(Customer.class));

        Schema<?> customer = component("Customer");
        for (String name : List.of("address", "billing")) {
            Schema<?> address = property(customer, name);
            assertThat(address.getTypes()).isNull();
            assertThat(address.getAnyOf()).hasSize(2);
            assertThat(address.getAnyOf().get(0).get$ref()).isEqualTo(REF + "Address");
            assertThat(address.getAnyOf().get(1).getTypes()).containsExactly("null");
        }
        assertThat(customer.getRequired()).containsExactly("name");
        assertThat(generator.components()).containsOnlyKeys("Customer", "Address");
    }

    @Test
    @SuppressWarnings("unchecked") // swagger types getAnyOf() as a List of the raw Schema
    void nullableItemsAdmitNull() {
        generator.output(ResolvableType.forClass(Gaps.class));

        // A @Nullable item type: the mapper writes, and the binder passes through, a null item, so the items admit
        // null, as a nullable component does; the other items stay non-null.
        Schema<?> gaps = component("Gaps");
        assertThat(property(gaps, "gaps").getItems().getTypes()).containsExactly("string", "null");
        Schema<?> note = (Schema<?>) property(gaps, "notes").getAdditionalProperties();
        assertThat(note.getAnyOf()).hasSize(2);
        assertThat(note.getAnyOf().get(0).get$ref()).isEqualTo(REF + "Address");
        assertThat(note.getAnyOf().get(1).getTypes()).containsExactly("null");
        assertThat(property(gaps, "holes").getItems().getTypes()).containsExactly("integer", "null");
        Schema<?> rows = property(gaps, "rows");
        assertThat(rows.getItems().getTypes()).containsExactly("array");
        assertThat(rows.getItems().getItems().getTypes()).containsExactly("string", "null");
        assertThat(property(gaps, "plain").getItems().getTypes()).containsExactly("string");
        assertThat(((Schema<?>) property(gaps, "addresses").getAdditionalProperties()).get$ref())
                .isEqualTo(REF + "Address");
    }

    @Test
    void enumsListTheirWireValues() {
        Schema<?> sort = generator.output(ResolvableType.forClass(Sort.class));

        assertThat(sort.get$ref()).isEqualTo(REF + "Sort");
        Schema<?> component = component("Sort");
        assertThat(component.getTypes()).containsExactly("string");
        List<Object> values = List.copyOf(component.getEnum());
        assertThat(values).containsExactly("by-title", "YEAR");
        assertThat(generator.input(ResolvableType.forClass(Sort.class)).get$ref()).isEqualTo(REF + "Sort");
        assertThat(generator.components()).containsOnlyKeys("Sort");
    }

    @Test
    void namesFollowTheMappersNamingStrategy() {
        SchemaGenerator snakeCase = new SchemaGenerator(
                JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build(), VALIDATOR);

        Schema<?> output = snakeCase.output(ResolvableType.forClass(Article.class));
        Schema<?> input = snakeCase.input(ResolvableType.forClass(Article.class));
        snakeCase.page(ResolvableType.forClass(Article.class));

        assertThat(output.get$ref()).isEqualTo(REF + "Article");
        assertThat(input.get$ref()).isEqualTo(REF + "Article");
        Map<String, Schema<?>> components = snakeCase.components();
        assertThat(components).containsOnlyKeys("Article", "ArticlePage");
        assertThat(component(snakeCase, "Article").getProperties()).containsOnlyKeys("published_at");
        assertThat(component(snakeCase, "Article").getRequired()).containsExactly("published_at");
        assertThat(component(snakeCase, "ArticlePage").getProperties().keySet())
                .containsExactly("items", "next_cursor",
                        "has_next", "total");
        assertThat(component(snakeCase, "ArticlePage").getRequired()).containsExactly("has_next", "items");
    }

    @Test
    void inputAndOutputNamesCanDiffer() {
        Schema<?> output = generator.output(ResolvableType.forClass(Entry.class));
        Schema<?> input = generator.input(ResolvableType.forClass(Entry.class));
        Schema<?> journalInput = generator.input(ResolvableType.forClass(Journal.class));
        Schema<?> journalOutput = generator.output(ResolvableType.forClass(Journal.class));
        Schema<?> author = generator.input(ResolvableType.forClass(Author.class));

        assertThat(output.get$ref()).isEqualTo(REF + "Entry");
        assertThat(input.get$ref()).isEqualTo(REF + "EntryInput");
        assertThat(journalInput.get$ref()).isEqualTo(REF + "JournalInput");
        assertThat(journalOutput.get$ref()).isEqualTo(REF + "Journal");
        assertThat(author.get$ref()).isEqualTo(REF + "Author");
        assertThat(generator.components())
                .containsOnlyKeys("Entry", "EntryInput", "Journal", "JournalInput",
                        "Author");
        assertThat(component("Entry").getProperties()).containsOnlyKeys("id", "title");
        assertThat(component("EntryInput").getProperties()).containsOnlyKeys("title", "secret");
        assertThat(component("EntryInput").getRequired()).containsExactly("title");
        assertThat(property(component("JournalInput"), "entry").get$ref()).isEqualTo(REF + "EntryInput");
        assertThat(property(component("Journal"), "entry").get$ref()).isEqualTo(REF + "Entry");
    }

    @Test
    void constraintsBecomeKeywords() {
        generator.input(ResolvableType.forClass(Constrained.class));

        // Its collections and maps aren't required in the input, which so differs from its output.
        Schema<?> constrained = component("ConstrainedInput");
        assertThat(property(constrained, "name").getMinLength()).isEqualTo(1);
        assertThat(property(constrained, "code").getMinLength()).isEqualTo(1);
        assertThat(property(constrained, "tags").getMinItems()).isEqualTo(1);
        assertThat(property(constrained, "attributes").getMinProperties()).isEqualTo(1);
        assertThat(property(constrained, "shortText").getMinLength()).isEqualTo(2);
        assertThat(property(constrained, "shortText").getMaxLength()).isEqualTo(5);
        assertThat(property(constrained, "few").getMinItems()).isEqualTo(1);
        assertThat(property(constrained, "few").getMaxItems()).isEqualTo(3);
        assertThat(property(constrained, "some").getMinProperties()).isNull();
        assertThat(property(constrained, "some").getMaxProperties()).isEqualTo(4);
        assertThat(property(constrained, "rating").getMinimum()).isEqualByComparingTo("1");
        assertThat(property(constrained, "rating").getMaximum()).isEqualByComparingTo("10");
        assertThat(property(constrained, "price").getExclusiveMinimumValue()).isEqualByComparingTo("0");
        assertThat(property(constrained, "price").getMinimum()).isNull();
        assertThat(property(constrained, "price").getMaximum()).isEqualByComparingTo("100.5");
        assertThat(property(constrained, "bounded").getMinimum()).isEqualByComparingTo("1");
        assertThat(property(constrained, "bounded").getExclusiveMaximumValue()).isEqualByComparingTo("9");
        assertThat(property(constrained, "positive").getExclusiveMinimumValue()).isEqualByComparingTo("0");
        assertThat(property(constrained, "nonNegative").getMinimum()).isEqualByComparingTo("0");
        assertThat(property(constrained, "negative").getExclusiveMaximumValue()).isEqualByComparingTo("0");
        assertThat(property(constrained, "nonPositive").getMaximum()).isEqualByComparingTo("0");
        assertThat(property(constrained, "lower").getPattern()).isEqualTo("[a-z]+");
        assertThat(property(constrained, "email").getFormat()).isEqualTo("email");
        assertThat(property(constrained, "mandatory").getTypes()).containsExactly("string");
        assertThat(property(constrained, "labels").getItems().getMinLength()).isEqualTo(1);
        assertThat(property(constrained, "labels").getMinItems()).isNull();
        assertThat(((Schema<?>) property(constrained, "notes").getAdditionalProperties()).getMaxLength())
                .isEqualTo(3);
        assertThat(property(constrained, "nickname").getMaxLength()).isEqualTo(8);
        assertThat(property(constrained, "nickname").getTypes()).containsExactly("string", "null");
        assertThat(property(constrained, "unchecked").getMaxLength()).isEqualTo(2);
        assertThat(property(constrained, "tight").getMinLength()).isEqualTo(3);
        assertThat(property(constrained, "floor").getMinimum()).isEqualByComparingTo("2");
        assertThat(property(constrained, "shortCode").getMinLength()).isEqualTo(1);
        assertThat(property(constrained, "shortCode").getMaxLength()).isEqualTo(5);
        // An input collection or map binds empty, so it is required only when its constraints need items.
        assertThat(constrained.getRequired())
                .contains("name", "code", "mandatory", "shortCode", "tags", "attributes", "few")
                .doesNotContain("nickname", "unchecked", "some", "labels", "notes");
    }

    @Test
    void anInputCollectionOrMapIsRequiredOnlyWhenItMustHaveItems() {
        Schema<?> input = generator.input(ResolvableType.forClass(Bag.class));
        Schema<?> output = generator.output(ResolvableType.forClass(Bag.class));
        Schema<?> crate = generator.input(ResolvableType.forClass(Crate.class));

        // The binder binds an absent or null collection or map empty, as gRPC does a repeated or map field, so the
        // input differs from the output, which always writes them.
        assertThat(input.get$ref()).isEqualTo(REF + "BagInput");
        assertThat(output.get$ref()).isEqualTo(REF + "Bag");
        // An absent @NotEmpty or @Size(min >= 1) collection or map is rejected, so it is required, with its minimum.
        assertThat(component("BagInput").getRequired()).containsExactly("codes", "name", "notes", "tags");
        assertThat(property(component("BagInput"), "notes").getMinProperties()).isEqualTo(1);
        assertThat(property(component("BagInput"), "tags").getMinItems()).isEqualTo(1);
        assertThat(component("Bag").getRequired())
                .containsExactly("codes", "counts", "few", "labels", "name", "notes", "tags");
        // A record that holds one is documented apart too, and refers to the input.
        assertThat(crate.get$ref()).isEqualTo(REF + "CrateInput");
        assertThat(property(component("CrateInput"), "bag").get$ref()).isEqualTo(REF + "BagInput");
    }

    @Test
    void recursiveAndSharedTypesAreWalkedOnce() {
        Schema<?> node = generator.output(ResolvableType.forClass(Node.class));
        generator.output(ResolvableType.forClass(Shelf.class));

        assertThat(node.get$ref()).isEqualTo(REF + "Node");
        assertThat(generator.components()).containsOnlyKeys("Node", "Shelf", "Book");
        assertThat(property(component("Node"), "children").getItems().get$ref()).isEqualTo(REF + "Node");
        Schema<?> shelf = component("Shelf");
        assertThat(property(shelf, "first").get$ref()).isEqualTo(REF + "Book");
        assertThat(property(shelf, "second").get$ref()).isEqualTo(REF + "Book");
        assertThat(property(shelf, "others").getItems().get$ref()).isEqualTo(REF + "Book");
        // One walk per type: Node's reference to itself and Shelf's three references to Book reuse the component.
        assertThat(generator.walkCount()).isEqualTo(3);

        // Without a budget the walk would never end: every level doubles the parameterizations, and none repeats.
        SchemaGenerator growing = new SchemaGenerator(JsonMapper.builder().build(), VALIDATOR);
        // The counts below are the check; the timeout is only a backstop (about 30 times the usual second) so that
        // a runaway walk fails here instead of hanging the build.
        Schema<?> grow = assertTimeoutPreemptively(Duration.ofSeconds(30),
                () -> growing.output(ResolvableType.forClassWithGenerics(Grow.class, String.class)));

        assertThat(grow.get$ref()).isEqualTo(REF + "GrowOfString");
        assertThat(growing.components())
                .hasSizeLessThanOrEqualTo(SchemaGenerator.TYPE_BUDGET)
                .containsKeys("GrowOfString", "GrowOfListOfString", "GrowOfSetOfString");
        // The budget bounds the work, not only the result: each type within it is walked once, and no more.
        assertThat(growing.walkCount()).isEqualTo(growing.components().size());
        assertThat(growing.schemaCount()).isLessThanOrEqualTo(DEFAULTS.schemas());
        assertThat(growing.undescribed())
                .hasSize(1)
                .allSatisfy(type -> assertThat(type).startsWith(Grow.class.getCanonicalName() + "<"));
    }

    @Test
    void genericRecordsAreNamedByTheirArguments() {
        Schema<?> envelope = generator.output(ResolvableType.forClassWithGenerics(Envelope.class, Book.class));
        Schema<?> pair = generator.output(ResolvableType.forClassWithGenerics(Pair.class, Book.class, Author.class));
        Schema<?> nested = generator
                .output(ResolvableType
                        .forClassWithGenerics(Envelope.class,
                                ResolvableType.forClassWithGenerics(List.class, Book.class)));

        assertThat(envelope.get$ref()).isEqualTo(REF + "EnvelopeOfBook");
        assertThat(pair.get$ref()).isEqualTo(REF + "PairOfBookAndAuthor");
        assertThat(nested.get$ref()).isEqualTo(REF + "EnvelopeOfListOfBook");
        assertThat(property(component("EnvelopeOfBook"), "content").get$ref()).isEqualTo(REF + "Book");
        assertThat(property(component("PairOfBookAndAuthor"), "second").get$ref()).isEqualTo(REF + "Author");
        assertThat(property(component("EnvelopeOfListOfBook"), "content").getItems().get$ref())
                .isEqualTo(REF + "Book");
        assertThat(generator.components())
                .containsOnlyKeys("EnvelopeOfBook", "PairOfBookAndAuthor",
                        "EnvelopeOfListOfBook", "Book", "Author");
    }

    @Test
    void clashingSimpleNamesUseQualifiedNames() {
        Schema<?> first = generator
                .output(
                        ResolvableType.forClass(io.americanexpress.synapse.api.rest.openapi.internal.a.Book.class));
        assertThat(first.get$ref()).isEqualTo(REF + "Book");

        Schema<?> second = generator
                .output(
                        ResolvableType.forClass(io.americanexpress.synapse.api.rest.openapi.internal.b.Book.class));

        String a = "io.americanexpress.synapse.api.rest.openapi.internal.a.Book";
        String b = "io.americanexpress.synapse.api.rest.openapi.internal.b.Book";
        assertThat(first.get$ref()).isEqualTo(REF + a);
        assertThat(second.get$ref()).isEqualTo(REF + b);
        assertThat(generator.components()).containsOnlyKeys(a, b);
        assertThat(component(a).getProperties()).containsOnlyKeys("title");
        assertThat(component(b).getProperties()).containsOnlyKeys("number");
    }

    @Test
    void unknownTypesAreEmptyAndReported() {
        generator.output(ResolvableType.forClass(Unknown.class));

        Schema<?> unknown = component("Unknown");
        for (String name : List.of("anything", "thread")) {
            Schema<?> property = property(unknown, name);
            assertThat(property.getTypes()).as(name).isNull();
            assertThat(property.get$ref()).as(name).isNull();
        }
        assertThat(generator.undescribed())
                .containsExactlyInAnyOrder("java.lang.Object", "java.lang.Thread");
        // Jackson writes any map as an object: its keys become strings.
        assertThat(describe(property(unknown, "byId"))).isEqualTo("object{string}");
    }

    @Test
    void pageObjectFollowsPageBody() {
        Schema<?> page = generator.page(ResolvableType.forClass(Book.class));

        assertThat(page.get$ref()).isEqualTo(REF + "BookPage");
        Schema<?> component = component("BookPage");
        assertThat(component.getTypes()).containsExactly("object");
        assertThat(describedProperties(component))
                .containsExactly(entry("items", "array[$Book]"),
                        entry("nextCursor", "string"), entry("hasNext", "boolean"), entry("total", "integer/int64"));
        assertThat(component.getRequired()).containsExactly("hasNext", "items");
        assertThat(generator.components()).containsOnlyKeys("BookPage", "Book");
    }

    @Test
    void visitBudgetStopsTheWalk() {
        SchemaGenerator budgeted = limited(3, DEFAULTS.nameLength(), DEFAULTS.schemasPerType(), DEFAULTS.schemas());

        budgeted.output(ResolvableType.forClass(Chain1.class));

        assertThat(budgeted.components()).containsOnlyKeys("Chain1", "Chain2", "Chain3");
        Schema<?> fourth = property(component(budgeted, "Chain3"), "next");
        assertThat(fourth.get$ref()).isNull();
        assertThat(fourth.getTypes()).isNull();
        assertThat(budgeted.undescribed()).containsExactly(SchemaFixtures.class.getCanonicalName() + ".Chain4");

        // A type already counted is still described once the budget is spent.
        assertThat(budgeted.input(ResolvableType.forClass(Chain1.class)).get$ref()).isEqualTo(REF + "Chain1");
        assertThat(budgeted.output(ResolvableType.forClass(Author.class)).getTypes()).isNull();
        assertThat(budgeted.undescribed()).hasSize(1);
    }

    @Test
    void aPageOverTheBudgetIsEmptyAndReported() {
        SchemaGenerator budgeted = limited(1, DEFAULTS.nameLength(), DEFAULTS.schemasPerType(), DEFAULTS.schemas());
        budgeted.output(ResolvableType.forClass(Author.class));

        Schema<?> page = budgeted.page(ResolvableType.forClass(Author.class));

        assertThat(page.get$ref()).isNull();
        assertThat(budgeted.components()).containsOnlyKeys("Author");
        assertThat(budgeted.undescribed())
                .containsExactly(
                        "io.americanexpress.synapse.core.paging.PageResult<" + Author.class.getCanonicalName() + ">");
    }

    @Test
    void typesResolveThroughBoundsWildcardsArraysAndSupertypes() {
        Schema<?> odd = generator.output(ResolvableType.forClass(Odd.class));
        Schema<?> books = generator.output(ResolvableType.forClass(Book[].class));
        Schema<?> level = generator.output(ResolvableType.forClass(Level.class));

        assertThat(odd.get$ref()).isEqualTo(REF + "OddOfComparableOfObject");
        assertThat(describe(books)).isEqualTo("array[$Book]");
        Schema<?> component = component("OddOfComparableOfObject");
        assertThat(property(component, "ranked").getTypes()).isNull();
        assertThat(describe(property(component, "books"))).isEqualTo("array[$Book]");
        assertThat(describe(property(component, "numbers"))).isEqualTo("array[integer/int32]");
        assertThat(property(component, "values").getItems().getTypes()).isNull();
        assertThat(describe(property(component, "array"))).isEqualTo("array[$Book]");
        assertThat(describe(property(component, "ints"))).isEqualTo("array[integer/int32]");
        assertThat(describe(property(component, "tags"))).isEqualTo("array[string]");
        assertThat(describe(property(component, "envelopes"))).isEqualTo("array[$EnvelopeOfString]");
        assertThat(level.get$ref()).isEqualTo(REF + "Level");
        assertThat(component("Level").getTypes()).containsExactly("integer");
        List<Object> levels = List.copyOf(component("Level").getEnum());
        assertThat(levels).containsExactly(0, 1);
        assertThat(generator.undescribed())
                .containsExactly("java.lang.Comparable<java.lang.Object>");
    }

    @Test
    void clashesLeftAfterQualifyingGetASuffix() {
        Schema<?> ledger = generator.input(ResolvableType.forClass(Ledger.class));
        Schema<?> other = generator.output(ResolvableType.forClass(LedgerInput.class));

        String qualified = LedgerInput.class.getCanonicalName();
        assertThat(ledger.get$ref()).isEqualTo(REF + qualified);
        assertThat(other.get$ref()).isEqualTo(REF + qualified + "-2");
        assertThat(component(qualified).getProperties()).containsOnlyKeys("name");
        assertThat(component(qualified + "-2").getProperties()).containsOnlyKeys("name", "extra");
    }

    @Test
    void synapseComponentNamesAreReserved() {
        Schema<?> violation = generator.output(ResolvableType.forClass(SynapseViolation.class));

        // RouteDocumenter adds SynapseProblem and SynapseViolation: an application type with one of those names
        // takes its qualified name, as two application types that claim one name do.
        String qualified = SynapseViolation.class.getCanonicalName();
        assertThat(violation.get$ref()).isEqualTo(REF + qualified);
        assertThat(generator.components()).containsOnlyKeys(qualified);
        assertThat(SchemaGenerator.RESERVED).containsExactlyInAnyOrder("SynapseProblem", "SynapseViolation");
    }

    /** An application record with the name of a Synapse component. */
    record SynapseViolation(String rule) {
    }

    @Test
    void parametersCarryConstraintsButNoNullability() {
        Map<String, Schema<?>> parameters = new LinkedHashMap<>();
        for (RecordComponent component : Objects.requireNonNull(Query.class.getRecordComponents())) {
            parameters.put(component.getName(), generator.parameter(new ResolvedComponent(Query.class, component)));
        }

        assertThat(present(parameters, "title").getTypes()).containsExactly("string");
        assertThat(present(parameters, "title").getMaxLength()).isEqualTo(20);
        assertThat(present(parameters, "page").getTypes()).containsExactly("integer");
        assertThat(present(parameters, "page").getMinimum()).isEqualByComparingTo(BigDecimal.ONE);
        // The ConversionService reads an enum parameter by its constant names: inline, not the JSON-named component.
        assertThat(present(parameters, "sort").get$ref()).isNull();
        assertThat(present(parameters, "sort").getTypes()).containsExactly("string");
        assertThat(List.<Object>copyOf(present(parameters, "sort").getEnum())).containsExactly("TITLE", "YEAR");
        assertThat(describe(present(parameters, "tags"))).isEqualTo("array[string]");
        assertThat(generator.components()).isEmpty();
    }

    @Test
    void objectsOfSomeComponentsAreInline() {
        Schema<?> output = generator.outputObject(Entry.class, name -> !name.equals("title"));
        Schema<?> input = generator.inputObject(Entry.class, name -> true);
        Schema<?> customer = generator.inputObject(Customer.class, Set.of("address")::contains);

        assertThat(output.get$ref()).isNull();
        assertThat(output.getTypes()).containsExactly("object");
        assertThat(output.getProperties()).containsOnlyKeys("id");
        assertThat(output.getRequired()).containsExactly("id");
        assertThat(input.getProperties()).containsOnlyKeys("title", "secret");
        assertThat(customer.getProperties()).containsOnlyKeys("address");
        assertThat(customer.getRequired()).isNull();
        assertThat(generator.components()).containsOnlyKeys("Address");
    }

    private static SchemaGenerator limited(int types, int nameLength, int schemasPerType, int schemas) {
        return new SchemaGenerator(MAPPER, VALIDATOR,
                new SchemaGenerator.Limits(types, nameLength, schemasPerType, schemas));
    }

    private static String name(Schema<?> reference) {
        assertThat(reference.get$ref()).startsWith(REF);
        return reference.get$ref().substring(REF.length());
    }

    private Schema<?> component(String name) {
        return component(generator, name);
    }

    private static Schema<?> component(SchemaGenerator generator, String name) {
        return present(generator.components(), name);
    }

    private static Schema<?> present(Map<String, Schema<?>> schemas, String name) {
        Schema<?> schema = schemas.get(name);
        assertThat(schema).as(name).isNotNull();
        return Objects.requireNonNull(schema);
    }

    private static Schema<?> property(Schema<?> object, String name) {
        Schema<?> property = object.getProperties().get(name);
        assertThat(property).as(name).isNotNull();
        return Objects.requireNonNull(property);
    }

    private static Map<String, String> describedProperties(Schema<?> object) {
        Map<String, String> described = new LinkedHashMap<>();
        object.getProperties().forEach((name, property) -> described.put(name, describe(property)));
        return described;
    }

    /** {@code type/format}, {@code array[items]}, {@code object{values}} or {@code $Name} for a reference. */
    private static String describe(Schema<?> schema) {
        if (schema.get$ref() != null) {
            return "$" + schema.get$ref().substring(REF.length());
        }
        Set<String> types = schema.getTypes();
        if (types.contains("array")) {
            return "array[" + describe(schema.getItems()) + "]"
                    + (Boolean.TRUE.equals(schema.getUniqueItems()) ? " unique" : "");
        }
        if (types.contains("object")) {
            return "object{" + describe((Schema<?>) schema.getAdditionalProperties()) + "}";
        }
        return String.join(",", types) + (schema.getFormat() == null ? "" : "/" + schema.getFormat());
    }
}
