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

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonNaming;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.internal.paging.PagingSettings;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.core.paging.PageQuery;

import static org.assertj.core.api.Assertions.assertThat;

class WireNamesTest {

    private static final JsonMapper SNAKE_CASE = JsonMapper
            .builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    private final WireNames shelf = names(RestBindings
            .builder()
            .put("/shelves/{shelfId}", new StockShelfOperation())
            .path("shelfId", ShelfRequest::id)
            .query("dry_run", ShelfRequest::dryRun)
            .header("X-Tags", ShelfRequest::tags)
            .cookie("sess", ShelfRequest::session)
            .context(ShelfRequest::tenantId, context -> "t")
            .from(ShelfRequest::origin, request -> "o")
            .build(), PagingSettings.defaults());

    @Test
    void nestedBodyPathUsesJsonNamesPerType() {
        assertThat(shelf.rename("author.countryCode")).isEqualTo("author.country_code");
        assertThat(shelf.rename("translator.countryCode")).isEqualTo("translator.country-code");
        assertThat(shelf.rename("leadAuthor")).isEqualTo("lead_author");
    }

    @Test
    void listIndexIsKept() {
        assertThat(shelf.rename("items[2].skuCode")).isEqualTo("items[2].sku_code");
        assertThat(shelf.rename("labelNames[0]")).isEqualTo("label_names[0]");
        assertThat(shelf.rename("grid[1][0].skuCode")).isEqualTo("grid[1][0].sku_code");
    }

    @Test
    void setElementAndOptionalAreUnwrapped() {
        assertThat(shelf.rename("itemSet.skuCode")).isEqualTo("item_set.sku_code");
        assertThat(shelf.rename("maybeAuthor.countryCode")).isEqualTo("maybe_author.country_code");
        assertThat(shelf.rename("itemArray[3].skuCode")).isEqualTo("item_array[3].sku_code");
    }

    @Test
    void mapValuePropertyUsesTheValuesJsonName() {
        assertThat(shelf.rename("authorsByRank.countryCode")).isEqualTo("authors_by_rank.country_code");
        assertThat(shelf.rename("authorsByRank")).isEqualTo("authors_by_rank");
        // A map of lists: core writes the value's item index after the map.
        assertThat(shelf.rename("itemsByAisle[1].skuCode")).isEqualTo("items_by_aisle[1].sku_code");
    }

    @Test
    void mapKeyNeverAppearsInARestPath() {
        // Spring's form writes a key as it is, and a key may hold anything, path-like text included: the path is cut
        // at the first map.
        assertThat(WireNames.body(SNAKE_CASE, ShelfRequest.class, "labels[a.b]].x")).isEqualTo("labels");
        assertThat(WireNames.body(SNAKE_CASE, ShelfRequest.class, "labels[k]")).isEqualTo("labels");
        assertThat(WireNames.body(SNAKE_CASE, ShelfRequest.class, "itemsByAisle[a[0]][0].skuCode"))
                .isEqualTo("items_by_aisle");
        assertThat(WireNames.body(SNAKE_CASE, ShelfRequest.class, "itemsByAisle[k][7].skuCode"))
                .isEqualTo("items_by_aisle");
        assertThat(WireNames.body(SNAKE_CASE, ShelfRequest.class, "labels[" + "a][".repeat(500) + "].x"))
                .isEqualTo("labels");
        // Core never writes a key, so a bracket after a map that holds no list cuts the path there.
        assertThat(shelf.rename("labels[a.b].x")).isEqualTo("labels");
    }

    @Test
    void nestedMapKeyIsNeverReadAsPath() {
        // An application Validator's field through two maps, whose inner key is q].items[7.
        String field = WireNames.body(SNAKE_CASE, Nest.class, "m[a].byKey[q].items[7].skuCode");

        assertThat(field).isEqualTo("m").doesNotContain("7").doesNotContain("items");
    }

