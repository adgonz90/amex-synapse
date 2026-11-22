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
package io.americanexpress.synapse.core.internal.paging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import io.americanexpress.synapse.core.conditional.VersionCondition;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.SortOrder;
import io.americanexpress.synapse.core.paging.SortOrder.Direction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class InputDigestTest {

    private final InputDigest digest = new InputDigest();

    @Test
    void pageQueryIsLeftOut() {
        String first = digest.of(new SearchRequest("a", PageQuery.first(10)), Map.of());
        String other = digest
                .of(new SearchRequest("a",
                        new PageQuery("token", null, 50, List.of(new SortOrder("TITLE", Direction.DESC)))), Map.of());

        assertThat(first).isEqualTo(other).matches("[0-9a-f]{64}");
        // The record is written as a JSON object keyed by component name, without the PageQuery component.
        assertThat(first).isEqualTo(sha256("{\"author\":\"a\"}"));
    }

    @Test
    void changesWithFilters() {
        assertThat(digest.of(new SearchRequest("a", PageQuery.first(10)), Map.of()))
                .isNotEqualTo(digest.of(new SearchRequest("b", PageQuery.first(10)), Map.of()))
                .isNotEqualTo(digest.of(new SearchRequest(null, PageQuery.first(10)), Map.of()));
    }

    @Test
    void isStableForMapComponents() {
        Map<String, String> ab = new LinkedHashMap<>();
        ab.put("a", "1");
        ab.put("b", "2");
        Map<String, String> ba = new LinkedHashMap<>();
        ba.put("b", "2");
        ba.put("a", "1");

        String first = digest.of(new TaggedRequest(ab, "z", PageQuery.first(10)), Map.of());

        assertThat(first).isEqualTo(digest.of(new TaggedRequest(ba, "z", PageQuery.first(10)), Map.of()));
        // Components are ordered by name too, whatever their declaration order.
        assertThat(first).isEqualTo(sha256("{\"genre\":\"z\",\"tags\":{\"a\":\"1\",\"b\":\"2\"}}"));
    }

    @Test
    void versionConditionComponentsAreLeftOutByType() {
        String first = digest
                .of(new VersionedRequest("a", VersionCondition.of(1L), VersionCondition.of("x"),
                        new VersionCondition.Version<>(3L), PageQuery.first(10)), Map.of());

        // Whatever binds them (a header, a gRPC field) and whatever their value, nullable or not, or a case's type.
        assertThat(first)
                .isEqualTo(digest
                        .of(new VersionedRequest("a", VersionCondition.of(2L), VersionCondition.any(),
                                new VersionCondition.Version<>(4L), PageQuery.first(10)), Map.of()))
                .isEqualTo(digest
                        .of(new VersionedRequest("a", null, null, new VersionCondition.Version<>(5L),
                                PageQuery.first(10)), Map.of()))
                .isEqualTo(sha256("{\"author\":\"a\"}"))
                .isNotEqualTo(digest
                        .of(new VersionedRequest("b", VersionCondition.of(1L), VersionCondition.of("x"),
                                new VersionCondition.Version<>(3L), PageQuery.first(10)), Map.of()));
    }

    @Test
    void noInputIsTheEmptyObject() {
        assertThat(digest.of(null, Map.of())).isEqualTo(sha256("{}"));
        // A whole PageQuery input is no filter either, whatever its token, size and sort.
        assertThat(digest.of(PageQuery.first(10), Map.of()))
                .isEqualTo(digest.of(new PageQuery("token", null, 5, List.of()), Map.of()))
                .isEqualTo(sha256("{}"));
    }

    @Test
    void extraEntriesChangeTheDigest() {
        SearchRequest input = new SearchRequest("a", PageQuery.first(10));

        String v1 = digest.of(input, Map.of("@version", "1.0.0"));

        // An extra entry is written beside the components, under its own key.
        assertThat(v1)
                .isEqualTo(sha256("{\"@version\":\"1.0.0\",\"author\":\"a\"}"))
                .isNotEqualTo(digest.of(input, Map.of()))
                .isNotEqualTo(digest.of(input, Map.of("@version", "2.0.0")));
        assertThat(digest.of(null, Map.of("@version", "1.0.0"))).isEqualTo(sha256("{\"@version\":\"1.0.0\"}"));
        assertThat(digest.of(PageQuery.first(10), Map.of("@version", "1.0.0")))
                .isEqualTo(sha256("{\"@version\":\"1.0.0\"}"));
    }

    @Test
    void extraKeyWithoutAtFails() {
        SearchRequest input = new SearchRequest("a", PageQuery.first(10));

        // Without the '@', an entry could take a component's place: {"author":"b"} for the filter author=a.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> digest.of(input, Map.of("author", "b")))
                .withMessageContaining("'@'")
                .withMessageContaining("author");
        assertThatIllegalArgumentException().isThrownBy(() -> digest.of(null, Map.of("", "b")));
    }

    @Test
    void setsAreWrittenSortedWhateverTheirIterationOrder() {
        Set<String> bac = new LinkedHashSet<>(List.of("b", "a", "c"));
        Set<String> cba = new LinkedHashSet<>(List.of("c", "b", "a"));
        assertThat(List.copyOf(bac)).isNotEqualTo(List.copyOf(cba));

        String first = digest
                .of(new LabelledRequest(bac, Map.of("k", new LinkedHashSet<>(List.of(2, 1))), PageQuery.first(5)),
                        Map.of());
        String second = digest
                .of(new LabelledRequest(cba, Map.of("k", new LinkedHashSet<>(List.of(1, 2))), PageQuery.first(5)),
                        Map.of());

        assertThat(first)
                .isEqualTo(second)
                .isEqualTo(sha256("{\"labels\":[\"a\",\"b\",\"c\"],\"nested\":{\"k\":[1,2]}}"));
    }

    @Test
    void ignoresJacksonAnnotationsAndInclusion() {
        LocalDate since = LocalDate.of(2026, 11, 4);

        String first = digest.of(new HiddenRequest("a", "b", Kind.FIRST, since, PageQuery.first(5)), Map.of());

        // @JsonIgnore, @JsonProperty and an enum's @JsonProperty don't change what is hashed; java.time is ISO text.
        assertThat(first)
                .isEqualTo(sha256(
                        "{\"author\":\"b\",\"kind\":\"FIRST\",\"since\":\"2026-11-04\",\"tenant\":\"a\"}"))
                .isNotEqualTo(digest
                        .of(new HiddenRequest("other", "b", Kind.FIRST, since, PageQuery.first(5)), Map.of()));
        // An empty string and null are different filters.
        assertThat(digest.of(new HiddenRequest("a", "", null, null, PageQuery.first(5)), Map.of()))
                .isNotEqualTo(digest.of(new HiddenRequest("a", null, null, null, PageQuery.first(5)), Map.of()));
    }

    @Test
    void everyOtherComponentBindsTheDigest() {
        // A component the plugin fills from a header or the request context is a filter like any other, so a cursor
        // can't be replayed by another principal or tenant.
        String first = digest.of(new TenantRequest("a", "p", PageQuery.first(5)), Map.of());

        assertThat(first)
                .isEqualTo(sha256("{\"principal\":\"p\",\"tenant\":\"a\"}"))
                .isNotEqualTo(digest.of(new TenantRequest("b", "p", PageQuery.first(5)), Map.of()))
                .isNotEqualTo(digest.of(new TenantRequest("a", "q", PageQuery.first(5)), Map.of()));
    }

    private static String sha256(String text) {
        try {
            return HexFormat
                    .of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    record SearchRequest(@Nullable String author, PageQuery page) {
    }

    record TaggedRequest(Map<String, String> tags, String genre, PageQuery page) {
    }

    record VersionedRequest(String author, @Nullable VersionCondition<Long> version,
            @Nullable VersionCondition<String> unless, VersionCondition.Version<Long> exact, PageQuery page) {
    }

    record LabelledRequest(Set<String> labels, Map<String, Set<Integer>> nested, PageQuery page) {
    }

    enum Kind {
        @JsonProperty("first-kind")
        FIRST
    }

    record HiddenRequest(@JsonIgnore @Nullable String tenant, @JsonProperty("writer") @Nullable String author,
            @Nullable Kind kind, @Nullable LocalDate since, PageQuery page) {
    }

    record TenantRequest(@Nullable String tenant, @Nullable String principal, PageQuery page) {
    }
}
