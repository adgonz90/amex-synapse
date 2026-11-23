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
package io.americanexpress.synapse.api.rest.internal.error;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.util.ServletRequestPathUtils;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.internal.binding.BindingValidator;
import io.americanexpress.synapse.api.rest.internal.binding.CookieDuplicates;
import io.americanexpress.synapse.api.rest.internal.binding.RequestBinder;
import io.americanexpress.synapse.api.rest.internal.conditional.ETagValues;
import io.americanexpress.synapse.api.rest.internal.paging.PagingSettings;
import io.americanexpress.synapse.api.rest.internal.route.ResponseWriter;
import io.americanexpress.synapse.api.rest.internal.route.RouteCompiler;
import io.americanexpress.synapse.core.autoconfigure.SynapsePagingProperties;
import io.americanexpress.synapse.core.internal.paging.AesGcmCursorCodec;
import io.americanexpress.synapse.core.internal.paging.CursorKeys;
import io.americanexpress.synapse.core.internal.paging.InputDigest;
import io.americanexpress.synapse.core.internal.paging.PageCursors;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static org.assertj.core.api.Assertions.assertThat;

class SynapsePathsTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void servedPathMatchesWhateverTheMethod() {
        SynapsePaths paths = SynapsePaths
                .of(compile(RestBindings.builder().get("/books/{id}", new GetBookOperation()).version("2").build()));