    @Test
    void theNameCacheHoldsNoMapper() throws ClassNotFoundException {
        // The cache is the value of a weak map keyed by the mapper: a value that reached its key would keep it alive.
        Class<?> namer = Class.forName(WireNames.class.getName() + "$Namer");
        Class<?> naming = Class.forName(WireNames.class.getName() + "$Naming");

        assertThat(namer.getDeclaredFields())
                .isNotEmpty()
                .allSatisfy(field -> {
                    // JsonMapper is an ObjectMapper.
                    assertThat(ObjectMapper.class.isAssignableFrom(field.getType())).as(field.getName()).isFalse();
                    assertThat(naming.isAssignableFrom(field.getType())).as(field.getName()).isFalse();
                });
    }

    @Test
    void bodyInCoresFormKeepsAMapOfListsIndex() {
        // Core's form has no keys: a bracket after a map of lists is the value's item index.
        assertThat(WireNames.body(SNAKE_CASE, ShelfRequest.class, "itemsByAisle[1].skuCode", false))
                .isEqualTo("items_by_aisle[1].sku_code");
        assertThat(WireNames.body(SNAKE_CASE, ShelfRequest.class, "authorsByRank.countryCode", false))
                .isEqualTo("authors_by_rank.country_code");
        assertThat(WireNames.body(SNAKE_CASE, ShelfRequest.class, "labels[k].x", false)).isEqualTo("labels");
    }

    @Test
    void aMapperThatIsGoneTakesItsNamesWithIt() throws InterruptedException {
        JsonMapper mapper = JsonMapper.builder().build();
        assertThat(WireNames.body(mapper, Item.class, "skuCode")).isEqualTo("skuCode");
        WeakReference<JsonMapper> gone = new WeakReference<>(mapper);
        mapper = null;

        // Bounded: a collector that clears weak references on an explicit GC does so within a few rounds.
        for (int round = 0; round < 50 && gone.get() != null; round++) {
            System.gc();
            Thread.sleep(20);
        }
        assertThat(gone.get()).isNull();
    }

    @Test
    void bodyFormKeepsListIndexesAndDropsASetsEmptyBracket() {
        assertThat(WireNames.body(SNAKE_CASE, ShelfRequest.class, "items[2].skuCode")).isEqualTo("items[2].sku_code");
        assertThat(WireNames.body(SNAKE_CASE, ShelfRequest.class, "itemSet[].skuCode"))
                .isEqualTo("item_set.sku_code");
        assertThat(WireNames.body(SNAKE_CASE, ShelfRequest.class, "leadAuthor")).isEqualTo("lead_author");
        assertThat(WireNames.body(SNAKE_CASE, ShelfRequest.class, "")).isEmpty();
        assertThat(WireNames.body(SNAKE_CASE, ShelfRequest.class, "unknown.name")).isEqualTo("unknown.name");
        // An unknown first property is cut before a bracket, which may hold a key.
        assertThat(WireNames.body(SNAKE_CASE, ShelfRequest.class, "unknown[secret].name")).isEqualTo("unknown");
        // A map or list body: its items have no name, and a key is never written.
        assertThat(WireNames.body(SNAKE_CASE, Map.class, "[secret].x")).isEmpty();
    }

    @Test
    void unnamedPartCutsThePath() {
        // Jackson reads Opaque with its own deserializer, so it can't name what is inside.
        assertThat(shelf.rename("opaqueBook.title")).isEqualTo("opaque_book");
        assertThat(shelf.rename("items[x].skuCode")).isEqualTo("items");
        assertThat(shelf.rename("items[2].unknown")).isEqualTo("items[2]");
        assertThat(shelf.rename("leadAuthor.countryCode.more")).isEqualTo("lead_author.country_code");
        assertThat(shelf.rename("items[2")).isEqualTo("items");
        assertThat(shelf.rename("items[2]x")).isEqualTo("items[2]");
        assertThat(shelf.rename("tags[x]")).isEqualTo("X-Tags");
    }

    @Test
    void sourceNamesAreTheDeclaredNames() {
        assertThat(shelf.rename("id")).isEqualTo("shelfId");
        assertThat(shelf.rename("dryRun")).isEqualTo("dry_run");
        assertThat(shelf.rename("tags[1]")).isEqualTo("X-Tags[1]");
        assertThat(shelf.rename("session")).isEqualTo("sess");
        // A value converted from text has no parts on the wire.
        assertThat(shelf.rename("session.value")).isEqualTo("sess");
    }

