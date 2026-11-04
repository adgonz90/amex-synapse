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

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
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
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.DeleteBookOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.FindBookOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.FindBookResultOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.GetBookOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.GetBookResultOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.GetLabelsOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.GetQuotaOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.GetRenamedResultOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.GetTaggedBookOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.Job;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.Labels;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.ListBooksOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.PurgeBookOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.Quota;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.SubmitJobOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.TaggedBook;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;

import static io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.BOOK;
import static io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.route;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ResponseWriterTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final ResponseWriter writer = new ResponseWriter(MAPPER, new DefaultFormattingConversionService());

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
        ResponseWriter converting = new ResponseWriter(MAPPER, conversions);
        RouteDefinition route = route(RestBindings
                .builder()
                .get("/results/{id}", new GetBookResultOperation())
                .header("X-RateLimit-Remaining", BookResult::remaining)
                .build());

        Written written = write(converting, route, new BookResult(BOOK, 5), route.method().name());

        assertThat(written.header("X-RateLimit-Remaining")).isEqualTo("#5");
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

        Written written = write(new ResponseWriter(snake, new DefaultFormattingConversionService()), quota,
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

    private Written write(RouteDefinition route, @Nullable Object result) throws Exception {
        return write(writer, route, result, route.method().name());
    }

    private static Written write(ResponseWriter writer, RouteDefinition route, @Nullable Object result,
            String method) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/");
        ServerResponse response = writer.write(route, result, ServerRequest.create(request, List.of()));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        response.writeTo(request, servletResponse, List::of);
        return new Written(servletResponse.getStatus(), servletResponse, servletResponse.getContentAsString());
    }

    /** The JSON of a value, read back as a tree, so number nodes compare like a parsed body's. */
    private static JsonNode json(Object value) {
        return MAPPER.readTree(MAPPER.writeValueAsString(value));
    }
}