        assertThat(paths.matches(new MockHttpServletRequest("GET", "/v2/books/7"))).isTrue();
        assertThat(paths.matches(new MockHttpServletRequest("DELETE", "/v2/books/7"))).isTrue();
        assertThat(paths.matches(new MockHttpServletRequest("GET", "/v3/books/7"))).isFalse();
        assertThat(paths.matches(new MockHttpServletRequest("GET", "/books/7"))).isFalse();
        assertThat(paths.matches(new MockHttpServletRequest("GET", "/v2/books/7/editions"))).isFalse();
    }

    @Test
    void contextPathIsLeftOut() {
        SynapsePaths paths = SynapsePaths
                .of(compile(RestBindings.builder().get("/books/{id}", new GetBookOperation()).build()));

        assertThat(paths.matches(underContextPath("/api/books/7"))).isTrue();
        assertThat(paths.matches(underContextPath("/api/api/books/7"))).isFalse();
        assertThat(paths.matches(underContextPath("/api/shelves/7"))).isFalse();
    }

    /** Inside the DispatcherServlet, the path it parsed and cached is the one read. */
    @Test
    void cachedPathIsRead() {
        SynapsePaths paths = SynapsePaths
                .of(compile(RestBindings.builder().get("/books/{id}", new GetBookOperation()).build()));
        MockHttpServletRequest request = underContextPath("/api/books/7");
        ServletRequestPathUtils.parseAndCache(request);

        assertThat(paths.matches(request)).isTrue();
        assertThat(ServletRequestPathUtils.hasParsedRequestPath(request)).isTrue();
    }

    /** Reading the path doesn't leave a parsed path behind, which would change how Spring MVC reads it later. */
    @Test
    void uncachedPathIsNotCached() {
        SynapsePaths paths = SynapsePaths
                .of(compile(RestBindings.builder().get("/books/{id}", new GetBookOperation()).build()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/books/7");

        assertThat(paths.matches(request)).isTrue();
        assertThat(ServletRequestPathUtils.hasParsedRequestPath(request)).isFalse();
    }

    @Test
    void noneMatchesNothing() {
        assertThat(SynapsePaths.none().matches(new MockHttpServletRequest("GET", "/"))).isFalse();
        assertThat(SynapsePaths.none().matches(new MockHttpServletRequest("GET", "/books/7"))).isFalse();
        assertThat(SynapsePaths.none().owns(new MockHttpServletRequest("GET", "/books/7"))).isFalse();
    }

    /**
     * An unversioned route owns its literal prefix, the segments before its first variable, and every path under it;
     * a shared first segment is not enough.
     */
    @Test
    void unversionedRouteOwnsItsLiteralPrefix() {
        SynapsePaths paths = SynapsePaths
                .of(compile(RestBindings
                        .builder()
                        .get("/api/books/{id}", new GetBookOperation())
                        .get("/shelves/{id}/books/{book}", new GetShelfBookOperation())
                        .build()));

        assertOwns(paths, "/api/books", "/api/books/nope", "/api/books/7/reviews", "/shelves", "/shelves/7/x");
        assertNotOwned(paths, "/api/other", "/api", "/api/bookshelf", "/", "/Api/books/7", "/v1/api/books/7",
                "/catalog/api/books/7");
        assertThat(paths.owns(underContextPath("/api/api/books/7/reviews"))).isTrue();
        assertThat(paths.owns(underContextPath("/api/other"))).isFalse();
    }

    /**
     * A versioned route owns every version, but only under the literal prefix after its version segment: neither
     * another prefix under a version nor its unversioned prefix.
     */
    @Test
    void versionedRouteOwnsEveryVersionUnderItsLiteralPrefix() {
        SynapsePaths paths = SynapsePaths
                .of(compile(RestBindings
                        .builder()
                        .get("/books/{id}", new GetBookOperation())
                        .version("1")
                        .get("/books/{id}", new GetBookOperation())
                        .version("3")
                        .build()));

        assertOwns(paths, "/v9/books/x", "/v2/books/x", "/v1/books", "/v3/books/7/reviews", "/v2.1/books/x",
                "/v1.2.3/books/x");
        assertNotOwned(paths, "/v2/legacy/x", "/v9/unknown", "/v9", "/books/7", "/vx/books/7", "/v1.2.3.4/books/7",
                "/V1/books/7", "/v/books/7");
    }

    /** A route without a literal prefix (a variable first, or right after the version) owns only its template. */
    @Test
    void routeWithoutALiteralPrefixOwnsOnlyItsTemplate() {
        SynapsePaths unversioned = SynapsePaths
                .of(compile(RestBindings.builder().get("/{slug}", new GetSlugOperation()).build()));
        SynapsePaths versioned = SynapsePaths
                .of(compile(RestBindings.builder().get("/{slug}", new GetSlugOperation()).version("2").build()));

        assertOwns(unversioned, "/anything");
        assertNotOwned(unversioned, "/", "/anything/else", "/v1/anything");
        assertOwns(versioned, "/v2/anything", "/v9/anything");
        assertNotOwned(versioned, "/v9", "/v9/anything/else", "/anything", "/vx/anything");
    }

    /**
     * A route on {@code /} has no literal prefix: it owns exactly {@code /}, or exactly {@code /v<any>} when
     * versioned.
     */
    @Test
    void routeOnTheRootOwnsOnlyTheRoot() {
        SynapsePaths unversioned = SynapsePaths
                .of(compile(RestBindings.builder().get("/", new GetRootOperation()).build()));
        SynapsePaths versioned = SynapsePaths
                .of(compile(RestBindings.builder().get("/", new GetRootOperation()).version("2").build()));

        assertOwns(unversioned, "/");
        assertNotOwned(unversioned, "/anything", "/v1", "/v1/anything");
        assertOwns(versioned, "/v2", "/v9", "/v1.2");
        assertNotOwned(versioned, "/", "/v9/anything", "/vx", "/anything");
    }

    private static void assertOwns(SynapsePaths paths, String... uris) {
        assertThat(uris)
                .allSatisfy(uri -> assertThat(paths.owns(new MockHttpServletRequest("GET", uri))).as(uri).isTrue());
    }

    private static void assertNotOwned(SynapsePaths paths, String... uris) {
        assertThat(uris)
                .allSatisfy(uri -> assertThat(paths.owns(new MockHttpServletRequest("GET", uri))).as(uri).isFalse());
    }

    @Test
    void ownsNoVersionPrefixWithoutAVersionedRoute() {
        SynapsePaths paths = SynapsePaths
                .of(compile(RestBindings.builder().get("/books/{id}", new GetBookOperation()).build()));

        assertThat(paths.owns(new MockHttpServletRequest("GET", "/v1/books/7"))).isFalse();
        assertThat(paths.owns(new MockHttpServletRequest("GET", "/books/7/x"))).isTrue();
    }

    /** Only the routes of Synapse's operations count: another router function's paths are not Synapse paths. */
    @Test
    void otherRouterFunctionsAreNotSynapsePaths() {
        RouterFunction<ServerResponse> other = RouterFunctions
                .route()
                .GET("/user/{id}", request -> ServerResponse.ok().build())
                .build();

        assertThat(SynapsePaths.of(other).matches(new MockHttpServletRequest("GET", "/user/7"))).isFalse();
    }

    private static MockHttpServletRequest underContextPath(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setContextPath("/api");
        return request;
    }

    /** The compiled route {@code GET /books/{id}}. */
    static RouterFunction<ServerResponse> booksRoutes() {
        return compile(RestBindings.builder().get("/books/{id}", new GetBookOperation()).build());
    }

    private static RouterFunction<ServerResponse> compile(RestBindings bindings) {
        SecureRandom random = new SecureRandom();
        PageCursors cursors = new PageCursors(new AesGcmCursorCodec(
                CursorKeys.from(new SynapsePagingProperties.Cursor(null, List.of(), null), random), Clock.systemUTC(),
                null, random), new InputDigest());
        RouteCompiler compiler = new RouteCompiler(new BindingValidator(PagingSettings.defaults()),
                new RequestBinder(MAPPER, new DefaultFormattingConversionService(), PagingSettings.defaults(),
                        CookieDuplicates.none()),
                new ResponseWriter(MAPPER, new DefaultFormattingConversionService(), cursors, PagingSettings.defaults(),
                        new ETagValues()),
                cursors, PagingSettings.defaults(), null);
        return compiler.compile(List.of(bindings), List.of());
    }

    record BookId(long id) {
    }

    record Book(long id, String title) {
    }

    static class GetBookOperation implements QueryOperation<BookId, Book> {
        @Override
        public Book execute(BookId input) {
            return new Book(input.id(), "Dune");
        }
    }

    record Slug(String slug) {
    }

    record ShelfBook(long id, long book) {
    }

    static class GetShelfBookOperation implements QueryOperation<ShelfBook, ShelfBook> {
        @Override
        public ShelfBook execute(ShelfBook input) {
            return input;
        }
    }

    static class GetSlugOperation implements QueryOperation<Slug, Slug> {
        @Override
        public Slug execute(Slug input) {
            return input;
        }
    }

    static class GetRootOperation implements QueryOperation<Void, String> {
        @Override
        public String execute(Void input) {
            return "root";
        }
    }
}