    @Test
    void contextComponentKeepsItsName() {
        assertThat(shelf.rename("tenantId")).isEqualTo("tenantId");
        assertThat(shelf.rename("tenantId.value")).isEqualTo("tenantId");
        assertThat(shelf.rename("origin")).isEqualTo("origin");
    }

    @Test
    void bodyComponentPathStartsAtTheBody() {
        WireNames patch = names(RestBindings
                .builder()
                .put("/books/{id}", new PatchBookOperation())
                .body(PatchBookRequest::changes)
                .build(), PagingSettings.defaults());

        assertThat(patch.rename("changes.newTitle")).isEqualTo("new_title");
        assertThat(patch.rename("changes.author.countryCode")).isEqualTo("author.country_code");
        assertThat(patch.rename("changes")).isEqualTo("changes");
        assertThat(patch.rename("changes.unknown")).isEqualTo("changes");
        assertThat(patch.rename("id")).isEqualTo("id");
    }

    @Test
    void queryRecordPathUsesTheParameterName() {
        WireNames search = names(RestBindings
                .builder()
                .get("/books", new SearchBooksOperation())
                .query(SearchRequest::range)
                .build(), PagingSettings.defaults());

        assertThat(search.rename("range.minYear")).isEqualTo("minYear");
        assertThat(search.rename("range")).isEqualTo("range");
        assertThat(search.rename("range.unknown")).isEqualTo("range");
    }

    @Test
    void pagingPathUsesTheParameterNames() {
        RestBindings bindings = RestBindings.builder().get("/books", new PagedBooksOperation()).build();
        WireNames defaults = names(bindings, PagingSettings.defaults());
        WireNames renamed = names(bindings, new PagingSettings("after", "skip", "per_page", "order", 1000, 20, 100));

        assertThat(defaults.rename("page.size")).isEqualTo("size");
        assertThat(renamed.rename("page.size")).isEqualTo("per_page");
        assertThat(renamed.rename("page.cursor")).isEqualTo("after");
        assertThat(renamed.rename("page.offset")).isEqualTo("skip");
        assertThat(renamed.rename("page.sort[0].property")).isEqualTo("order");
        assertThat(renamed.rename("page")).isEqualTo("page");
        assertThat(renamed.rename("page.other")).isEqualTo("page");
        assertThat(renamed.rename("authorName")).isEqualTo("authorName");
    }

    @Test
    void pageQueryInputPathUsesTheParameterNames() {
        WireNames page = names(RestBindings.builder().get("/books", new PageOnlyOperation()).build(),
                new PagingSettings("after", "skip", "per_page", "order", 1000, 20, 100));

        assertThat(page.rename("size")).isEqualTo("per_page");
        assertThat(page.rename("sort[0]")).isEqualTo("order");
        assertThat(page.rename("")).isEmpty();
    }

    @Test
    void unknownPathIsUnchanged() {
        assertThat(shelf.rename("isbn")).isEqualTo("isbn");
        assertThat(shelf.rename("isbn[0].x")).isEqualTo("isbn[0].x");
        assertThat(shelf.rename("")).isEmpty();
        assertThat(shelf.rename("If-Match")).isEqualTo("If-Match");
    }

    @Test
    void violationsAreRenamedInOrder() {
        List<Violation> renamed = shelf
                .rename(List
                        .of(new Violation("leadAuthor", "must not be null"),
                                new Violation("isbn", "must not be blank")));

        assertThat(renamed)
                .containsExactly(new Violation("lead_author", "must not be null"),
                        new Violation("isbn", "must not be blank"));
        assertThat(shelf.rename(List.of())).isEmpty();
    }

    @Test
    void beanBodyUsesItsJsonNames() {
        assertThat(shelf.rename("beanAuthor.countryCode")).isEqualTo("bean_author.country_code");
        assertThat(WireNames.body(SNAKE_CASE, AuthorBean.class, "countryCode")).isEqualTo("country_code");
    }

