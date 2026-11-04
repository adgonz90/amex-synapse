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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.internal.binding.BindingValidator;
import io.americanexpress.synapse.api.rest.internal.binding.RequestBinder;
import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PagedBooksOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PagedBooksRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.SortKey;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.SortOrder;
import io.americanexpress.synapse.core.paging.SortOrder.Direction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class PageQueryReaderTest {

    private static final SortKeys KEYS = SortKeys.of(SortKey.class);

    private final PageQueryReader reader = new PageQueryReader(PagingSettings.defaults());

    @Test
    void defaultsSizeAndLeavesSortEmpty() {
        List<Violation> violations = new ArrayList<>();

        assertThat(reader.read(query(), KEYS, violations)).isEqualTo(new PageQuery(null, null, 20, List.of()));
        assertThat(violations).isEmpty();
    }

    @Test
    void readsCursorOffsetSizeAndSort() {
        List<Violation> violations = new ArrayList<>();

        assertThat(reader.read(query("size", "5", "offset", "10", "sort", "title,-year"), KEYS, violations))
                .isEqualTo(new PageQuery(null, 10L, 5,
                        List
                                .of(new SortOrder("TITLE", Direction.ASC),
                                        new SortOrder("PUBLISHED_YEAR", Direction.DESC))));
        assertThat(violations).isEmpty();
        List<Violation> cursorViolations = new ArrayList<>();
        assertThat(reader.read(query("cursor", "abc"), KEYS, cursorViolations))
                .isEqualTo(new PageQuery("abc", null, 20, List.of()));
        assertThat(cursorViolations).isEmpty();
    }

    @Test
    void sizeOverTheCapIsAWireNameViolation() {
        assertViolations(query("size", "101"), KEYS, new Violation("size", "must be less than or equal to 100"));
        assertViolations(query("size", "0"), KEYS, new Violation("size", "must be greater than or equal to 1"));
        assertViolations(query("size", "-1"), KEYS, new Violation("size", "must be greater than or equal to 1"));
        assertViolations(query("size", "abc"), KEYS, new Violation("size", "is not a valid value"));
        assertViolations(query("size", ""), KEYS, new Violation("size", "is not a valid value"));
        assertViolations(query("size", "99999999999999999999"), KEYS, new Violation("size", "is not a valid value"));
        // Within long but beyond int: still a cap violation, never a wrapped int.
        assertViolations(query("size", "4294967297"), KEYS, new Violation("size", "must be less than or equal to 100"));
        List<Violation> violations = new ArrayList<>();
        assertThat(reader.read(query("size", "100"), KEYS, violations).size()).isEqualTo(100);
        assertThat(violations).isEmpty();
    }

    @Test
    void offsetCapCannotBeBypassed() {
        Violation overCap = new Violation("offset", "must be less than or equal to 1000");
        Violation invalid = new Violation("offset", "is not a valid value");
        Violation repeated = new Violation("offset", "must have a single value");
        assertViolations(query("offset", "1001"), KEYS, overCap);
        assertViolations(query("offset", "+1001"), KEYS, overCap);
        assertViolations(query("offset", "99999999999999999999"), KEYS, invalid);
        assertViolations(query("offset", "1", "offset", "2000"), KEYS, repeated);
        assertViolations(query("offset", "-1"), KEYS, new Violation("offset", "must be greater than or equal to 0"));
        assertViolations(query("offset", ""), KEYS, invalid);

        // The parameter renamed: the configured name is the only one read, and violations carry it.
        PageQueryReader renamed = new PageQueryReader(skipSettings());
        Violation skipOverCap = new Violation("skip", "must be less than or equal to 1000");
        assertViolations(renamed, query("skip", "1001"), KEYS, skipOverCap);
        assertViolations(renamed, query("skip", "1001", "offset", "1001"), KEYS, skipOverCap);
        assertViolations(renamed, query("skip", "+1001"), KEYS, skipOverCap);
        assertViolations(renamed, query("skip", "99999999999999999999"), KEYS,
                new Violation("skip", "is not a valid value"));
        assertViolations(renamed, query("skip", "1", "skip", "2000"), KEYS,
                new Violation("skip", "must have a single value"));
        List<Violation> ignored = new ArrayList<>();
        assertThat(renamed.read(query("offset", "1001"), KEYS, ignored).offset()).isNull();
        assertThat(ignored).isEmpty();

        // The same values from a request on a POST route, through the binder (+ is %2B in a query string).
        assertBinderViolations(PagingSettings.defaults(), "offset=1001", overCap);
        assertBinderViolations(PagingSettings.defaults(), "offset=%2B1001", overCap);
        assertBinderViolations(PagingSettings.defaults(), "offset=+1001", invalid);
        assertBinderViolations(PagingSettings.defaults(), "offset=99999999999999999999", invalid);
        assertBinderViolations(PagingSettings.defaults(), "offset=1&offset=2000", repeated);
        assertBinderViolations(skipSettings(), "skip=1001", skipOverCap);
        assertBinderViolations(skipSettings(), "skip=1001&offset=1001", skipOverCap);
        assertBinderViolations(skipSettings(), "skip=%2B1001", skipOverCap);
        // Percent-encoded names and digits decode before they are read; names are case-sensitive.
        assertBinderViolations(PagingSettings.defaults(), "%6Fffset=1001", overCap);
        assertBinderViolations(PagingSettings.defaults(), "offset=%31001", overCap);
        assertBinderViolations(PagingSettings.defaults(), "offset=%D9%A1%D9%A0%D9%A0%D9%A1", invalid);
        assertThat(postBinder(PagingSettings.defaults()).bind("Offset=1001").offset()).isNull();

        // The cap is inclusive.
        List<Violation> violations = new ArrayList<>();
        assertThat(reader.read(query("offset", "1000"), KEYS, violations).offset()).isEqualTo(1000L);
        assertThat(violations).isEmpty();
        assertThat(renamed.read(query("skip", "1000"), KEYS, violations).offset()).isEqualTo(1000L);
        assertThat(violations).isEmpty();
        assertThat(postBinder(PagingSettings.defaults()).bind("offset=1000").offset()).isEqualTo(1000L);
    }

    @Test
    void integersAreAsciiDigitsWithAnOptionalSign() {
        // Arabic-Indic, fullwidth and Devanagari digits: Long.parseLong would accept them.
        for (String digits : List.of("١٠", "１０", "१०", "1٠", "+١")) {
            assertViolations(query("offset", digits), KEYS, new Violation("offset", "is not a valid value"));
            assertViolations(query("size", digits), KEYS, new Violation("size", "is not a valid value"));
        }
        for (String malformed : List.of("+", "-", "1e3", "0x10", " 1", "1 ", "1_000", "++1", "+-1")) {
            assertViolations(query("offset", malformed), KEYS, new Violation("offset", "is not a valid value"));
        }
        assertViolations(query("size", "-1"), KEYS, new Violation("size", "must be greater than or equal to 1"));
        assertViolations(query("offset", "-1"), KEYS, new Violation("offset", "must be greater than or equal to 0"));
        assertViolations(query("size", "+101"), KEYS, new Violation("size", "must be less than or equal to 100"));

        List<Violation> violations = new ArrayList<>();
        assertThat(reader.read(query("offset", "007", "size", "007"), KEYS, violations))
                .isEqualTo(new PageQuery(null, 7L, 7, List.of()));
        assertThat(reader.read(query("offset", "+0", "size", "+5"), KEYS, violations))
                .isEqualTo(new PageQuery(null, 0L, 5, List.of()));
        assertThat(violations).isEmpty();
    }

    @Test
    void cursorWithOffsetIsAViolation() {
        assertViolations(query("cursor", "x", "offset", "0"), KEYS,
                new Violation("cursor", "must not be combined with offset"));
        assertViolations(query("cursor", ""), KEYS, new Violation("cursor", "is not a valid value"));
        assertViolations(query("cursor", "a", "cursor", "b"), KEYS,
                new Violation("cursor", "must have a single value"));
    }

    @Test
    void sortWithoutSortableIsAViolation() {
        assertViolations(query("sort", "title"), null, new Violation("sort", "is not supported by this route"));
        assertViolations(query("sort", ""), null, new Violation("sort", "is not supported by this route"));
        List<Violation> violations = new ArrayList<>();
        assertThat(reader.read(query("size", "5"), null, violations))
                .isEqualTo(new PageQuery(null, null, 5, List.of()));
        assertThat(violations).isEmpty();
    }

    @Test
    void unknownSortKeyIsAViolation() {
        Violation unknown = new Violation("sort", "has a key that is not allowed (allowed: title, year, authorName)");
        assertViolations(query("sort", "price"), KEYS, unknown);
        // Keys are case-sensitive, nothing is trimmed, and the operation's property name is not a wire name.
        assertViolations(query("sort", "Title"), KEYS, unknown);
        assertViolations(query("sort", " title"), KEYS, unknown);
        assertViolations(query("sort", "title "), KEYS, unknown);
        assertViolations(query("sort", "PUBLISHED_YEAR"), KEYS, unknown);
        assertViolations(query("sort", "--title"), KEYS, unknown);
        // Several unknown keys are one violation.
        assertViolations(query("sort", "price,isbn"), KEYS, unknown);
    }

    @Test
    void malformedOrRepeatedSortKeys() {
        Violation invalid = new Violation("sort", "is not a valid value");
        assertViolations(query("sort", "title,,year"), KEYS, invalid);
        assertViolations(query("sort", "-"), KEYS, invalid);
        assertViolations(query("sort", ""), KEYS, invalid);
        assertViolations(query("sort", "title,"), KEYS, invalid);
        assertViolations(query("sort", "title,-title"), KEYS, new Violation("sort", "names a key more than once"));
        assertViolations(query("sort", "year,year"), KEYS, new Violation("sort", "names a key more than once"));
        assertViolations(query("sort", "price,,title,title"), KEYS,
                new Violation("sort", "has a key that is not allowed (allowed: title, year, authorName)"),
                invalid, new Violation("sort", "names a key more than once"));
    }

    @Test
    void repeatedSortParametersAreJoined() {
        List<Violation> joined = new ArrayList<>();
        List<Violation> single = new ArrayList<>();

        PageQuery repeated = reader.read(query("sort", "title", "sort", "-year"), KEYS, joined);

        assertThat(repeated).isEqualTo(reader.read(query("sort", "title,-year"), KEYS, single));
        assertThat(repeated.sort())
                .containsExactly(new SortOrder("TITLE", Direction.ASC),
                        new SortOrder("PUBLISHED_YEAR", Direction.DESC));
        assertThat(joined).isEmpty();
        assertThat(single).isEmpty();
        assertViolations(query("sort", "title", "sort", "title"), KEYS,
                new Violation("sort", "names a key more than once"));
    }

    @Test
    void repeatedSizeIsAViolation() {
        assertViolations(query("size", "1", "size", "2"), KEYS, new Violation("size", "must have a single value"));
        assertViolations(query("size", "1", "size", "1"), KEYS, new Violation("size", "must have a single value"));
    }

    @Test
    void usesConfiguredParameterNames() {
        PageQueryReader renamed = new PageQueryReader(
                new PagingSettings("after", "skip", "page_size", "order", 500, 10, 50));

        assertViolations(renamed, query("page_size", "200"), KEYS,
                new Violation("page_size", "must be less than or equal to 50"));
        assertViolations(renamed, query("skip", "501"), KEYS,
                new Violation("skip", "must be less than or equal to 500"));
        assertViolations(renamed, query("after", "x", "skip", "1"), KEYS,
                new Violation("after", "must not be combined with offset"));
        assertViolations(renamed, query("order", "price"), KEYS,
                new Violation("order", "has a key that is not allowed (allowed: title, year, authorName)"));
        List<Violation> violations = new ArrayList<>();
        assertThat(renamed
                .read(query("size", "200", "offset", "5000", "cursor", "c", "sort", "price"), KEYS,
                        violations))
                .isEqualTo(new PageQuery(null, null, 10, List.of()));
        assertThat(violations).isEmpty();
        assertThat(renamed.read(query("page_size", "7", "order", "-authorName"), KEYS, violations))
                .isEqualTo(new PageQuery(null, null, 7, List.of(new SortOrder("AUTHOR_NAME", Direction.DESC))));
        assertThat(violations).isEmpty();
    }

    @Test
    void appendsToTheViolationsAlreadyCollected() {
        Violation earlier = new Violation("limit", "is not a valid value");
        List<Violation> violations = new ArrayList<>(List.of(earlier));

        reader.read(query("size", "0", "sort", "price", "cursor", "c", "offset", "x"), KEYS, violations);

        assertThat(violations)
                .containsExactly(earlier, new Violation("cursor", "must not be combined with offset"),
                        new Violation("offset", "is not a valid value"),
                        new Violation("size", "must be greater than or equal to 1"),
                        new Violation("sort", "has a key that is not allowed (allowed: title, year, authorName)"));
    }

    private void assertViolations(MultiValueMap<String, String> query, @Nullable SortKeys keys,
            Violation... expected) {
        assertViolations(reader, query, keys, expected);
    }

    private static void assertViolations(PageQueryReader reader, MultiValueMap<String, String> query,
            @Nullable SortKeys keys, Violation... expected) {
        List<Violation> violations = new ArrayList<>();
        reader.read(query, keys, violations);
        assertThat(violations).as("violations for %s", query).containsExactly(expected);
    }

    private static void assertBinderViolations(PagingSettings settings, String query, Violation... expected) {
        PostBinder binder = postBinder(settings);
        assertThatExceptionOfType(SynapseException.class)
                .as("binding ?%s", query)
                .isThrownBy(() -> binder.bind(query))
                .satisfies(e -> {
                    assertThat(e.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED);
                    assertThat(e.violations()).containsExactly(expected);
                });
    }

    private static PagingSettings skipSettings() {
        return new PagingSettings("cursor", "skip", "size", "sort", 1000, 20, 100);
    }

    private static PostBinder postBinder(PagingSettings settings) {
        RouteDefinition route = new BindingValidator(settings)
                .validate(RestBindings.builder().post("/books", new PagedBooksOperation()).build().routes(), List.of())
                .getFirst();
        return new PostBinder(new RequestBinder(JsonMapper.builder().build(),
                new DefaultFormattingConversionService(), settings), route);
    }

    private record PostBinder(RequestBinder binder, RouteDefinition route) {

        PageQuery bind(String query) {
            MockHttpServletRequest request = MockMvcRequestBuilders
                    .post(URI.create("/books?" + query))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"author\":\"a\"}".getBytes(StandardCharsets.UTF_8))
                    .buildRequest(new MockServletContext());
            request.setAttribute(RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of());
            Object input = Objects.requireNonNull(binder.bind(route, ServerRequest.create(request, List.of())));
            assertThat(input).isInstanceOf(PagedBooksRequest.class);
            return ((PagedBooksRequest) input).page();
        }
    }

    private static MultiValueMap<String, String> query(String... namesAndValues) {
        MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            query.add(namesAndValues[i], namesAndValues[i + 1]);
        }
        return query;
    }
}
