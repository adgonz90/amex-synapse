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

import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.api.grpc.fixture.proto.Item;
import io.americanexpress.synapse.api.grpc.fixture.proto.Scalars;
import io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.Genre;
import io.americanexpress.synapse.api.grpc.internal.mapping.MappingFixtures.Shelf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class EnumNamesTest {

    private static final EnumDescriptor GENRE = MappingFixtures.field(Item.getDescriptor(), "genre").getEnumType();

    private static final EnumDescriptor SHELF = MappingFixtures.field(Scalars.getDescriptor(), "shelf").getEnumType();

    @Test
    void everyJavaConstantNeedsAProtoValue() {
        assertThat(EnumNames.firstWithoutValue(Genre.class, GENRE)).isNull();
        assertThat(EnumNames.firstWithoutValue(Shelf.class, SHELF)).isNull();
        assertThat(EnumNames.firstWithoutValue(Wider.class, GENRE)).isEqualTo(Wider.POETRY);
        assertThatIllegalArgumentException().isThrownBy(() -> EnumNames.toProto(Wider.POETRY, GENRE));
    }

    @Test
    void valuesMatchByNameWithOrWithoutTheEnumPrefix() {
        assertThat(EnumNames.toJava(GENRE.findValueByName("GENRE_FICTION"), Genre.class)).isEqualTo(Genre.FICTION);
        assertThat(EnumNames.toJava(SHELF.findValueByName("TOP"), Shelf.class)).isEqualTo(Shelf.TOP);
        assertThat(EnumNames.toProto(Genre.SCIENCE, GENRE).getName()).isEqualTo("GENRE_SCIENCE");
        assertThat(EnumNames.toProto(Shelf.BOTTOM, SHELF).getName()).isEqualTo("BOTTOM");
    }

    @Test
    void unspecifiedOrUnknownZeroIsNull() throws DescriptorValidationException {
        EnumDescriptor color = color();

        assertThat(EnumNames.toJava(GENRE.findValueByNumber(0), Genre.class)).isNull();
        assertThat(EnumNames.toJava(color.findValueByNumber(0), Genre.class)).isNull();
        assertThat(EnumNames.hasUnspecifiedZero(GENRE)).isTrue();
        assertThat(EnumNames.hasUnspecifiedZero(color)).isTrue();
        assertThat(EnumNames.hasUnspecifiedZero(SHELF)).isFalse();
        assertThat(EnumNames.toJava(SHELF.findValueByNumber(0), Shelf.class)).isEqualTo(Shelf.TOP);
    }

    @Test
    void constantMatchingTheUnspecifiedValueIsFound() throws DescriptorValidationException {
        assertThat(EnumNames.firstMatchingUnspecified(Color.class, color())).isEqualTo(Color.UNKNOWN);
        assertThat(EnumNames.firstMatchingUnspecified(Genre.class, GENRE)).isNull();
        assertThat(EnumNames.firstMatchingUnspecified(Shelf.class, SHELF)).isNull();
    }

    @Test
    void valueWithoutConstantFails() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> EnumNames.toJava(GENRE.findValueByNumberCreatingIfUnknown(9), Genre.class));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> EnumNames.toJava(GENRE.findValueByName("GENRE_SCIENCE"), Shelf.class));
    }

    @Test
    void prefixIsTheEnumNameInUpperSnakeCase() {
        assertThat(EnumNames.prefix("Genre")).isEqualTo("GENRE");
        assertThat(EnumNames.prefix("BookStatus")).isEqualTo("BOOK_STATUS");
        assertThat(EnumNames.prefix("HTTPStatus")).isEqualTo("HTTP_STATUS");
        assertThat(EnumNames.prefix("V2Kind")).isEqualTo("V2_KIND");
        assertThat(EnumNames.prefix("ISBN")).isEqualTo("ISBN");
    }

    private static EnumDescriptor color() throws DescriptorValidationException {
        FileDescriptorProto file = FileDescriptorProto
                .newBuilder()
                .setName("color.proto")
                .setSyntax("proto3")
                .addEnumType(EnumDescriptorProto
                        .newBuilder()
                        .setName("Color")
                        .addValue(EnumValueDescriptorProto.newBuilder().setName("COLOR_UNKNOWN").setNumber(0))
                        .addValue(EnumValueDescriptorProto.newBuilder().setName("COLOR_RED").setNumber(1)))
                .build();
        return FileDescriptor.buildFrom(file, new FileDescriptor[0]).getEnumTypes().getFirst();
    }

    /** A Java enum with a constant the proto enum lacks. */
    enum Wider {
        FICTION, POETRY
    }

    /** A Java enum whose {@code UNKNOWN} matches {@code COLOR_UNKNOWN = 0}. */
    enum Color {
        UNKNOWN, RED
    }
}