    @Test
    void wholeBodyInputReadByItsOwnDeserializerKeepsComponentNames() {
        WireNames whole = names(RestBindings.builder().post("/opaque", new OpaqueOperation()).build(),
                PagingSettings.defaults());

        assertThat(whole.rename("title")).isEqualTo("title");
        assertThat(whole.rename("title.more")).isEqualTo("title");
    }

    private static WireNames names(RestBindings bindings, PagingSettings paging) {
        RouteDefinition route = new BindingValidator(paging)
                .validate(RestBindingsAccess.routes(bindings), List.of())
                .getFirst();
        return WireNames.of(route, SNAKE_CASE, paging);
    }

    record Author(String countryCode) {
    }

    @JsonNaming(PropertyNamingStrategies.KebabCaseStrategy.class)
    record KebabAuthor(String countryCode) {
    }

    record Item(String skuCode) {
    }

    record Label(String x) {
    }

    record Wrapper(@Nullable List<Item> items, @Nullable Map<String, Item> byKey) {
    }

    record Nest(@Nullable Map<String, Wrapper> m) {
    }

    /** Read with its own deserializer. */
    @JsonDeserialize(using = OpaqueDeserializer.class)
    record Opaque(String title) {
    }

    static final class OpaqueDeserializer extends ValueDeserializer<Opaque> {
        @Override
        public Opaque deserialize(JsonParser parser, DeserializationContext context) {
            return new Opaque(parser.getValueAsString());
        }
    }

    /** A bean read through its setter. */
    static final class AuthorBean {

        private @Nullable String countryCode;

        public @Nullable String getCountryCode() {
            return countryCode;
        }

        public void setCountryCode(@Nullable String countryCode) {
            this.countryCode = countryCode;
        }
    }

    record Session(String value) {

        static Session valueOf(String value) {
            return new Session(value);
        }
    }

    record ShelfRequest(long id, @Nullable Boolean dryRun, @Nullable List<String> tags, @Nullable Session session,
            String tenantId, String origin, Author leadAuthor, @Nullable Author author,
            @Nullable KebabAuthor translator, @Nullable List<Item> items, @Nullable List<String> labelNames,
            @Nullable List<List<Item>> grid, @Nullable Set<Item> itemSet, @Nullable Optional<Author> maybeAuthor,
            Item @Nullable [] itemArray, @Nullable Map<String, Author> authorsByRank,
            @Nullable Map<String, List<Item>> itemsByAisle, @Nullable Map<String, Label> labels,
            @Nullable Opaque opaqueBook, @Nullable AuthorBean beanAuthor) {
    }

    record BookChanges(String newTitle, @Nullable Author author) {
    }

    record PatchBookRequest(long id, BookChanges changes) {
    }

    record YearRange(@Nullable Integer minYear) {
    }

    record SearchRequest(@Nullable YearRange range) {
    }

    record PagedRequest(@Nullable String authorName, PageQuery page) {
    }

    static final class StockShelfOperation implements CommandOperation<ShelfRequest, String> {
        @Override
        public String execute(ShelfRequest input) {
            return "stocked";
        }
    }

    static final class PatchBookOperation implements CommandOperation<PatchBookRequest, String> {
        @Override
        public String execute(PatchBookRequest input) {
            return "patched";
        }
    }

    static final class SearchBooksOperation implements QueryOperation<SearchRequest, String> {
        @Override
        public String execute(SearchRequest input) {
            return "found";
        }
    }

    static final class PagedBooksOperation implements QueryOperation<PagedRequest, String> {
        @Override
        public String execute(PagedRequest input) {
            return "page";
        }
    }

    static final class PageOnlyOperation implements QueryOperation<PageQuery, String> {
        @Override
        public String execute(PageQuery input) {
            return "page";
        }
    }

    static final class OpaqueOperation implements CommandOperation<Opaque, String> {
        @Override
        public String execute(Opaque input) {
            return "read";
        }
    }
}
