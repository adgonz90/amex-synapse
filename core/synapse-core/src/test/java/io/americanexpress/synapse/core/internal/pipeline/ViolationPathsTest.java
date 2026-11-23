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
package io.americanexpress.synapse.core.internal.pipeline;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.Test;

import static io.americanexpress.synapse.core.internal.pipeline.PipelineFixtures.VALIDATOR;
import static org.assertj.core.api.Assertions.assertThat;

class ViolationPathsTest {

    record Author(@NotBlank String name) {
    }

    record Book(@NotBlank String title, @Valid Author author) {
    }

    record Tagged(List<@NotBlank String> tags) {
    }

    record Item(@NotBlank String sku) {
    }

    record Order(List<@Valid Item> items) {
    }

    record Shipment(@Valid Item[] items) {
    }

    record Account(Set<@NotBlank String> roles) {
    }

    record KeyLabels(Map<@NotBlank @Size(max = 3) String, String> labels) {
    }

    record ValueLabels(Map<String, @NotBlank String> labels) {
    }

    record Ranking(Map<String, @Valid Author> authorsByRank) {
    }

    record Everything(@Valid Book book, @Valid Tagged tagged, @Valid Order order, @Valid Shipment shipment,
            @Valid Account account, @Valid KeyLabels keys, @Valid ValueLabels values, @Valid Ranking ranking) {
    }

    /** The paths of every violation of the object, sorted. */
    private static List<String> paths(Object object) {
        return VALIDATOR
                .validate(object)
                .stream()
                .map(violation -> ViolationPaths.of(violation.getPropertyPath()))
                .sorted()
                .toList();
    }

    private static Map<String, String> keys(String... keys) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (String key : keys) {
            labels.put(key, "v");
        }
        return labels;
    }

    @Test
    void propertyIsItsName() {
        assertThat(paths(new Book("", new Author("Jane")))).containsExactly("title");
    }

    @Test
    void nestedPropertyIsDotted() {
        assertThat(paths(new Book("Emma", new Author("")))).containsExactly("author.name");
    }

    @Test
    void scalarListItemKeepsItsIndex() {
        assertThat(paths(new Tagged(List.of("a", "")))).containsExactly("tags[1]");
    }

    @Test
    void listOfRecordsKeepsIndexAndProperty() {
        assertThat(paths(new Order(List.of(new Item("a"), new Item("b"), new Item(" ")))))
                .containsExactly("items[2].sku");
    }

    @Test
    void arrayItemKeepsItsIndex() {
        assertThat(paths(new Shipment(new Item[]{new Item("a"), new Item("")}))).containsExactly("items[1].sku");
    }

    @Test
    void setElementHasNoIndex() {
        assertThat(paths(new Account(Set.of("admin", " ")))).containsExactly("roles");
    }

    @Test
    void mapKeyIsNeverWritten() {
        List<String> paths = paths(new KeyLabels(keys("", "a.b[c]")));

        assertThat(paths).containsExactly("labels", "labels");
        assertThat(paths).allSatisfy(path -> assertThat(path).doesNotContain("a.b").doesNotContain("[c]"));
    }

    @Test
    void wholeMapValueNamesTheMap() {
        assertThat(paths(new ValueLabels(Map.of("k", " ")))).containsExactly("labels");
    }

    @Test
    void propertyInsideAMapValueContinuesWithTheValue() {
        List<String> paths = paths(new Ranking(Map.of("x]y", new Author(""))));

        assertThat(paths).containsExactly("authorsByRank.name");
    }

    @Test
    void noContainerNodeNameIsEverWritten() {
        Everything everything = new Everything(new Book("", new Author("")), new Tagged(List.of("")),
                new Order(List.of(new Item(""))), new Shipment(new Item[]{new Item("")}), new Account(Set.of("")),
                new KeyLabels(keys("", "long")), new ValueLabels(Map.of("k", "")),
                new Ranking(Map.of("x]y", new Author(""))));

        List<String> paths = paths(everything);

        assertThat(paths)
                .containsExactly("account.roles", "book.author.name", "book.title", "keys.labels", "keys.labels",
                        "order.items[0].sku", "ranking.authorsByRank.name", "shipment.items[0].sku",
                        "tagged.tags[0]", "values.labels");
        assertThat(paths).allSatisfy(path -> assertThat(path).doesNotContain("<").doesNotContain("x]y"));
    }
}
