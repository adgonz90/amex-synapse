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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Negative;
import jakarta.validation.constraints.NegativeOrZero;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.ser.std.StdSerializer;
import tools.jackson.databind.util.StdConverter;

/** Records and enums the schema generator describes. */
final class SchemaFixtures {

    private SchemaFixtures() {
    }

    /** One component of every simple type the generator knows. */
    @SuppressWarnings("ArrayRecordComponent") // byte[] and String[] are types under test
    record Simple(String text, UUID id, URI link, boolean flag, Boolean boxedFlag, byte tiny, short small, int count,
            Integer boxedCount, long big, BigInteger huge, float ratio, double precise, BigDecimal amount,
            Instant instant, OffsetDateTime offset, ZonedDateTime zoned, LocalDate day, Duration span, LocalTime time,
            byte[] bytes, String[] words, List<String> list, Collection<Integer> numbers, Set<String> tags,
            Map<String, Long> counts, Optional<String> maybe) {
    }

    record Book(UUID id, String title, @Nullable Integer year) {
    }

    record Author(String name) {
    }

    record Address(String street) {
    }

    record Customer(String name, @Nullable Address address, Optional<Address> billing) {
    }

    enum Sort {
        @JsonProperty("by-title")
        TITLE, YEAR
    }

    record Article(Instant publishedAt) {
    }

    /** An entry whose id is written but never read, and whose secret is read but never written. */
    record Entry(@JsonProperty(access = JsonProperty.Access.READ_ONLY) UUID id, String title,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @Nullable String secret) {
    }

    /** Reads the same names it writes, but holds an entry, which doesn't. */
    record Journal(Entry entry) {
    }

    @SuppressWarnings("MultipleNullnessAnnotations") // @NotNull winning over @Nullable is under test
    record Constrained(@NotBlank String name, @NotEmpty String code, @NotEmpty List<String> tags,
            @NotEmpty Map<String, String> attributes, @Size(min = 2, max = 5) String shortText,
            @Size(min = 1, max = 3) List<String> few, @Size(max = 4) Map<String, String> some,
            @Min(1) @Max(10) int rating,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("100.5") BigDecimal price,
            @DecimalMin("1") @DecimalMax(value = "9", inclusive = false) BigDecimal bounded, @Positive int positive,
            @PositiveOrZero int nonNegative, @Negative int negative, @NegativeOrZero int nonPositive,
            @Pattern(regexp = "[a-z]+") String lower, @Email String email, @NotNull @Nullable String mandatory,
            List<@NotBlank String> labels, Map<String, @Size(max = 3) String> notes,
            Optional<@Size(max = 8) String> nickname, @Size(max = 2) @Nullable String unchecked,
            @NotBlank @Size(min = 3) String tight, @Min(2) @PositiveOrZero int floor, @ShortCode String shortCode) {
    }

    record Node(String name, List<Node> children) {
    }

    record Shelf(Book first, Book second, List<Book> others) {
    }

    /**
     * A generic record that branches and grows: every level doubles the parameterizations to walk
     * ({@code Grow<List<T>>} and {@code Grow<Set<T>>}), and none repeats.
     */
    record Grow<T>(T value, Optional<Grow<List<T>>> listed, Optional<Grow<Set<T>>> collected) {
    }

    record Envelope<T>(T content) {
    }

    record Pair<A, B>(A first, B second) {
    }

    record Unknown(Object anything, Thread thread, Map<UUID, String> byId) {
    }

    record Chain1(Chain2 next) {
    }

    record Chain2(Chain3 next) {
    }

    record Chain3(Chain4 next) {
    }

    record Chain4(String end) {
    }

    record Query(@Nullable @Size(max = 20) String title, Optional<@Min(1) Integer> page, @Nullable Sort sort,
            List<String> tags) {
    }

