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
package io.americanexpress.synapse.api.rest.internal.paging;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.SortKey;
import io.americanexpress.synapse.core.SynapseConfigurationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.entry;

class SortKeysTest {

    @Test
    void enumWireNamesUseJsonPropertyElseLowerCamel() {
        SortKeys keys = SortKeys.of(SortKey.class);

        assertThat(keys.wireToProperty())
                .containsExactly(entry("title", "TITLE"), entry("year", "PUBLISHED_YEAR"),
                        entry("authorName", "AUTHOR_NAME"));
        assertThat(keys.property("year")).isEqualTo("PUBLISHED_YEAR");
        assertThat(keys.property("PUBLISHED_YEAR")).isNull();
        assertThat(keys.property("Title")).isNull();
        assertThat(keys.allowed()).isEqualTo("title, year, authorName");
    }

    @Test
    void mixedCaseConstantsKeepTheirCamelCase() {
        assertThat(SortKeys.of(MixedKey.class).wireToProperty())
                .containsExactly(entry("publishedAt", "publishedAt"), entry("isbn13", "ISBN13"),
                        entry("title", "Title"), entry("empty", "EMPTY"));
    }

    @Test
    void namesMapToThemselves() {
        SortKeys keys = SortKeys.of(List.of("title", "year"));

        assertThat(keys.wireToProperty()).containsExactly(entry("title", "title"), entry("year", "year"));
        assertThat(keys.property("title")).isEqualTo("title");
        assertThat(keys.allowed()).isEqualTo("title, year");
    }

    @Test
    void duplicateWireNameFails() {
        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> SortKeys.of(DuplicateKey.class))
                .satisfies(e -> {
                    assertThat(e.description()).contains("two sort keys", "title", "NAME", "TITLE");
                    assertThat(e.action()).isNotBlank();
                });
        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> SortKeys.of(List.of("title", "year", "title")))
                .satisfies(e -> assertThat(e.description()).contains("two sort keys", "title"));
    }

    @Test
    void invalidOrMissingKeysFail() {
        for (List<String> names : List.of(List.of("-title"), List.of("a b"), List.of(""), List.of("tïtle"))) {
            assertThatExceptionOfType(SynapseConfigurationException.class)
                    .as("%s", names)
                    .isThrownBy(() -> SortKeys.of(names))
                    .satisfies(e -> {
                        assertThat(e.description()).contains("not a valid wire name");
                        assertThat(e.action()).isNotBlank();
                    });
        }
        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> SortKeys.of(BadWireKey.class))
                .satisfies(e -> assertThat(e.description()).contains("'by-date'", "not a valid wire name"));
        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> SortKeys.of(List.of()))
                .satisfies(e -> assertThat(e.description()).contains("no sort keys"));
        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> SortKeys.of(NoKey.class))
                .satisfies(e -> assertThat(e.description()).contains("no sort keys"));
    }

    enum DuplicateKey {
        @JsonProperty("title")
        NAME,
        TITLE
    }

    enum MixedKey {
        publishedAt,
        ISBN13,
        Title,
        @JsonProperty
        EMPTY
    }

    enum BadWireKey {
        @JsonProperty("by-date")
        DATE
    }

    enum NoKey {
    }
}
