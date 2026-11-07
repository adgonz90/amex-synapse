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

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.internal.binding.BindingValidator;
import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.core.conditional.VersionCondition;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;
import io.americanexpress.synapse.core.paging.SortOrder;
import io.americanexpress.synapse.core.paging.SortOrder.Direction;

import static org.assertj.core.api.Assertions.assertThat;

class InputDigestTest {

    private final InputDigest digest = new InputDigest();

    private final RouteDefinition search = route(
            RestBindings.builder().get("/books", new SearchOperation()).sortable("title").build());

    @Test
    void ignoresThePageQueryComponent() {
        String first = digest.of(search, new SearchRequest("a", PageQuery.first(10)));
        String other = digest
                .of(search, new SearchRequest("a",
                        new PageQuery("token", null, 50, List.of(new SortOrder("title", Direction.DESC)))));

        assertThat(first).isEqualTo(other).matches("[0-9a-f]{64}");
        // The record is written as a JSON object keyed by component name, without the PageQuery component.
        assertThat(first).isEqualTo(sha256("{\"author\":\"a\"}"));
    }

    @Test
    void changesWithFilters() {
        assertThat(digest.of(search, new SearchRequest("a", PageQuery.first(10))))
                .isNotEqualTo(digest.of(search, new SearchRequest("b", PageQuery.first(10))))
                .isNotEqualTo(digest.of(search, new SearchRequest(null, PageQuery.first(10))));
    }

    @Test
    void isStableForMapComponents() {
        RouteDefinition tagged = route(RestBindings.builder().post("/books/search", new TaggedOperation()).build());
        Map<String, String> ab = new LinkedHashMap<>();
        ab.put("a", "1");
        ab.put("b", "2");
        Map<String, String> ba = new LinkedHashMap<>();
        ba.put("b", "2");
        ba.put("a", "1");

        String first = digest.of(tagged, new TaggedRequest(ab, "z", PageQuery.first(10)));

        assertThat(first).isEqualTo(digest.of(tagged, new TaggedRequest(ba, "z", PageQuery.first(10))));
        // Components are ordered by name too, whatever their declaration order.
        assertThat(first).isEqualTo(sha256("{\"genre\":\"z\",\"tags\":{\"a\":\"1\",\"b\":\"2\"}}"));
    }

    @Test
    void excludesPreconditionHeaders() {
        RouteDefinition versioned = route(RestBindings
                .builder()
                .post("/books/search", new VersionedOperation())
                .header("If-Match", VersionedRequest::version)
                .header("If-None-Match", VersionedRequest::unless)
                .build());

        String first = digest
                .of(versioned, new VersionedRequest("a", VersionCondition.of(1L), VersionCondition.of("x"),
                        PageQuery.first(10)));

        assertThat(first)
                .isEqualTo(digest
                        .of(versioned,
                                new VersionedRequest("a", VersionCondition.of(2L), VersionCondition.any(),
                                        PageQuery.first(10))))
                .isEqualTo(sha256("{\"author\":\"a\"}"))
                .isNotEqualTo(digest
                        .of(versioned, new VersionedRequest("b", VersionCondition.of(1L), VersionCondition.of("x"),
                                PageQuery.first(10))));
    }

    @Test
    void pageQueryAsTheWholeInputIsTheDigestOfAnEmptyObject() {
        RouteDefinition all = route(RestBindings.builder().get("/all", new AllOperation()).build());

        assertThat(digest.of(all, PageQuery.first(10)))
                .isEqualTo(digest.of(all, new PageQuery("token", null, 5, List.of())))
                .isEqualTo(sha256("{}"));
        assertThat(digest.of(all, null)).isEqualTo(sha256("{}"));
    }

    @Test
    void aVersionedRouteBindsTheDigestToItsVersion() {
        SearchOperation operation = new SearchOperation();
        List<RouteDefinition> routes = new BindingValidator(PagingSettings.defaults())
                .validate(RestBindings
                        .builder()
                        .get("/books", operation)
                        .version("1")
                        .get("/books", operation)
                        .version("2")
                        .post("/books/search", operation)
                        .version("1.0")
                        .build()
                        .routes(), List.of());
        SearchRequest input = new SearchRequest("a", PageQuery.first(10));

        String v1 = digest.of(routes.get(0), input);
        // The version is written by its parsed value, under a key no component name can take.
        assertThat(v1)
                .isEqualTo(sha256("{\"@version\":\"1.0.0\",\"author\":\"a\"}"))
                .isNotEqualTo(digest.of(search, input))
                .isNotEqualTo(digest.of(routes.get(1), input));
        // Versions that parse equal share the scope, as two routes of one operation do.
        assertThat(digest.of(routes.get(2), input)).isEqualTo(v1);
    }

