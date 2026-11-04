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
package io.americanexpress.synapse.api.rest.internal.route;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.RestConventions;
import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.api.rest.internal.error.BookErrors;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.Book;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.BookResult;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.CreateBookOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.CreateVersionedBookOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.DeleteBookOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.FindBookOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.FindBookResultOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.GetBookOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.GetBookResultOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.GetLabelsOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.GetQuotaOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.GetRenamedResultOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.GetTaggedBookOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.GetVersionedBookOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.Job;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.Labels;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.ListBooksOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.PagedBooksOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.PagedBooksRequest;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.PurgeBookOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.Quota;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.SubmitJobOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.TaggedBook;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.VersionedBook;
import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;

import static io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.BOOK;
import static io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.route;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class ResponseWriterTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final VersionedBook VERSIONED = new VersionedBook(7, "Dune", 7L);

    private final ResponseWriter writer = new ResponseWriter(MAPPER, new DefaultFormattingConversionService(),
            RouteFixtures.cursors(), RouteFixtures.etags());

    /** A written response. */
    private record Written(int status, MockHttpServletResponse response, String body) {

        @Nullable
        String header(String name) {
            return response.getHeader(name);
        }

        JsonNode json() {
            return MAPPER.readTree(body);
        }
    }

    @Test
    void statusRulesFollowTheSpec() throws Exception {
        Written value = write(route(RestBindings.builder().get("/books/{id}", new GetBookOperation()).build()), BOOK);
        assertThat(value.status()).isEqualTo(200);
        assertThat(value.response().getContentType()).isEqualTo("application/json");
        assertThat(value.json()).isEqualTo(json(BOOK));

        Written voidResult = write(
                route(RestBindings.builder().delete("/books/{id}", new PurgeBookOperation()).build()), null);
        assertThat(voidResult.status()).isEqualTo(204);
        assertThat(voidResult.body()).isEmpty();
        assertThat(voidResult.response().getContentType()).isNull();

        RouteDefinition delete = route(RestBindings.builder().delete("/books/{id}", new DeleteBookOperation()).build());
        Written deleted = write(delete, BOOK);
        assertThat(deleted.status()).isEqualTo(204);
        assertThat(deleted.body()).isEmpty();

        Written deletedWithBody = write(
                route(RestBindings.builder().delete("/books/{id}", new DeleteBookOperation()).status(200).build()),
                BOOK);
        assertThat(deletedWithBody.status()).isEqualTo(200);
        assertThat(deletedWithBody.json()).isEqualTo(json(BOOK));

        RouteDefinition submit = route(
                RestBindings
                        .builder()
                        .post("/jobs", new SubmitJobOperation())
                        .status(r -> r.queued() ? 202 : 200)
                        .build());
        assertThat(write(submit, new Job(9, true)).status()).isEqualTo(202);
        Written done = write(submit, new Job(9, false));
        assertThat(done.status()).isEqualTo(200);
        assertThat(done.json().get("queued").asBoolean()).isFalse();

        Written posted = write(route(RestBindings.builder().post("/books", new CreateBookOperation()).build()), BOOK);
        assertThat(posted.status()).isEqualTo(200);
        assertThat(posted.header("Location")).isNull();

        Written created = write(
                route(RestBindings.builder().post("/books", new CreateBookOperation()).status(201).build()), BOOK);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json()).isEqualTo(json(BOOK));
    }

    @Test
    void emptyListIsAnEmptyArray() throws Exception {
        Written written = write(route(RestBindings.builder().get("/books", new ListBooksOperation()).build()),
                List.of());

        assertThat(written.status()).isEqualTo(200);
        assertThat(written.body()).isEqualTo("[]");
        assertThat(written.response().getContentType()).isEqualTo("application/json");
    }

    @Test
    void createdSetsLocation() throws Exception {
        RouteDefinition create = route(
                RestBindings
                        .builder()
                        .post("/books", new CreateBookOperation())
                        .created(b -> "/books/" + b.id())
                        .build());

        Written written = write(create, BOOK);

        assertThat(written.status()).isEqualTo(201);
        assertThat(written.header("Location")).isEqualTo("/books/7");
        assertThat(written.json()).isEqualTo(json(BOOK));
    }

    @Test
    void acceptedSetsLocationAndStatusLink() throws Exception {
        RouteDefinition submit = route(
                RestBindings
                        .builder()
                        .post("/jobs", new SubmitJobOperation())
                        .accepted(j -> "/jobs/" + j.id())
                        .build());

        Written written = write(submit, new Job(9, true));

        assertThat(written.status()).isEqualTo(202);
        assertThat(written.header("Location")).isEqualTo("/jobs/9");
        assertThat(written.header("Link")).isEqualTo("</jobs/9>; rel=\"status\"");
        assertThat(written.json()).isEqualTo(json(new Job(9, true)));
    }

    @Test
    void locationWithLineBreakIsRefused() {
        RouteDefinition create = route(RestBindings
                .builder()
                .post("/books", new CreateBookOperation())
                .created(b -> "/books/" + b.title())
                .build());

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> write(create, new Book(1, "a\r\nSet-Cookie: x=y")))
                .withMessageContaining("Location")
                .withMessageNotContaining("Set-Cookie");
    }

    @Test
    void emptyOptionalUsesRouteNotFoundCode() throws Exception {
        RouteDefinition withCode = route(RestBindings
                .builder()
                .get("/books/{id}", new FindBookOperation())
                .notFound(BookErrors.BOOK_NOT_FOUND)
                .build());
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> write(withCode, Optional.empty()))
                .satisfies(e -> assertThat(e.details()).isEqualTo(BookErrors.BOOK_NOT_FOUND));

        RouteDefinition withoutCode = route(RestBindings.builder().get("/books/{id}", new FindBookOperation()).build());
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> write(withoutCode, Optional.empty()))
                .satisfies(e -> assertThat(e.details()).isEqualTo(SynapseErrors.NOT_FOUND));

        RouteDefinition emptyAs = route(
                RestBindings.builder().get("/books/{id}", new FindBookOperation()).emptyAs(204).build());
        Written empty = write(emptyAs, Optional.empty());
        assertThat(empty.status()).isEqualTo(204);
        assertThat(empty.body()).isEmpty();

        Written present = write(withoutCode, Optional.of(BOOK));
        assertThat(present.status()).isEqualTo(200);
        assertThat(present.json()).isEqualTo(json(BOOK));
    }

    @Test
    void headerMappedComponentIsRemovedFromBody() throws Exception {
        Written removed = write(route(RestBindings
                .builder()
                .get("/results/{id}", new GetBookResultOperation())
                .header("X-RateLimit-Remaining", BookResult::remaining)
                .build()), new BookResult(BOOK, 5));
        assertThat(removed.status()).isEqualTo(200);
        assertThat(removed.header("X-RateLimit-Remaining")).isEqualTo("5");
        assertThat(removed.json().has("remaining")).isFalse();
        assertThat(removed.json().get("book")).isEqualTo(json(BOOK));

        Written kept = write(route(RestBindings
                .builder()
                .get("/results/{id}", new GetBookResultOperation())
                .header("X-RateLimit-Remaining", BookResult::remaining)
                .alsoInBody()
                .build()), new BookResult(BOOK, 5));
        assertThat(kept.header("X-RateLimit-Remaining")).isEqualTo("5");
        assertThat(kept.json().get("remaining").asInt()).isEqualTo(5);

        Written optional = write(route(RestBindings
                .builder()
                .get("/results/{id}", new FindBookResultOperation())
                .header("X-RateLimit-Remaining", BookResult::remaining)
                .build()), Optional.of(new BookResult(BOOK, 3)));
        assertThat(optional.header("X-RateLimit-Remaining")).isEqualTo("3");
        assertThat(optional.json().has("remaining")).isFalse();
    }

    @Test
    void headerMappedComponentIsRemovedByItsJsonName() throws Exception {
        Written written = write(route(RestBindings
                .builder()
                .get("/results/{id}", new GetRenamedResultOperation())
                .header("X-RateLimit-Remaining", RouteFixtures.RenamedResult::remaining)
                .build()), new RouteFixtures.RenamedResult(BOOK, 5));

        assertThat(written.header("X-RateLimit-Remaining")).isEqualTo("5");
        assertThat(written.json().has("left")).isFalse();
        assertThat(written.json().has("book")).isTrue();
    }

    @Test
    void conventionResponseHeaderAppliesByType() throws Exception {
        RestConventions conventions = RestConventions
                .builder()
                .responseHeader("X-RateLimit-Remaining", BookResult.class, BookResult::remaining)
                .build();

        Written written = write(
                route(RestBindings.builder().get("/results/{id}", new GetBookResultOperation()).build(), conventions),
                new BookResult(BOOK, 5));

        assertThat(written.header("X-RateLimit-Remaining")).isEqualTo("5");
        assertThat(written.json().has("remaining")).isFalse();
    }

    @Test
    void bodyComponentBecomesTheBody() throws Exception {
        Written written = write(route(RestBindings
                .builder()
                .get("/results/{id}", new GetBookResultOperation())
                .body(BookResult::book)
                .header("X-RateLimit-Remaining", BookResult::remaining)
                .build()), new BookResult(BOOK, 5));

        assertThat(written.status()).isEqualTo(200);
        assertThat(written.json()).isEqualTo(json(BOOK));
        assertThat(written.header("X-RateLimit-Remaining")).isEqualTo("5");
    }

    @Test
    void nullHeaderComponentWritesNoHeader() throws Exception {
        RouteDefinition tagged = route(RestBindings
                .builder()
                .get("/tagged/{id}", new GetTaggedBookOperation())
                .header("X-Tag", TaggedBook::tag)
                .build());

        Written untagged = write(tagged, new TaggedBook(BOOK, null));
        assertThat(untagged.response().containsHeader("X-Tag")).isFalse();
        assertThat(untagged.json().has("tag")).isFalse();

        assertThat(write(tagged, new TaggedBook(BOOK, "v1")).header("X-Tag")).isEqualTo("v1");
    }

    @Test
    void nullBodyComponentIs204() throws Exception {
        RouteDefinition tagBody = route(RestBindings
                .builder()
                .get("/tagged/{id}", new GetTaggedBookOperation())
                .body(TaggedBook::tag)
                .build());

        Written empty = write(tagBody, new TaggedBook(BOOK, null));
        assertThat(empty.status()).isEqualTo(204);
        assertThat(empty.body()).isEmpty();
        assertThat(empty.response().getContentType()).isNull();

        Written present = write(tagBody, new TaggedBook(BOOK, "v1"));
        assertThat(present.status()).isEqualTo(200);
        assertThat(present.body()).isEqualTo("\"v1\"");
    }

    @Test
    void outputHeaderWithLineBreakIsRefused() {
        RouteDefinition tagged = route(RestBindings
                .builder()
                .get("/tagged/{id}", new GetTaggedBookOperation())
                .header("X-Tag", TaggedBook::tag)
                .build());

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> write(tagged, new TaggedBook(BOOK, "a\r\nSet-Cookie: x=y")))
                .withMessageContaining("X-Tag")
                .withMessageNotContaining("Set-Cookie");
    }

    @Test
    void listAndOptionalHeaderComponents() throws Exception {
        RouteDefinition labels = route(RestBindings
                .builder()
                .get("/labels/{id}", new GetLabelsOperation())
                .header("X-Tags", Labels::tags)
                .header("X-Label", Labels::label)
                .build());

        Written full = write(labels, new Labels(BOOK, List.of("a", "b"), Optional.of("new")));
        assertThat(full.response().getHeaders("X-Tags")).containsExactly("a", "b");
        assertThat(full.header("X-Label")).isEqualTo("new");
        assertThat(full.json().has("tags")).isFalse();
        assertThat(full.json().has("label")).isFalse();

        Written none = write(labels, new Labels(BOOK, List.of(), Optional.empty()));
        assertThat(none.response().containsHeader("X-Tags")).isFalse();
        assertThat(none.response().containsHeader("X-Label")).isFalse();
    }

    @Test
    void headerValuesUseTheConversionService() throws Exception {
        DefaultFormattingConversionService conversions = new DefaultFormattingConversionService();
        conversions.addConverter(Integer.class, String.class, number -> "#" + number);
        ResponseWriter converting = new ResponseWriter(MAPPER, conversions, RouteFixtures.cursors(),
                RouteFixtures.etags());
        RouteDefinition route = route(RestBindings
                .builder()
                .get("/results/{id}", new GetBookResultOperation())
                .header("X-RateLimit-Remaining", BookResult::remaining)
                .build());

        Written written = write(converting, route, new BookResult(BOOK, 5), route.method().name());

        assertThat(written.header("X-RateLimit-Remaining")).isEqualTo("#5");
    }

    @Test
    void notFoundMessageGetsThePathValues() {
        RouteDefinition find = route(RestBindings
                .builder()
                .get("/books/{id}", new FindBookOperation())
                .notFound(BookErrors.BOOK_NOT_FOUND)
                .build());

        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> write(writer, find, Optional.empty(), "GET", Map.of("id", "42")))
                .satisfies(e -> {
                    assertThat(e.getMessage()).isEqualTo("Book 42 was not found");
                    assertThat(e.args()).containsExactly("42");
                });
    }

    @Test
    void defaultNotFoundGetsThePathValuesToo() {
        RouteDefinition find = route(RestBindings.builder().get("/books/{id}", new FindBookOperation()).build());

        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> write(writer, find, Optional.empty(), "GET", Map.of("id", "42")))
                .satisfies(e -> {
                    assertThat(e.details()).isEqualTo(SynapseErrors.NOT_FOUND);
                    assertThat(e.args()).containsExactly("42");
                });
    }

    @Test
    void notFoundArgumentsFollowTheTemplateOrder() {
        RouteDefinition find = route(RestBindings
                .builder()
                .get("/shelves/{shelf}/books/{id}", new FindShelvedBookOperation())
                .notFound(ShelfErrors.NOT_ON_SHELF)
                .build());

        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> write(writer, find, Optional.empty(), "GET",
                        Map.of("id", "7", "shelf", "fiction")))
                .satisfies(e -> {
                    assertThat(e.getMessage()).isEqualTo("Book 7 is not on shelf fiction");
                    assertThat(e.args()).containsExactly("fiction", "7");
                });
    }

    @Test
    void notFoundOnARouteWithoutPathVariablesHasNoArguments() {
        RouteDefinition latest = route(RestBindings
                .builder()
                .get("/books/latest", new FindLatestBookOperation())
                .notFound(BookErrors.BOOK_NOT_FOUND)
                .build());

        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> write(writer, latest, Optional.empty(), "GET", Map.of()))
                .satisfies(e -> {
                    assertThat(e.args()).isEmpty();
                    assertThat(e.getMessage()).isEqualTo("Book {0} was not found");
                });
    }

    @Test
    void nullResultOnAnOptionalRouteIsEmpty() {
        RouteDefinition find = route(RestBindings.builder().get("/books/{id}", new FindBookOperation()).build());

        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> write(find, null))
                .satisfies(e -> assertThat(e.details()).isEqualTo(SynapseErrors.NOT_FOUND));
    }

    @Test
    void declaredStatusAppliesToAVoidResult() throws Exception {
        Written written = write(
                route(RestBindings.builder().delete("/books/{id}", new PurgeBookOperation()).status(202).build()),
                null);

        assertThat(written.status()).isEqualTo(202);
        assertThat(written.body()).isEmpty();
    }

    @Test
    void headerComponentIsRemovedUnderTheMappersNamingStrategy() throws Exception {
        JsonMapper snake = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
        RouteDefinition quota = route(RestBindings
                .builder()
                .get("/quota/{id}", new GetQuotaOperation())
                .header("X-Rate", Quota::rateRemaining)
                .build());

        Written written = write(
                new ResponseWriter(snake, new DefaultFormattingConversionService(), RouteFixtures.cursors(),
                        RouteFixtures.etags()),
                quota,
                new Quota(BOOK, 5), "GET");

        assertThat(written.header("X-Rate")).isEqualTo("5");
        assertThat(written.json().has("rate_remaining")).isFalse();
        assertThat(written.json().has("rateRemaining")).isFalse();
        assertThat(written.json().has("book")).isTrue();
    }

    @Test
    void headRequestGetsTheHeadersWithoutTheBody() throws Exception {
        RouteDefinition get = route(RestBindings
                .builder()
                .get("/results/{id}", new GetBookResultOperation())
                .header("X-RateLimit-Remaining", BookResult::remaining)
                .build());
        Written full = write(writer, get, new BookResult(BOOK, 5), "GET");

        Written head = write(writer, get, new BookResult(BOOK, 5), "HEAD");

        assertThat(head.status()).isEqualTo(200);
        assertThat(head.body()).isEmpty();
        assertThat(head.response().getContentType()).isEqualTo("application/json");
        assertThat(head.response().getContentLength()).isEqualTo(full.body().length()).isPositive();
        assertThat(head.header("X-RateLimit-Remaining")).isEqualTo("5");
    }

    @Test
    void pageResultIsAPageObject() throws Exception {
        RouteDefinition paged = route(RestBindings.builder().get("/books", new PagedBooksOperation()).build());
        PagedBooksRequest input = new PagedBooksRequest("herbert", PageQuery.first(20));
        Book other = new Book(8, "Dune Messiah");

        Written written = write(writer, paged, new PageResult<>(List.of(BOOK, other), "raw", true, 42L), "GET",
                Map.of(), input);

        assertThat(written.status()).isEqualTo(200);
        assertThat(written.response().getContentType()).isEqualTo("application/json");
        JsonNode body = written.json();
        assertThat(body.propertyNames()).containsExactly("items", "nextCursor", "hasNext", "total");
        assertThat(body.get("items")).isEqualTo(json(List.of(BOOK, other)));
        assertThat(body.get("hasNext").asBoolean()).isTrue();
        assertThat(body.get("total").asLong()).isEqualTo(42);
        String token = body.get("nextCursor").asString();
        assertThat(token).isNotEqualTo("raw").doesNotContain("raw").matches("[A-Za-z0-9_-]+");
        assertThat(written.body()).doesNotContain("\"raw\"");
        // The token is bound to the operation, the sort and the filters of this request.
        Object next = RouteFixtures
                .cursors()
                .unwrap(paged, new PagedBooksRequest("herbert", PageQuery.ofCursor(token, 20, List.of())));
        assertThat(next).isEqualTo(new PagedBooksRequest("herbert", PageQuery.ofCursor("raw", 20, List.of())));
    }

    @Test
    void nullMembersAreOmitted() throws Exception {
        RouteDefinition paged = route(RestBindings.builder().get("/books", new PagedBooksOperation()).build());

        Written written = write(writer, paged, PageResult.of(List.of(), false), "GET", Map.of(),
                new PagedBooksRequest(null, PageQuery.first(20)));

        assertThat(written.status()).isEqualTo(200);
        assertThat(written.body()).isEqualTo("{\"items\":[],\"hasNext\":false}");
    }

    @Test
    void itemsAndHasNextSurviveTheApplicationsInclusion() throws Exception {
        RouteDefinition paged = route(RestBindings.builder().get("/books", new PagedBooksOperation()).build());
        for (JsonInclude.Include inclusion : List.of(JsonInclude.Include.NON_EMPTY, JsonInclude.Include.NON_DEFAULT)) {
            JsonMapper strict = JsonMapper
                    .builder()
                    .changeDefaultPropertyInclusion(value -> value.withValueInclusion(inclusion))
                    .build();

            Written written = write(
                    new ResponseWriter(strict, new DefaultFormattingConversionService(), RouteFixtures.cursors(),
                            RouteFixtures.etags()),
                    paged, PageResult.of(List.of(), false), "GET", Map.of(),
                    new PagedBooksRequest(null, PageQuery.first(20)));

            assertThat(written.body()).as(inclusion.name()).isEqualTo("{\"items\":[],\"hasNext\":false}");
        }
    }

    @Test
    void pageMemberNamesFollowTheNamingStrategy() throws Exception {
        JsonMapper snake = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
        RouteDefinition paged = route(RestBindings.builder().get("/books", new PagedBooksOperation()).build());

        Written written = write(
                new ResponseWriter(snake, new DefaultFormattingConversionService(), RouteFixtures.cursors(),
                        RouteFixtures.etags()),
                paged, new PageResult<>(List.of(BOOK), "raw", true, 3L), "GET", Map.of(),
                new PagedBooksRequest(null, PageQuery.first(20)));

        assertThat(written.json().propertyNames()).containsExactly("items", "next_cursor", "has_next", "total");
    }

    @Test
    void pageResultTheStartupCheckCantSeeIsNeverWritten() {
        PageResult<Book> hidden = new PageResult<>(List.of(BOOK), "RAW-SECRET", true, null);
        Smuggler output = new Smuggler("s", Map.of("page", hidden));
        List<RouteDefinition> routes = List
                .of(route(RestBindings.builder().get("/smuggler", new GetSmugglerOperation()).build()),
                        route(RestBindings
                                .builder()
                                .get("/smuggler", new GetSmugglerOperation())
                                .header("X-Name", Smuggler::name)
                                .build()));

        for (RouteDefinition route : routes) {
            assertThatIllegalStateException()
                    .isThrownBy(() -> write(route, output))
                    .withMessage("PageResult must be the operation's direct output")
                    .satisfies(e -> {
                        assertThat(e.getCause()).isNull();
                        StringWriter trace = new StringWriter();
                        e.printStackTrace(new PrintWriter(trace));
                        assertThat(trace.toString()).doesNotContain("RAW-SECRET");
                    });
        }
    }

    @Test
    void headOfAPageHasNoBody() throws Exception {
        RouteDefinition paged = route(RestBindings.builder().get("/books", new PagedBooksOperation()).build());
        PagedBooksRequest input = new PagedBooksRequest(null, PageQuery.first(20));

        Written head = write(writer, paged, new PageResult<>(List.of(BOOK), "raw", true, null), "HEAD", Map.of(),
                input);

        assertThat(head.status()).isEqualTo(200);
        assertThat(head.body()).isEmpty();
        assertThat(head.response().getContentLength()).isPositive();
        for (String name : head.response().getHeaderNames()) {
            assertThat(head.response().getHeaders(name)).as(name).noneMatch(value -> value.contains("raw"));
        }
    }

    @Test
    void etagRouteAddsValidators() throws Exception {
        Written written = write(versionedGet(), VERSIONED);

        assertThat(written.status()).isEqualTo(200);
        assertThat(written.header("ETag")).isEqualTo("\"7\"");
        assertThat(written.header("Cache-Control")).isEqualTo("no-cache");
        assertThat(written.header("Vary")).isEqualTo("Accept");
        // The ETag component stays in the body.
        assertThat(written.json().get("version").asLong()).isEqualTo(7);
        assertThat(written.header("X-Title")).isEqualTo("Dune");
    }

    @Test
    void matchingIfNoneMatchIs304() throws Exception {
        for (String method : List.of("GET", "HEAD")) {
            Written written = conditional(versionedGet(), VERSIONED, method, "\"7\"");

            assertThat(written.status()).as(method).isEqualTo(304);
            assertThat(written.header("ETag")).as(method).isEqualTo("\"7\"");
            assertThat(written.header("Cache-Control")).as(method).isEqualTo("no-cache");
            assertThat(written.header("Vary")).as(method).isEqualTo("Accept");
            assertThat(written.body()).as(method).isEmpty();
            assertThat(written.response().getContentType()).as(method).isNull();
            assertThat(written.response().containsHeader("Content-Length")).as(method).isFalse();
            // Nothing else: no output headers.
            assertThat(written.response().getHeaderNames())
                    .as(method)
                    .containsExactlyInAnyOrder("ETag", "Cache-Control", "Vary");
        }
    }

    @Test
    void weakAndWildcardIfNoneMatchMatch() throws Exception {
        assertThat(conditional(versionedGet(), VERSIONED, "GET", "W/\"7\"").status()).isEqualTo(304);
        assertThat(conditional(versionedGet(), VERSIONED, "GET", "*").status()).isEqualTo(304);
        assertThat(conditional(versionedGet(), VERSIONED, "GET", "\"5\", W/\"7\"").status()).isEqualTo(304);

        Written other = conditional(versionedGet(), VERSIONED, "GET", "\"8\"");
        assertThat(other.status()).isEqualTo(200);
        assertThat(other.header("ETag")).isEqualTo("\"7\"");
        assertThat(other.json().get("version").asLong()).isEqualTo(7);
    }

    @Test
    void malformedIfNoneMatchIsIgnored() throws Exception {
        for (String malformed : List.of("7", "\"7\"x", "*, \"7\"")) {
            Written written = conditional(versionedGet(), VERSIONED, "GET", malformed);

            assertThat(written.status()).as(malformed).isEqualTo(200);
            assertThat(written.header("ETag")).as(malformed).isEqualTo("\"7\"");
            assertThat(written.json().get("version").asLong()).as(malformed).isEqualTo(7);
        }
    }

    @Test
    void postCreatedSendsETagOnly() throws Exception {
        RouteDefinition create = route(RestBindings
                .builder()
                .post("/books", new CreateVersionedBookOperation())
                .created(b -> "/books/" + b.id())
                .etag(VersionedBook::version)
                .build());

        Written written = conditional(create, new VersionedBook(1, "Dune", 1L), "POST", "*");

        assertThat(written.status()).isEqualTo(201);
        assertThat(written.header("Location")).isEqualTo("/books/1");
        assertThat(written.header("ETag")).isEqualTo("\"1\"");
        assertThat(written.response().containsHeader("Cache-Control")).isFalse();
        assertThat(written.response().containsHeader("Vary")).isFalse();
        assertThat(written.json().get("version").asLong()).isEqualTo(1);
    }

    @Test
    void nullTagMeansNoValidators() throws Exception {
        VersionedBook unversioned = new VersionedBook(7, "Dune", null);

        Written written = conditional(versionedGet(), unversioned, "GET", "*");

        assertThat(written.status()).isEqualTo(200);
        assertThat(written.response().containsHeader("ETag")).isFalse();
        assertThat(written.response().containsHeader("Cache-Control")).isFalse();
        assertThat(written.response().containsHeader("Vary")).isFalse();
        assertThat(written.json().has("title")).isTrue();
    }

    @Test
    void etagIsSentOnlyWithA200OnRead() throws Exception {
        RouteDefinition accepted = route(RestBindings
                .builder()
                .get("/books/{id}", new GetVersionedBookOperation())
                .status(203)
                .etag(VersionedBook::version)
                .build());

        Written written = conditional(accepted, VERSIONED, "GET", "\"7\"");

        assertThat(written.status()).isEqualTo(203);
        assertThat(written.response().containsHeader("ETag")).isFalse();
        assertThat(written.response().containsHeader("Cache-Control")).isFalse();
    }

    /** A GET route with {@code .etag(version)} and an output header, so a 304 can show it leaves it out. */
    private static RouteDefinition versionedGet() {
        return route(RestBindings
                .builder()
                .get("/books/{id}", new GetVersionedBookOperation())
                .etag(VersionedBook::version)
                .header("X-Title", VersionedBook::title)
                .alsoInBody()
                .build());
    }

    /** Writes with an {@code If-None-Match} header. */
    private Written conditional(RouteDefinition route, Object result, String method, String ifNoneMatch)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/");
        request.setAttribute(RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of());
        request.addHeader("If-None-Match", ifNoneMatch);
        return written(request, writer.write(route, result, ServerRequest.create(request, List.of()), null));
    }

    private Written write(RouteDefinition route, @Nullable Object result) throws Exception {
        return write(writer, route, result, route.method().name());
    }

    private static Written write(ResponseWriter writer, RouteDefinition route, @Nullable Object result,
            String method) throws Exception {
        return write(writer, route, result, method, Map.of());
    }

    /** Writes with the path variables that routing would have put on the request. */
    private static Written write(ResponseWriter writer, RouteDefinition route, @Nullable Object result,
            String method, Map<String, String> pathVariables) throws Exception {
        return write(writer, route, result, method, pathVariables, null);
    }

    /** Writes the result of the operation called with the input. */
    private static Written write(ResponseWriter writer, RouteDefinition route, @Nullable Object result,
            String method, Map<String, String> pathVariables, @Nullable Object input) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/");
        request.setAttribute(RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE, pathVariables);
        return written(request, writer.write(route, result, ServerRequest.create(request, List.of()), input));
    }

    /** Writes the response to a mock servlet response. */
    private static Written written(MockHttpServletRequest request, ServerResponse response) throws Exception {
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        response.writeTo(request, servletResponse, List::of);
        return new Written(servletResponse.getStatus(), servletResponse, servletResponse.getContentAsString());
    }

    /** Identifies a book on a shelf; the template names the shelf first. */
    /** An output that hides a page where the startup check can't see it. */
    record Smuggler(String name, Map<String, Object> extras) {
    }

    static class GetSmugglerOperation implements QueryOperation<Void, Smuggler> {
        @Override
        public Smuggler execute(Void input) {
            return new Smuggler("s", Map.of());
        }
    }

    record ShelvedBookId(String shelf, long id) {
    }

    static class FindShelvedBookOperation implements QueryOperation<ShelvedBookId, Optional<Book>> {
        @Override
        public Optional<Book> execute(ShelvedBookId input) {
            return Optional.empty();
        }
    }

    static class FindLatestBookOperation implements QueryOperation<Void, Optional<Book>> {
        @Override
        public Optional<Book> execute(Void input) {
            return Optional.empty();
        }
    }

    /** A message whose placeholders are not in template order. */
    enum ShelfErrors implements ErrorDetails {

        NOT_ON_SHELF;

        @Override
        public String code() {
            return name();
        }

        @Override
        public String messageTemplate() {
            return "Book {1} is not on shelf {0}";
        }

        @Override
        public ErrorCategory category() {
            return ErrorCategory.NOT_FOUND;
        }
    }

    /** The JSON of a value, read back as a tree, so number nodes compare like a parsed body's. */
    private static JsonNode json(Object value) {
        return MAPPER.readTree(MAPPER.writeValueAsString(value));
    }
}
