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
package io.americanexpress.synapse.api.rest;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.HttpMethod;
import org.springframework.util.unit.DataSize;

import io.americanexpress.synapse.api.rest.internal.binding.ConventionRules;
import io.americanexpress.synapse.api.rest.internal.binding.ResponseRules;
import io.americanexpress.synapse.api.rest.internal.binding.RestBindingsAccess;
import io.americanexpress.synapse.api.rest.internal.binding.RouteDeclaration;
import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.BookResult;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.CreateBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.CreateBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.GetBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.GetBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PagedBooksOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.SearchBooksOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.SortKey;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.UpdateBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.UpdateBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.WriteOptions;
import io.americanexpress.synapse.api.rest.internal.error.BookErrors;
import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.core.paging.PageResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class RestBindingsTest {

    private static final BookResult BOOK = new BookResult(new UUID(0, 7), "T", 3);

    @Test
    void builderKeepsRouteOrderAndOptions() {
        RestBindings bindings = RestBindings
                .builder()
                .get("/books/{id}", new GetBookOperation())
                .notFound(BookErrors.BOOK_NOT_FOUND)
                .post("/books", new CreateBookOperation())
                .created(book -> "/books/" + book.id())
                .build();

        List<RouteDefinition> routes = RestBindingsAccess.routes(bindings);
        assertThat(routes).hasSize(2);
        RouteDefinition get = routes.get(0);
        assertThat(get.method()).isEqualTo(HttpMethod.GET);
        assertThat(get.path()).isEqualTo("/books/{id}");
        assertThat(get.operationName()).isEqualTo("get-book");
        assertThat(get.inputType()).isEqualTo(GetBookRequest.class);
        assertThat(get.outputType()).isEqualTo(Optional.class);
        assertThat(get.outputValueType()).isEqualTo(BookResult.class);
        assertThat(get.response().notFound()).isEqualTo(BookErrors.BOOK_NOT_FOUND);
        assertThat(get.response().created()).isNull();
        RouteDefinition post = routes.get(1);
        assertThat(post.method()).isEqualTo(HttpMethod.POST);
        assertThat(post.operationName()).isEqualTo("create-book");
        assertThat(post.inputType()).isEqualTo(CreateBookRequest.class);
        assertThat(post.response().created()).isNotNull();
        assertThat(Objects.requireNonNull(post.response().created()).apply(BOOK)).isEqualTo("/books/" + BOOK.id());
        assertThat(post.sources()).isEmpty();
    }

    @Test
    void everyMethodStartsARouteFromTheBuilderOrTheRoute() {
        GetBookOperation getBook = new GetBookOperation();
        UpdateBookOperation updateBook = new UpdateBookOperation();

        List<RouteDefinition> routes = RestBindingsAccess
                .routes(RestBindings
                        .builder()
                        .head("/a/{id}", getBook)
                        .get("/b/{id}", getBook)
                        .head("/c/{id}", getBook)
                        .post("/d/{id}", updateBook)
                        .put("/e/{id}", updateBook)
                        .delete("/f/{id}", getBook)
                        .build());
        List<RouteDefinition> fromBuilder = RestBindingsAccess
                .routes(RestBindings
                        .builder()
                        .put("/g/{id}", updateBook)
                        .build());
        List<RouteDefinition> deleteFromBuilder = RestBindingsAccess
                .routes(RestBindings.builder().delete("/h/{id}", getBook).build());
        List<RouteDefinition> postFromBuilder = RestBindingsAccess
                .routes(RestBindings.builder().post("/i/{id}", updateBook).build());

        assertThat(routes)
                .extracting(RouteDefinition::method)
                .containsExactly(HttpMethod.HEAD, HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST, HttpMethod.PUT,
                        HttpMethod.DELETE);
        assertThat(fromBuilder.getFirst().method()).isEqualTo(HttpMethod.PUT);
        assertThat(deleteFromBuilder.getFirst().method()).isEqualTo(HttpMethod.DELETE);
        assertThat(postFromBuilder.getFirst().method()).isEqualTo(HttpMethod.POST);
    }

    @Test
    void nonPositiveMaxRequestSizeFailsFast() {
        for (DataSize size : List.of(DataSize.ofBytes(0), DataSize.ofBytes(-1))) {
            RouteSpec<?, ?> route = RestBindings.builder().post("/books", new CreateBookOperation());
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> route.maxRequestSize(size))
                    .withMessage("maxRequestSize must be positive but was " + size);
        }
        assertThat(RestBindings
                .builder()
                .post("/books", new CreateBookOperation())
                .maxRequestSize(
                        DataSize.ofBytes(1)))
                .isNotNull();
    }

    @Test
    void routeRecordsDeclarationsInOrderWithoutResolvingThem() {
        RouteDefinition route = RestBindingsAccess
                .routes(RestBindings
                        .builder()
                        .put("/books/{bookId}", new UpdateBookOperation())
                        .path("bookId", UpdateBookRequest::id)
                        .query("t", UpdateBookRequest::title)
                        .query(UpdateBookRequest::options)
                        .cookie("c", UpdateBookRequest::title)
                        .context(UpdateBookRequest::title, context -> context.correlationId())
                        .from(UpdateBookRequest::title, request -> "x")
                        .header("X-Title", BookResult::title)
                        .alsoInBody()
                        .body(BookResult::id)
                        .header("X-Rate", (UpdateBookRequest request) -> request.id())
                        .status(202)
                        .accepted(book -> "/jobs/" + book.id())
                        .emptyAs(204)
                        .maxRequestSize(DataSize.ofKilobytes(64))
                        .build())
                .getFirst();

        assertThat(route.declarations())
                .extracting(declaration -> declaration.getClass().getSimpleName())
                .containsExactly("Path", "Query", "Query", "Cookie", "Context", "Custom", "Header", "AlsoInBody",
                        "Body",
                        "Header");
        assertThat(route.declarations().get(1))
                .isInstanceOfSatisfying(RouteDeclaration.Query.class,
                        query -> assertThat(query.parameter()).isEqualTo("t"));
        assertThat(route.declarations().get(2))
                .isInstanceOfSatisfying(RouteDeclaration.Query.class,
                        query -> assertThat(query.parameter()).isNull());
        ResponseRules response = route.response();
        assertThat(response.status()).isEqualTo(202);
        assertThat(response.statusFunction()).isNull();
        assertThat(response.accepted()).isNotNull();
        assertThat(Objects.requireNonNull(response.accepted()).apply(BOOK)).isEqualTo("/jobs/" + BOOK.id());
        assertThat(response.emptyAs()).isEqualTo(204);
        assertThat(response.maxRequestSize()).isEqualTo(DataSize.ofKilobytes(64));
        assertThat(response.outputHeaders()).isEmpty();
        assertThat(response.bodyComponent()).isNull();
    }

    @Test
    void laterStatusDeclarationReplacesTheEarlierOne() {
        RouteDefinition computed = RestBindingsAccess
                .routes(RestBindings
                        .builder()
                        .post("/books", new CreateBookOperation())
                        .status(200)
                        .status(book -> book.remaining() > 0 ? 201 : 202)
                        .build())
                .getFirst();
        assertThat(computed.response().status()).isNull();
        assertThat(computed.response().statusFunction()).isNotNull();
        assertThat(Objects.requireNonNull(computed.response().statusFunction()).applyAsInt(BOOK)).isEqualTo(201);

        RouteDefinition fixed = RestBindingsAccess
                .routes(RestBindings
                        .builder()
                        .post("/books", new CreateBookOperation())
                        .status(book -> 202)
                        .status(200)
                        .build())
                .getFirst();
        assertThat(fixed.response().status()).isEqualTo(200);
        assertThat(fixed.response().statusFunction()).isNull();
    }

    @Test
    void resolvesTypesAndNameThroughProxies() {
        GetBookOperation target = new GetBookOperation();
        ProxyFactory classProxy = new ProxyFactory(target);
        classProxy.setProxyTargetClass(true);
        GetBookOperation cglib = (GetBookOperation) classProxy.getProxy();
        @SuppressWarnings("unchecked")
        QueryOperation<GetBookRequest, Optional<BookResult>> jdk = (QueryOperation<GetBookRequest, Optional<BookResult>>) new ProxyFactory(
                target).getProxy();

        List<RouteDefinition> routes = RestBindingsAccess
                .routes(RestBindings
                        .builder()
                        .get("/a/{id}", cglib)
                        .get("/b/{id}", jdk)
                        .build());

        assertThat(cglib.getClass()).isNotEqualTo(GetBookOperation.class);
        assertThat(java.lang.reflect.Proxy.isProxyClass(jdk.getClass())).isTrue();
        assertThat(routes).allSatisfy(route -> {
            assertThat(route.operationName()).isEqualTo("get-book");
            assertThat(route.inputType()).isEqualTo(GetBookRequest.class);
            assertThat(route.outputType()).isEqualTo(Optional.class);
            assertThat(route.outputValueType()).isEqualTo(BookResult.class);
        });
    }

    @Test
    void conventionsKeepTheirRules() {
        RestConventions conventions = RestConventions
                .builder()
                .query(WriteOptions.class)
                .header("X-Tenant", String.class)
                .responseHeader("X-Remaining", BookResult.class, BookResult::remaining)
                .build();

        assertThat(RestBindingsAccess.rules(conventions).queryRecords()).containsExactly(WriteOptions.class);
        assertThat(RestBindingsAccess.rules(conventions).headers()).singleElement().satisfies(rule -> {
            assertThat(rule.name()).isEqualTo("X-Tenant");
            assertThat(rule.componentType()).isEqualTo(String.class);
        });
        assertThat(RestBindingsAccess.rules(conventions).responseHeaders()).singleElement().satisfies(rule -> {
            assertThat(rule.name()).isEqualTo("X-Remaining");
            assertThat(rule.outputType()).isEqualTo(BookResult.class);
        });
    }

    @Test
    void searchRouteHasAListOutput() {
        RouteDefinition route = RestBindingsAccess
                .routes(RestBindings
                        .builder()
                        .get("/books", new SearchBooksOperation())
                        .build())
                .getFirst();

        assertThat(route.outputType()).isEqualTo(List.class);
        assertThat(route.outputValueType()).isEqualTo(BookResult.class);
    }

    @Test
    void sortableIsRecordedAsADeclaration() {
        List<RouteDeclaration> declarations = RestBindingsAccess
                .routes(RestBindings
                        .builder()
                        .get("/books", new PagedBooksOperation())
                        .sortable(SortKey.class)
                        .get("/all", new PagedBooksOperation())
                        .sortable("title", "year")
                        .build())
                .stream()
                .flatMap(route -> route.declarations().stream())
                .toList();

        assertThat(declarations)
                .containsExactly(new RouteDeclaration.Sortable(SortKey.class, List.of()),
                        new RouteDeclaration.Sortable(null, List.of("title", "year")));
    }

    @Test
    void versionPrefixesThePath() {
        List<RouteDefinition> routes = RestBindingsAccess
                .routes(RestBindings
                        .builder()
                        .get("/books/{id}", new GetBookOperation())
                        .version("2")
                        .get("/", new GetBookOperation())
                        .version("1.1")
                        .get("books", new GetBookOperation())
                        .version("3")
                        .get("/books", new SearchBooksOperation())
                        .build());

        RouteDefinition versioned = routes.get(0);
        assertThat(versioned.path()).isEqualTo("/v2/books/{id}");
        assertThat(versioned.version()).isEqualTo("2");
        assertThat(versioned.declaredPath()).isEqualTo("/books/{id}");
        assertThat(versioned.declarations()).containsExactly(new RouteDeclaration.Version("2"));
        RouteDefinition root = routes.get(1);
        assertThat(root.path()).isEqualTo("/v1.1");
        assertThat(root.version()).isEqualTo("1.1");
        assertThat(root.declaredPath()).isEqualTo("/");
        // A path without its leading slash is served under the version prefix as Spring serves it unversioned.
        RouteDefinition relative = routes.get(2);
        assertThat(relative.path()).isEqualTo("/v3/books");
        assertThat(relative.declaredPath()).isEqualTo("/books");
        RouteDefinition unversioned = routes.get(3);
        assertThat(unversioned.path()).isEqualTo("/books");
        assertThat(unversioned.version()).isNull();
        assertThat(unversioned.declaredPath()).isEqualTo("/books");
    }

    @Test
    void pagedRouteIsDeclaredWithoutPagingRules() {
        RouteDefinition route = RestBindingsAccess
                .routes(RestBindings
                        .builder()
                        .get("/books", new PagedBooksOperation())
                        .build())
                .getFirst();

        assertThat(route.outputType()).isEqualTo(PageResult.class);
        assertThat(route.outputValueType()).isEqualTo(BookResult.class);
        assertThat(route.pagedOutput()).isTrue();
        assertThat(route.paging()).isNull();
    }

    @Test
    void publicApiExposesNoInternalType() {
        // An internal type in a public signature becomes API that applications can reach without importing an
        // internal package, so Synapse reads the declarations through RestBindingsAccess instead.
        List<String> exposed = new ArrayList<>();
        Deque<Class<?>> types = new ArrayDeque<>(
                List.of(RestBindings.class, RestConventions.class, RestErrors.class, RouteSpec.class));
        while (!types.isEmpty()) {
            Class<?> type = types.pop();
            for (Class<?> nested : type.getDeclaredClasses()) {
                if (Modifier.isPublic(nested.getModifiers())) {
                    types.add(nested);
                }
            }
            for (Method method : type.getMethods()) {
                String signature = method.toGenericString();
                if (method.getDeclaringClass() != Object.class && signature.contains(".internal.")) {
                    exposed.add(signature);
                }
            }
        }

        assertThat(exposed).isEmpty();
    }

    @Test
    void accessIsRegisteredOnce() {
        // Initializes both classes, which register their access.
        RestBindings.builder().build();
        RestConventions.builder().build();

        assertThatIllegalStateException()
                .isThrownBy(() -> RestBindingsAccess.registerRoutes(bindings -> List.of()))
                .withMessage("The RestBindings access is already registered");
        assertThatIllegalStateException()
                .isThrownBy(() -> RestBindingsAccess
                        .registerRules(conventions -> new ConventionRules(List.of(), List.of(), List.of())))
                .withMessage("The RestConventions access is already registered");
    }
}