    @Test
    void setsAreWrittenSortedWhateverTheirIterationOrder() {
        RouteDefinition labelled = route(RestBindings.builder().post("/books/search", new LabelledOperation()).build());
        Set<String> bac = new LinkedHashSet<>(List.of("b", "a", "c"));
        Set<String> cba = new LinkedHashSet<>(List.of("c", "b", "a"));
        assertThat(List.copyOf(bac)).isNotEqualTo(List.copyOf(cba));

        String first = digest
                .of(labelled, new LabelledRequest(bac, Map.of("k", new LinkedHashSet<>(List.of(2, 1))),
                        PageQuery.first(5)));
        String second = digest
                .of(labelled, new LabelledRequest(cba, Map.of("k", new LinkedHashSet<>(List.of(1, 2))),
                        PageQuery.first(5)));

        assertThat(first)
                .isEqualTo(second)
                .isEqualTo(sha256("{\"labels\":[\"a\",\"b\",\"c\"],\"nested\":{\"k\":[1,2]}}"));
    }

    @Test
    void ignoresJacksonAnnotationsAndInclusion() {
        RouteDefinition hidden = route(RestBindings.builder().post("/books/search", new HiddenOperation()).build());
        LocalDate since = LocalDate.of(2026, 11, 4);

        String first = digest.of(hidden, new HiddenRequest("a", "b", Kind.FIRST, since, PageQuery.first(5)));

        // @JsonIgnore, @JsonProperty and an enum's @JsonProperty don't change what is hashed; java.time is ISO text.
        assertThat(first)
                .isEqualTo(sha256(
                        "{\"author\":\"b\",\"kind\":\"FIRST\",\"since\":\"2026-11-04\",\"tenant\":\"a\"}"))
                .isNotEqualTo(digest
                        .of(hidden, new HiddenRequest("other", "b", Kind.FIRST, since,
                                PageQuery.first(5))));
        // An empty string and null are different filters.
        assertThat(digest.of(hidden, new HiddenRequest("a", "", null, null, PageQuery.first(5))))
                .isNotEqualTo(digest.of(hidden, new HiddenRequest("a", null, null, null, PageQuery.first(5))));
    }

    @Test
    void fromAndContextComponentsBindTheDigest() {
        RouteDefinition tenanted = route(RestBindings
                .builder()
                .get("/books", new TenantOperation())
                .from(TenantRequest::tenant, request -> request.headers().firstHeader("X-Tenant"))
                .context(TenantRequest::principal, context -> "anyone")
                .build());

        String first = digest.of(tenanted, new TenantRequest("a", "p", PageQuery.first(5)));

        assertThat(first)
                .isEqualTo(sha256("{\"principal\":\"p\",\"tenant\":\"a\"}"))
                .isNotEqualTo(digest.of(tenanted, new TenantRequest("b", "p", PageQuery.first(5))))
                .isNotEqualTo(digest.of(tenanted, new TenantRequest("a", "q", PageQuery.first(5))));
    }

    private static RouteDefinition route(RestBindings bindings) {
        return new BindingValidator(PagingSettings.defaults()).validate(bindings.routes(), List.of()).getFirst();
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

    static class SearchOperation implements QueryOperation<SearchRequest, PageResult<String>> {
        @Override
        public PageResult<String> execute(SearchRequest input) {
            return PageResult.of(List.of(), false);
        }
    }

    record TaggedRequest(Map<String, String> tags, String genre, PageQuery page) {
    }

    static class TaggedOperation implements CommandOperation<TaggedRequest, PageResult<String>> {
        @Override
        public PageResult<String> execute(TaggedRequest input) {
            return PageResult.of(List.of(), false);
        }
    }

    record VersionedRequest(String author, @Nullable VersionCondition<Long> version,
            @Nullable VersionCondition<String> unless, PageQuery page) {
    }

    static class VersionedOperation implements CommandOperation<VersionedRequest, PageResult<String>> {
        @Override
        public PageResult<String> execute(VersionedRequest input) {
            return PageResult.of(List.of(), false);
        }
    }

    record LabelledRequest(Set<String> labels, Map<String, Set<Integer>> nested, PageQuery page) {
    }

    static class LabelledOperation implements CommandOperation<LabelledRequest, PageResult<String>> {
        @Override
        public PageResult<String> execute(LabelledRequest input) {
            return PageResult.of(List.of(), false);
        }
    }

    enum Kind {
        @JsonProperty("first-kind")
        FIRST
    }

    record HiddenRequest(@JsonIgnore @Nullable String tenant, @JsonProperty("writer") @Nullable String author,
            @Nullable Kind kind, @Nullable LocalDate since, PageQuery page) {
    }

    static class HiddenOperation implements CommandOperation<HiddenRequest, PageResult<String>> {
        @Override
        public PageResult<String> execute(HiddenRequest input) {
            return PageResult.of(List.of(), false);
        }
    }

    record TenantRequest(@Nullable String tenant, @Nullable String principal, PageQuery page) {
    }

    static class TenantOperation implements QueryOperation<TenantRequest, PageResult<String>> {
        @Override
        public PageResult<String> execute(TenantRequest input) {
            return PageResult.of(List.of(), false);
        }
    }

    static class AllOperation implements QueryOperation<PageQuery, PageResult<String>> {
        @Override
        public PageResult<String> execute(PageQuery input) {
            return PageResult.of(List.of(), false);
        }
    }
}
