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
package io.americanexpress.synapse.api.rest.internal.binding;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JsonParser;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.exc.InvalidNullException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.util.AccessPattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class EmptyContainersTest {

    private final JsonMapper mapper = JsonMapper.builder().addModule(EmptyContainers.module()).build();

    record Shelf(List<List<String>> rows, Map<String, List<String>> byRoom, Set<TimeUnit> units,
            String @Nullable [] codes) {
    }

    record Strict(@JsonSetter(nulls = Nulls.FAIL) List<String> tags) {
    }

    @Test
    void aNullOrAbsentCollectionOrMapIsEmptyAtEveryDepth() {
        Shelf shelf = mapper
                .readValue("{\"rows\":[null,[\"a\"]],\"byRoom\":{\"hall\":null},\"units\":null}",
                        Shelf.class);

        // A null element or map value that is itself a collection is empty too: one rule at every depth.
        assertThat(shelf.rows()).containsExactly(List.of(), List.of("a"));
        assertThat(shelf.byRoom()).containsExactly(Map.entry("hall", List.of()));
        assertThat(shelf.units()).isEqualTo(EnumSet.noneOf(TimeUnit.class));
        assertThat(mapper.readValue("{}", Shelf.class).rows()).isEmpty();
        assertThat(mapper.readValue("null", new TypeReference<List<String>>() {
        })).isEmpty();
    }

    @Test
    void eachNullGetsItsOwnEmptyValue() {
        try (JsonParser parser = mapper.treeAsTokens(mapper.createObjectNode())) {
            ValueDeserializer<Object> deserializer = ((DeserializationContext) parser.objectReadContext())
                    .findRootValueDeserializer(mapper.constructType(List.class));

            // Never one shared, mutable instance.
            assertThat(deserializer.getNullAccessPattern()).isEqualTo(AccessPattern.DYNAMIC);
        }
        assertThat(mapper.readValue("{}", Shelf.class).rows()).isNotSameAs(mapper.readValue("{}", Shelf.class).rows());
    }

    @Test
    void anArrayKeepsNull() {
        assertThat(mapper.readValue("{\"codes\":null}", Shelf.class).codes()).isNull();
        assertThat(mapper.readValue("{}", Shelf.class).codes()).isNull();
    }

    @Test
    void aComponentsOwnNullHandlingWins() {
        assertThatExceptionOfType(InvalidNullException.class)
                .isThrownBy(() -> mapper.readValue("{\"tags\":null}", Strict.class));
    }
}
