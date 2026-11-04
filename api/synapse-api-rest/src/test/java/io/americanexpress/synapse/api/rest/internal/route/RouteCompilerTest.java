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
import jakarta.servlet.DispatcherType;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.internal.binding.BindingValidator;
import io.americanexpress.synapse.api.rest.internal.binding.RequestBinder;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.GetBookOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.ListBooksOperation;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class RouteCompilerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final RouteCompiler compiler = new RouteCompiler(new BindingValidator(),
            new RequestBinder(MAPPER, new DefaultFormattingConversionService()),
            new ResponseWriter(MAPPER, new DefaultFormattingConversionService()));

    @Test
    void mostSpecificTemplateWinsRegardlessOfDeclarationOrder() {
        RouterFunction<ServerResponse> routes = compiler
                .compile(List
                        .of(RestBindings
                                .builder()
                                .get("/books/{id}", new GetBookOperation())
                                .get("/books/latest", new ListBooksOperation())
                                .build()),
                        List.of());

        assertThat(operationFor(routes, "GET", "/books/latest")).isEqualTo("list-books");
        assertThat(operationFor(routes, "GET", "/books/7")).isEqualTo("get-book");
        // The implicit HEAD routes follow the same order.
        assertThat(operationFor(routes, "HEAD", "/books/latest")).isEqualTo("list-books");
        assertThat(operationFor(routes, "HEAD", "/books/7")).isEqualTo("get-book");
    }

    @Test
    void declaredHeadRouteComesBeforeEveryImplicitOne() {
        RouterFunction<ServerResponse> routes = compiler
                .compile(List
                        .of(RestBindings
                                .builder()
                                .get("/books/latest", new ListBooksOperation())
                                .head("/books/{id}", new GetBookOperation())
                                .build()),
                        List.of());

        // Implicit HEAD routes come after every declared route, so a declared HEAD template that also matches the
        // path of a more specific GET route takes its HEAD requests.
        assertThat(operationFor(routes, "HEAD", "/books/latest")).isEqualTo("get-book");
        assertThat(operationFor(routes, "GET", "/books/latest")).isEqualTo("list-books");
    }

    @Test
    void duplicateRoutesStillFailAfterOrdering() {
        RestBindings bindings = RestBindings
                .builder()
                .get("/books/latest", new ListBooksOperation())
                .get("/books/{id}", new GetBookOperation())
                .get("/books/{bookId}", new GetBookOperation())
                .build();

        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> compiler.compile(List.of(bindings), List.of()))
                .satisfies(e -> assertThat(e.description()).contains("GET /books/{bookId}", "same method and path"));
    }

    @Test
    void routesNeverMatchTheErrorDispatch() {
        RouterFunction<ServerResponse> routes = compiler
                .compile(List
                        .of(RestBindings
                                .builder()
                                .get("/{slug}", new GetSlugOperation())
                                .put("/{slug}", new PutSlugOperation())
                                .build()),
                        List.of());

        assertThat(operationFor(routes, "GET", "/error")).isEqualTo("get-slug");
        for (String method : List.of("GET", "HEAD", "PUT", "DELETE", "OPTIONS")) {
            MockHttpServletRequest request = new MockHttpServletRequest(method, "/error");
            request.setDispatcherType(DispatcherType.ERROR);
            assertThat(routes.route(ServerRequest.create(request, List.of()))).as(method).isEmpty();
        }
    }

    @Test
    void routesHoldNoMethodNotAllowedFallback() {
        RouterFunction<ServerResponse> routes = compiler
                .compile(List
                        .of(RestBindings
                                .builder()
                                .get("/books/{id}", new GetBookOperation())
                                .build()),
                        List.of());

        assertThat(handlerFor(routes, "DELETE", "/books/7")).isNull();
        assertThat(handlerFor(routes, "OPTIONS", "/books/7")).isNull();
    }

    private static @Nullable String operationFor(RouterFunction<ServerResponse> routes, String method,
            String path) {
        HandlerFunction<ServerResponse> handler = handlerFor(routes, method, path);
        return handler instanceof OperationHandler operation ? operation.route().operationName() : null;
    }

    private static @Nullable HandlerFunction<ServerResponse> handlerFor(RouterFunction<ServerResponse> routes,
            String method, String path) {
        return routes.route(ServerRequest.create(new MockHttpServletRequest(method, path), List.of())).orElse(null);
    }

    record Slug(String slug) {
    }

    static class GetSlugOperation implements QueryOperation<Slug, Slug> {
        @Override
        public Slug execute(Slug input) {
            return input;
        }
    }

    static class PutSlugOperation implements CommandOperation<Slug, Slug> {
        @Override
        public Slug execute(Slug input) {
            return input;
        }
    }
}