    /** A code: not blank and at most five characters, composed of two built-in constraints. */
    @NotBlank
    @Size(max = 5)
    @Constraint(validatedBy = {})
    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE_USE})
    @Retention(RetentionPolicy.RUNTIME)
    @interface ShortCode {

        String message() default "must be a short code";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    /** Types resolved through bounds, wildcards, arrays and supertypes. */
    @SuppressWarnings("ArrayRecordComponent") // generic, record and primitive arrays are types under test
    record Odd<T extends Comparable<T>>(T ranked, List<? extends Book> books, List<? super Integer> numbers,
            T[] values, Book[] array, int[] ints, Tags tags, Envelope<String>[] envelopes) {
    }

    /** A list of strings by inheritance. */
    static final class Tags extends ArrayList<String> {

        private static final long serialVersionUID = 1L;
    }

    /** An enum the mapper writes as numbers. */
    enum Level {
        LOW(0), HIGH(1);

        private final int code;

        Level(int code) {
            this.code = code;
        }

        @JsonValue
        int code() {
            return code;
        }
    }

    /** Needs an input component, whose name clashes with the record below. */
    record Ledger(@JsonProperty(access = JsonProperty.Access.READ_ONLY) UUID id, String name) {
    }

    record LedgerInput(String name, int extra) {
    }

    /** Grows by one level per step: the type budget alone doesn't bound the size of its types. */
    record Deep<T>(T value, Optional<Deep<List<T>>> next) {
    }

    /** Types Jackson writes in a way their Java type doesn't tell. */
    record Wire(Year year, Month month, DayOfWeek day, char letter, Character boxedLetter, OptionalInt count,
            OptionalLong total, OptionalDouble ratio, Map<UUID, String> byId, Map<Integer, Long> byNumber,
            Level level) {
    }

    /** Written as its value alone. */
    record Token(@JsonValue String value) {
    }

    /** Read through a delegating factory, from any object: not by its components. */
    record DelFactory(@Nullable String a, @Nullable String b) {

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        static DelFactory of(Map<String, String> values) {
            return new DelFactory(values.get("x"), values.get("y"));
        }
    }

    /** Read by its builder. */
    @JsonDeserialize(builder = Built.Builder.class)
    record Built(@Nullable String a) {

        static final class Builder {

            private @Nullable String a;

            Builder withA(@Nullable String a) {
                this.a = a;
                return this;
            }

            Built build() {
                return new Built(a);
            }
        }
    }

    /** Two components with one JSON name, which Jackson refuses. */
    record Clash(@JsonProperty("x") String a, @JsonProperty("x") String b) {
    }

    /** Read property by property, but Jackson never reads its only component: an object without properties. */
    record Stamp(@JsonProperty(access = JsonProperty.Access.READ_ONLY) @Nullable Instant at) {
    }

    /** Written by its own serializer. */
    @JsonSerialize(using = WrappedSerializer.class)
    record Wrapped(String inner) {
    }

    static final class WrappedSerializer extends StdSerializer<Wrapped> {

        WrappedSerializer() {
            super(Wrapped.class);
        }

        @Override
        public void serialize(Wrapped value, JsonGenerator generator, SerializationContext context) {
            generator.writeString(value.inner());
        }
    }

    /** Read by its own deserializer. */
    @JsonDeserialize(using = ParsedDeserializer.class)
    record Parsed(String text) {
    }

    static final class ParsedDeserializer extends StdDeserializer<Parsed> {

        ParsedDeserializer() {
            super(Parsed.class);
        }

        @Override
        public Parsed deserialize(JsonParser parser, DeserializationContext context) {
            return new Parsed(parser.getString());
        }
    }

    /** A note the mapper leaves out when it is empty. */
    record Sparse(@JsonInclude(JsonInclude.Include.NON_EMPTY) String note, String title) {
    }

    /** Written as the text a converter makes of it. */
    @JsonSerialize(converter = ConvertedToText.class)
    record Converted(String text) {
    }

    static final class ConvertedToText extends StdConverter<Converted, String> {

        @Override
        public String convert(Converted value) {
            return value.text();
        }
    }

    /** Read from the text a converter turns into it. */
    @JsonDeserialize(converter = TextToReconverted.class)
    record Reconverted(String text) {
    }

    static final class TextToReconverted extends StdConverter<String, Reconverted> {

        @Override
        public Reconverted convert(String value) {
            return new Reconverted(value);
        }
    }

    /** Written and read as an array of its components. */
    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    record Point(int x, int y) {
    }

    /** Annotated as an object; a mapper's config override can make it an array. */
    @JsonFormat(shape = JsonFormat.Shape.OBJECT)
    record Span(int left, int right) {
    }
}
