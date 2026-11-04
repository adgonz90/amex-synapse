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
package io.americanexpress.synapse.test.json;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonFixturesTest {

    record Book(String title, String author) {
    }

    @Test
    void fixturesReadRecordsAndLists() {
        JsonFixtures fixtures = JsonFixtures.standard();

        assertThat(fixtures.read("fixtures/book.json", Book.class)).isEqualTo(new Book("Dune", "Herbert"));
        assertThat(fixtures.read("/fixtures/book.json", Book.class)).isEqualTo(new Book("Dune", "Herbert"));
        assertThat(fixtures.read("fixtures/books.json", new TypeReference<List<Book>>() {
        })).containsExactly(new Book("Dune", "Herbert"), new Book("Emma", "Austen"));
    }

    @Test
    void textReturnsTheRawJson() {
        assertThat(JsonFixtures.standard().text("/fixtures/book.json")).contains("\"title\":\"Dune\"");
    }

    @Test
    void ofUsesTheGivenMapper() {
        JsonMapper mapper = JsonMapper.builder().build();

        assertThat(JsonFixtures.of(mapper).read("fixtures/book.json", Book.class).title()).isEqualTo("Dune");
    }

    @Test
    void missingFixtureNamesThePath() {
        JsonFixtures fixtures = JsonFixtures.standard();

        assertThatThrownBy(() -> fixtures.read("fixtures/nope.json", Book.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JSON fixture not found on the classpath: fixtures/nope.json");
        assertThatThrownBy(() -> fixtures.read("/fixtures/nope.json", new TypeReference<List<Book>>() {
        })).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("/fixtures/nope.json");
        assertThatThrownBy(() -> fixtures.text("nope.json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope.json");
    }
}
