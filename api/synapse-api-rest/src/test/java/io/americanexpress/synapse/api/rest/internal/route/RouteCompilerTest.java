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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
import io.americanexpress.synapse.api.rest.internal.paging.PageCursors;
import io.americanexpress.synapse.api.rest.internal.paging.PagingSettings;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.GetBookOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.ListBooksOperation;
import io.americanexpress.synapse.api.rest.internal.route.RouteFixtures.PagedBooksOperation;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class RouteCompilerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final RouteCompiler compiler = new RouteCompiler(new BindingValidator(PagingSettings.defaults()),
            new RequestBinder(MAPPER, new DefaultFormattingConversionService(), PagingSettings.defaults()),
            new ResponseWriter(MAPPER, new DefaultFormattingConversionService(), RouteFixtures.cursors(),
                    RouteFixtures.etags()),
            RouteFixtures.cursors(), () -> true);

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

    @Test
    void generatedCursorKeyIsReportedOnceAfterValidation() {
        PageCursors generated = RouteFixtures.cursors(null);
        RouteCompiler production = new RouteCompiler(new BindingValidator(PagingSettings.defaults()),
                new RequestBinder(MAPPER, new DefaultFormattingConversionService(), PagingSettings.defaults()),
                new ResponseWriter(MAPPER, new DefaultFormattingConversionService(), generated, RouteFixtures.etags()),
                generated,
                () -> true);
        RestBindings paged = RestBindings
                .builder()
                .get("/books", new PagedBooksOperation())
                .get("/books/{id}", new GetBookOperation())
                .build();
        RestBindings broken = RestBindings
                .builder()
                .get("/books", new PagedBooksOperation())
                .get("/books", new PagedBooksOperation())
                .build();

        List<ILoggingEvent> compiled = warnings(() -> production.compile(List.of(paged), List.of()));
        List<ILoggingEvent> failed = warnings(() -> assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> production.compile(List.of(broken), List.of())));
        List<ILoggingEvent> configured = warnings(() -> compiler.compile(List.of(paged), List.of()));

        assertThat(compiled)
                .singleElement()
                .satisfies(line -> assertThat(line.getFormattedMessage())
                        .startsWith("synapse.api.rest.paging.cursor.key is not set"));
        assertThat(failed).isEmpty();
        assertThat(configured).isEmpty();
    }

    @Test
    void generatedCursorKeyIsNotReportedWhenABindabilityCheckFails() {
        PageCursors generated = RouteFixtures.cursors(null);
        RouteCompiler production = new RouteCompiler(new BindingValidator(PagingSettings.defaults()),
                new RequestBinder(MAPPER, new DefaultFormattingConversionService(), PagingSettings.defaults()),
                new ResponseWriter(MAPPER, new DefaultFormattingConversionService(), generated, RouteFixtures.etags()),
                generated,
                () -> true);
        RestBindings unconvertible = RestBindings.builder().get("/pairs/{pair}", new PairPageOperation()).build();
        // The route passes the binding rules; only the bindability check, which runs after them, fails it.
        assertThat(new BindingValidator(PagingSettings.defaults()).validate(unconvertible.routes(), List.of()))
                .hasSize(1);

        List<ILoggingEvent> failed = warnings(() -> assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> production.compile(List.of(unconvertible), List.of()))
                .satisfies(e -> assertThat(e.description()).contains("can't be converted from a string")));

        assertThat(failed).isEmpty();
    }

    /** Runs the action and returns the WARN lines it logged on the REST logger. */
    private static List<ILoggingEvent> warnings(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger("io.americanexpress.synapse.api.rest");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().filter(line -> line.getLevel() == Level.WARN).toList();
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

    /** Two numbers: no conversion service makes one from a string. */
    record Pair(int a, int b) {
    }

    record PairPageRequest(Pair pair, PageQuery page) {
    }

    static class PairPageOperation implements QueryOperation<PairPageRequest, PageResult<Pair>> {
        @Override
        public PageResult<Pair> execute(PairPageRequest input) {
            return PageResult.of(List.of(input.pair()), false);
        }
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
