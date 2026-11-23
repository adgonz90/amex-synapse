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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.RestErrors;
import io.americanexpress.synapse.api.rest.internal.binding.CookieDuplicates;
import io.americanexpress.synapse.api.rest.internal.binding.RequestBinder;
import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.api.rest.internal.binding.WireNames;
import io.americanexpress.synapse.api.rest.internal.edge.RestRequestAttributes;
import io.americanexpress.synapse.api.rest.internal.edge.SizeLimitedRequest;
import io.americanexpress.synapse.api.rest.internal.error.RestProtocolException;
import io.americanexpress.synapse.api.rest.internal.paging.PagingSettings;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.CreateBookOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.GetBookOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.PagedBooksOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.PagedBooksRequest;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.ReviseVersionedBookOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.RevisionRequest;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.VersionedBook;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.core.internal.paging.PageCursors;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;

import static io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.route;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class OperationHandlerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final PageCursors CURSORS = RouteFixtures.cursors();

    private final GetBookOperation getBook = new GetBookOperation();

    private final OperationHandler handler = handler(
            route(RestBindings.builder().get("/books/{id}", getBook).build()),
            new RequestBinder(MAPPER, new DefaultFormattingConversionService(), PagingSettings.defaults(),
                    CookieDuplicates.none()),
            new ResponseWriter(MAPPER, new DefaultFormattingConversionService(), CURSORS, PagingSettings.defaults(),
                    RouteFixtures.etags()),
            PagingSettings.defaults());

    @ParameterizedTest
    @ValueSource(strings = {"", "application/json", "*/*", "application/*", "application/json;charset=UTF-8",
            "text/html, application/json;q=0.5", "application/xml, */*;q=0.1"})
    void jsonCompatibleOrAbsentAcceptRunsTheOperation(String accept) throws Exception {
        MockHttpServletRequest request = request(accept);

        ServerResponse response = handler.handle(ServerRequest.create(request, List.of()));

        assertThat(response.statusCode().value()).isEqualTo(200);
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        response.writeTo(request, servletResponse, List::of);
        assertThat(MAPPER.readTree(servletResponse.getContentAsString()).get("id").asLong()).isEqualTo(3);
        assertThat(getBook.calls.get()).isEqualTo(1);
        assertThat(handler.route().operationName()).isEqualTo("get-book");
        assertThat(RestRequestAttributes.wireNames(request)).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/xml", "text/html, text/plain", "application/json;q=0",
            "application/problem+json", "not a media type"})
    void otherAcceptIsNotAcceptableBeforeTheOperationRuns(String accept) {
        MockHttpServletRequest request = request(accept);

        assertThatExceptionOfType(RestProtocolException.class)
                .isThrownBy(() -> handler.handle(ServerRequest.create(request, List.of())))
                .satisfies(e -> {
                    assertThat(e.details()).isEqualTo(RestErrors.NOT_ACCEPTABLE);
                    // What the route produces, as Spring's 406 lists it.
                    assertThat(e.headers()).containsExactly(Map.entry("Accept", "application/json"));
                });
        assertThat(getBook.calls.get()).isZero();
        assertThat(RestRequestAttributes.operationName(request)).isEqualTo("get-book");
    }

    @Test
    void setsTheOperationNameBeforeBinding() {
        MockHttpServletRequest request = request("");
        request.setAttribute(RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", "x"));

        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> handler.handle(ServerRequest.create(request, List.of())));
        assertThat(RestRequestAttributes.operationName(request)).isEqualTo("get-book");
        // Binding failed, so the operation's wire names are not set: the binder's violations are wire names already.
        assertThat(RestRequestAttributes.wireNames(request)).isNull();
        assertThat(getBook.calls.get()).isZero();
    }

    @Test
    void routeRequestSizeLimitAppliesBeforeTheBodyIsRead() throws Exception {
        CreateBookOperation createBook = new CreateBookOperation();
        RequestBinder binder = new RequestBinder(MAPPER, new DefaultFormattingConversionService(),
                PagingSettings.defaults(), CookieDuplicates.none());
        ResponseWriter writer = new ResponseWriter(MAPPER, new DefaultFormattingConversionService(), CURSORS,
                PagingSettings.defaults(),
                RouteFixtures.etags());
        OperationHandler limited = handler(
                route(RestBindings.builder().post("/books", createBook).maxRequestSize(DataSize.ofBytes(16)).build()),
                binder, writer, PagingSettings.defaults());
        OperationHandler unlimited = handler(
                route(RestBindings.builder().post("/books", createBook).build()), binder, writer,
                PagingSettings.defaults());

        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> limited.handle(ServerRequest.create(postBook(), List.of())))
                .satisfies(e -> assertThat(e.details()).isEqualTo(RestErrors.PAYLOAD_TOO_LARGE));

        // Without a route limit, the edge's default (here 1 MB) stays.
        ServerResponse response = unlimited.handle(ServerRequest.create(postBook(), List.of()));
        assertThat(response.statusCode().value()).isEqualTo(200);
    }

    @Test
    void operationGetsTheRawCursorAndTheClientTheToken() throws Exception {
        PagedBooksOperation paged = new PagedBooksOperation();
        paged.result = new PageResult<>(List.of(RouteFixtures.BOOK), "RAW-2", true, null);
        OperationHandler pages = pagedHandler(paged);

        String token = MAPPER.readTree(body(pages, "author=herbert&size=1")).get("nextCursor").asString();
        assertThat(token).doesNotContain("RAW");
        paged.result = new PageResult<>(List.of(RouteFixtures.BOOK), "RAW-3", true, null);
        String next = body(pages, "author=herbert&size=1&cursor=" + token);

        assertThat(paged.inputs)
                .extracting(PagedBooksRequest::page)
                .extracting(PageQuery::cursor)
                .containsExactly(null, "RAW-2");
        assertThat(paged.inputs.get(1))
                .isEqualTo(new PagedBooksRequest("herbert", PageQuery
                        .ofCursor("RAW-2", 1,
                                List.of())));
        assertThat(next).doesNotContain("RAW");
    }

    @Test
    void offsetTokenOverALoweredCapIsInvalid() throws Exception {
        PagedBooksOperation paged = new PagedBooksOperation();
        // An offset page: no raw cursor, but a next page, so the body's nextCursor is an offset token (offset 30).
        paged.result = new PageResult<>(List.of(RouteFixtures.BOOK), null, true, null);
        String token = MAPPER
                .readTree(body(pagedHandler(paged), "author=herbert&offset=20&size=10"))
                .get("nextCursor")
                .asString();
        paged.inputs.clear();
        PagingSettings defaults = PagingSettings.defaults();
        PagingSettings lowered = new PagingSettings(defaults.cursor(), defaults.offset(), defaults.size(),
                defaults.sort(), 25, defaults.defaultSize(), defaults.maxSize());
        OperationHandler capped = pagedHandler(paged, lowered);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/books");
        request.setQueryString("author=herbert&size=10&cursor=" + token);
        // The token carries offset 30; the cap is now 25: the offset limit, on the cursor parameter.
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> capped.handle(ServerRequest.create(request, List.of())))
                .satisfies(e -> {
                    assertThat(e.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED);
                    assertThat(e.violations())
                            .containsExactly(new Violation("cursor",
                                    "has reached the offset limit (25); narrow the query or use keyset paging"));
                });
        assertThat(paged.inputs).isEmpty();
    }

    @Test
    void invalidCursorIs400BeforeTheOperationRuns() throws Exception {
        PagedBooksOperation paged = new PagedBooksOperation();
        paged.result = new PageResult<>(List.of(RouteFixtures.BOOK), "RAW-2", true, null);
        OperationHandler pages = pagedHandler(paged);
        String token = MAPPER.readTree(body(pages, "author=herbert")).get("nextCursor").asString();
        paged.inputs.clear();

        for (String query : List.of("cursor=garbage", "author=guin&cursor=" + token)) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/books");
            request.setQueryString(query);
            assertThatExceptionOfType(SynapseException.class)
                    .as(query)
                    .isThrownBy(() -> pages.handle(ServerRequest.create(request, List.of())))
                    .satisfies(e -> assertThat(e.violations())
                            .containsExactly(new Violation("cursor", "is not valid for this request")));
        }
        assertThat(paged.inputs).isEmpty();
    }

    @Test
    void preconditionsRunAfterBindingAndBeforeExecute() {
        RequestBinder binder = new RequestBinder(MAPPER, new DefaultFormattingConversionService(),
                PagingSettings.defaults(), CookieDuplicates.none());
        ResponseWriter writer = new ResponseWriter(MAPPER, new DefaultFormattingConversionService(), CURSORS,
                PagingSettings.defaults(),
                RouteFixtures.etags());
        OperationHandler create = handler(
                route(RestBindings.builder().post("/books", new CreateBookOperation()).build()), binder, writer,
                PagingSettings.defaults());
        ReviseVersionedBookOperation revise = new ReviseVersionedBookOperation();
        OperationHandler revising = handler(route(RestBindings
                .builder()
                .put("/books/{id}", revise)
                .header("If-Match", RevisionRequest::version)
                .build()), binder, writer, PagingSettings.defaults());

        // A bad body is 400 even with an If-Match the route doesn't bind: binding comes first.
        MockHttpServletRequest badBody = new MockHttpServletRequest("POST", "/books");
        badBody.setContentType("application/json");
        badBody.setContent("{\"id\":\"seven\",\"title\":\"Dune\"}".getBytes(StandardCharsets.UTF_8));
        badBody.addHeader("If-Match", "\"1\"");
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> create.handle(ServerRequest.create(badBody, List.of())))
                .satisfies(e -> assertThat(e.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED));

        // A valid body with a weak If-Match is 412, and the operation never runs.
        MockHttpServletRequest weak = new MockHttpServletRequest("PUT", "/books/7");
        weak.setContentType("application/json");
        weak.setContent("{\"title\":\"Dune\"}".getBytes(StandardCharsets.UTF_8));
        weak.addHeader("If-Match", "W/\"1\"");
        weak.setAttribute(RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", "7"));
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> revising.handle(ServerRequest.create(weak, List.of())))
                .satisfies(e -> assertThat(e.details()).isEqualTo(SynapseErrors.PRECONDITION_FAILED));
        assertThat(revise.calls.get()).isZero();
    }

    @Test
    void staleVersionFromTheOperationIs412OnlyWithIfMatch() {
        RequestBinder binder = new RequestBinder(MAPPER, new DefaultFormattingConversionService(),
                PagingSettings.defaults(), CookieDuplicates.none());
        ResponseWriter writer = new ResponseWriter(MAPPER, new DefaultFormattingConversionService(), CURSORS,
                PagingSettings.defaults(),
                RouteFixtures.etags());
        OperationHandler stale = handler(route(RestBindings
                .builder()
                .put("/books/{id}", new StaleBookOperation())
                .header("If-Match", RevisionRequest::version)
                .build()), binder, writer, PagingSettings.defaults());

        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> stale.handle(ServerRequest.create(putBook("\"1\""), List.of())))
                .satisfies(e -> {
                    assertThat(e.details()).isEqualTo(SynapseErrors.PRECONDITION_FAILED);
                    assertThat(e.getCause())
                            .isInstanceOfSatisfying(SynapseException.class,
                                    cause -> assertThat(cause.details()).isEqualTo(SynapseErrors.STALE_VERSION));
                });
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> stale.handle(ServerRequest.create(putBook(null), List.of())))
                .satisfies(e -> assertThat(e.details()).isEqualTo(SynapseErrors.STALE_VERSION));
    }

    /** Always loses the race: its compare-and-set fails. */
    static class StaleBookOperation implements CommandOperation<RevisionRequest, VersionedBook> {
        @Override
        public VersionedBook execute(RevisionRequest input) {
            throw new SynapseException(SynapseErrors.STALE_VERSION);
        }
    }

    private static MockHttpServletRequest putBook(@Nullable String ifMatch) {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/books/7");
        request.setContentType("application/json");
        request.setContent("{\"title\":\"Dune\"}".getBytes(StandardCharsets.UTF_8));
        if (ifMatch != null) {
            request.addHeader("If-Match", ifMatch);
        }
        request.setAttribute(RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", "7"));
        return request;
    }

    private static OperationHandler handler(RouteDefinition route, RequestBinder binder, ResponseWriter writer,
            PagingSettings paging) {
        return new OperationHandler(route, binder, writer, CURSORS, paging,
                WireNames.of(route, binder.mapper(), paging));
    }

    private static OperationHandler pagedHandler(PagedBooksOperation paged) {
        return pagedHandler(paged, PagingSettings.defaults());
    }

    private static OperationHandler pagedHandler(PagedBooksOperation paged, PagingSettings paging) {
        RequestBinder binder = new RequestBinder(MAPPER, new DefaultFormattingConversionService(), paging,
                CookieDuplicates.none());
        return handler(route(RestBindings.builder().get("/books", paged).build()), binder,
                new ResponseWriter(MAPPER, new DefaultFormattingConversionService(), CURSORS, paging,
                        RouteFixtures.etags()),
                paging);
    }

    /** The body of a GET {@code /books} with the query string. */
    private static String body(OperationHandler handler, String query) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/books");
        request.setQueryString(query);
        request.setAttribute(RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of());
        ServerResponse response = handler.handle(ServerRequest.create(request, List.of()));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        response.writeTo(request, servletResponse, List::of);
        assertThat(servletResponse.getStatus()).isEqualTo(200);
        return servletResponse.getContentAsString();
    }

    /** A JSON body of more than 16 bytes, behind the size limit the edge filter installs. */
    private static HttpServletRequest postBook() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/books");
        request.setContentType("application/json");
        request.setContent("{\"id\":7,\"title\":\"Dune Messiah\"}".getBytes(StandardCharsets.UTF_8));
        return new SizeLimitedRequest(request, DataSize.ofMegabytes(1).toBytes());
    }

    private static MockHttpServletRequest request(String accept) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/books/3");
        if (!accept.isEmpty()) {
            request.addHeader("Accept", accept);
        }
        request.setAttribute(RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", "3"));
        return request;
    }
}
