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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.api.grpc.fixture.proto.Author;
import io.americanexpress.synapse.api.grpc.fixture.proto.Item;
import io.americanexpress.synapse.api.grpc.internal.binding.FieldOverride;
import io.americanexpress.synapse.core.binding.ComponentRef;
import io.americanexpress.synapse.core.internal.binding.ComponentRefs;

/**
 * Records, enums and messages the mapping tests share. The package is {@code @NullMarked} (through the main
 * {@code package-info}), so component nullness is declared.
 */
final class MappingFixtures {

    static final UUID ID = UUID.fromString("0d4f1c9e-8a43-4a52-9a43-6f1e2b3c4d5e");

    static final Instant CREATED = Instant.parse("2026-11-05T10:15:30.123456789Z");

    private MappingFixtures() {
    }

    /** The Java side of {@code synapse.fixture.v1.Genre}. */
    enum Genre {
        FICTION, SCIENCE
    }

    /** The Java side of {@code synapse.fixture.v1.Shelf}, whose values are named like the constants. */
    enum Shelf {
        TOP, BOTTOM
    }

    /** A value type converted from and to a string by its static {@code valueOf} and {@code toString}. */
    static final class CountryCode {

        private final String value;

        private CountryCode(String value) {
            this.value = value;
        }

        /**
         * Parses a two-letter code.
         *
         * @param value the code
         * @return the code
         */
        public static CountryCode valueOf(String value) {
            if (!value.matches("[A-Z]{2}")) {
                throw new IllegalArgumentException("Not a country code");
            }
            return new CountryCode(value);
        }

        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof CountryCode code && code.value.equals(value);
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }

        @Override
        public String toString() {
            return value;
        }
    }

    record AuthorRec(String name, CountryCode countryCode) {
    }

    record ItemRec(UUID id, String name, @Nullable Instant createdAt, @Nullable Duration ttl, @Nullable Integer rating,
            @Nullable String note, Genre genre, List<String> tags, Map<String, Long> counts, AuthorRec author,
            @Nullable String sku, long version) {
    }

    record CreateItemRec(ItemRec item, String channel) {
    }

    /** {@code .map("sku", ItemRec::sku)}, which every {@link ItemRec} mapping needs (sku is in a oneof). */
    static List<FieldOverride> itemOverrides() {
        return List.of(override("sku", ItemRec::sku));
    }

    static <T> FieldOverride override(String path, ComponentRef<T, ?> component) {
        return new FieldOverride(path, ComponentRefs.resolve(component));
    }

    static ItemRec itemRecord() {
        return new ItemRec(ID, "Dune", CREATED, Duration.ofSeconds(90, 5), 4, "signed", Genre.FICTION,
                List.of("classic", "space"), Map.of("views", 12L, "likes", 3L),
                new AuthorRec("Frank Herbert", CountryCode.valueOf("US")), "SKU-1", 7L);
    }

    static Item item() {
        return Item
                .newBuilder()
                .setId(ID.toString())
                .setName("Dune")
                .setCreatedAt(com.google.protobuf.Timestamp
                        .newBuilder()
                        .setSeconds(CREATED.getEpochSecond())
                        .setNanos(CREATED.getNano()))
                .setTtl(com.google.protobuf.Duration.newBuilder().setSeconds(90).setNanos(5))
                .setRating(com.google.protobuf.Int32Value.of(4))
                .setNote("signed")
                .setGenre(io.americanexpress.synapse.api.grpc.fixture.proto.Genre.GENRE_FICTION)
                .addTags("classic")
                .addTags("space")
                .putCounts("views", 12L)
                .putCounts("likes", 3L)
                .setAuthor(Author.newBuilder().setName("Frank Herbert").setCountryCode("US"))
                .setSku("SKU-1")
                .setVersion(7L)
                .build();
    }

    /** Re-reads a generated message as a {@link DynamicMessage}, as the edge receives it. */
    static DynamicMessage dynamic(Message message) {
        try {
            return DynamicMessage.parseFrom(message.getDescriptorForType(), message.toByteString());
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException(e);
        }
    }

    static FieldDescriptor field(Descriptor message, String name) {
        return Objects.requireNonNull(message.findFieldByName(name), name);
    }
}
