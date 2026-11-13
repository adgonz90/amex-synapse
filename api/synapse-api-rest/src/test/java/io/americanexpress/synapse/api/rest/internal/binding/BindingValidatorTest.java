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

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.ResolvableType;
import org.springframework.core.test.tools.SourceFile;
import org.springframework.core.test.tools.TestCompiler;
import org.springframework.http.HttpMethod;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.RestConventions;
import io.americanexpress.synapse.api.rest.RouteSpec;
import io.americanexpress.synapse.api.rest.internal.binding.ResponseRules.OutputHeader;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.BookResult;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.BookShelf;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.ConditionFilterOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.ConditionFilterRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.ConditionalReadRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.CreateBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.CreateBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.CreateVersionedBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.DigestiblePageOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.EchoOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.FilteredPageOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.FilteredPageRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.GetBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.GetBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.GetBookShelfOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.GetConditionalBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.GetDataOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.GetEntityOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.GetFileOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.GetGrowOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.GetHolderOutputOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.GetPlainBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.GetRowOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.GetStrictConditionalBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.GetVersionedBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.GetWithBodyOnlyOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.GetWithNullableFilterOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.HeaderRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.HeadersOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.HolderPageOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.ListBookShelvesOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.ListOfPagesOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.ListVersionedBooksOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.LooseConditionOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.LooseConditionRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.OffsetFilter;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.OptionalPageOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.OtherRecord;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PageOfBookShelvesOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PageOfPagesOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PageSizeOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PageSizeRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PageVersionedBooksOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PagedBooksOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PagedBooksRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PatchBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PatchBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PingOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PlainVersionOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PlainVersionRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.Principal;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PrincipalBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PrincipalOnlyOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PrincipalPageOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PrincipalPageRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PrincipalRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.RemoveBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.RemoveBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.ReviseBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.ReviseBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.SaveEntityOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.SearchAllOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.SearchBooksOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.SearchBooksRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.SizeFilterOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.SizeFilterRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.SizeOnlyOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.SortKey;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.StrictConditionOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.StrictConditionRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.StrictConditionalReadRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.StrictPutBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.StrictReviseOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.StrictReviseRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.SupertypeConditionOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.SupertypeConditionRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.TenantId;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.TreeNode;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.TreePageOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.TwoPagesOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.UpdateBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.UpdateBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.VersionedBook;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.WriteOptions;
import io.americanexpress.synapse.api.rest.internal.error.BookErrors;
import io.americanexpress.synapse.api.rest.internal.paging.PagingSettings;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.binding.ComponentRef;
import io.americanexpress.synapse.core.conditional.VersionCondition;
import io.americanexpress.synapse.core.context.RequestContext;
import io.americanexpress.synapse.core.internal.binding.ResolvedComponent;
import io.americanexpress.synapse.core.operation.QueryOperation;
import io.americanexpress.synapse.core.operation.ServiceOperation;
import io.americanexpress.synapse.core.paging.PageQuery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class BindingValidatorTest {

    private final BindingValidator validator = new BindingValidator(PagingSettings.defaults());

    private final GetBookOperation getBook = new GetBookOperation();

    private final SearchBooksOperation searchBooks = new SearchBooksOperation();

    private final CreateBookOperation createBook = new CreateBookOperation();

    private final UpdateBookOperation updateBook = new UpdateBookOperation();

    private final GetVersionedBookOperation getVersionedBook = new GetVersionedBookOperation();

    private final CreateVersionedBookOperation createVersionedBook = new CreateVersionedBookOperation();

    private final ReviseBookOperation reviseBook = new ReviseBookOperation();

    @Test
    void pathVariableWithoutComponentFails() {
        assertFails(RestBindings.builder().get("/books/{bookId}", getBook).build(),
                "REST route GET /books/{bookId} (get-book): ", "{bookId}");
    }

    @Test
    void pathVariableNamingAComponentBoundByAConventionSaysSo() {
        List<RouteDefinition> routes = RestBindingsAccess
                .routes(RestBindings.builder().put("/books/{id}/{options}", updateBook).build());
        List<RestConventions> conventions = List.of(RestConventions.builder().query(WriteOptions.class).build());

        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> validator.validate(routes, conventions))
                .satisfies(e -> {
                    assertThat(e.description())
                            .contains("{options}",
                                    "UpdateBookRequest.options is bound by a convention to the query string");
                    assertThat(e.action()).contains(".path(\"options\", UpdateBookRequest::options)");
                });
    }

    @Test
    void pathVariableNamingAComponentBoundOnTheRouteSaysSo() {
        assertFails(
                RestBindings.builder().get("/books/{author}", searchBooks).query(SearchBooksRequest::author).build(),
                "{author}", "SearchBooksRequest.author is bound on the route to query parameter author");
    }

    @Test
    void declaredPathVariableBinds() {
        RouteDefinition route = single(
                RestBindings.builder().get("/books/{bookId}", getBook).path("bookId", GetBookRequest::id).build());

        assertThat(route.sources()).singleElement().satisfies(source -> {
            assertThat(source).isInstanceOf(InputSource.Path.class);
            assertThat(((InputSource.Path) source).variable()).isEqualTo("bookId");
            assertThat(source.component().name()).isEqualTo("id");
        });
    }

    @Test
    void pathDeclarationForAVariableNotInTheTemplateFails() {
        assertFails(RestBindings.builder().get("/books/{id}", getBook).path("bookId", GetBookRequest::id).build(),
                "GET /books/{id} (get-book)", "{bookId}", "not in the path template");
    }

    @Test
    void componentOfAnotherTypeFails() {
        assertFails(RestBindings.builder().get("/books/{id}", getBook).header("X-Rate", OtherRecord::value).build(),
                "OtherRecord.value", "is not part of the input or output type");
        assertFails(RestBindings.builder().get("/books/{id}", getBook).body(OtherRecord::value).build(),
                "is not part of the input or output type");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void inputOnlyDeclarationOfAnOutputComponentFails() {
        ComponentRef<BookResult, Integer> output = BookResult::remaining;
        RouteSpec raw = RestBindings.builder().get("/books/{id}", getBook);

        assertFails(raw.query(output).build(), "BookResult.remaining", "is not part of the input type");
    }

    @Test
    void twoSourcesForOneComponentFail() {
        assertFails(RestBindings
                .builder()
                .get("/books", searchBooks)
                .query(SearchBooksRequest::author)
                .header("X-Author", SearchBooksRequest::author)
                .build(), "SearchBooksRequest.author", "two sources");
    }

    @Test
    void outputComponentMappedTwiceFails() {
        assertFails(RestBindings
                .builder()
                .get("/books/{id}", getBook)
                .header("X-Remaining", BookResult::remaining)
                .header("X-Left", BookResult::remaining)
                .build(), "BookResult.remaining", "twice");
        assertFails(RestBindings
                .builder()
                .get("/books/{id}", getBook)
                .body(BookResult::title)
                .body(BookResult::id)
                .build(), "response body", "twice");
    }

    @Test
    void nonNullableComponentWithoutSourceFails() {
        assertFails(RestBindings.builder().get("/books", new GetWithBodyOnlyOperation()).build(),
                "GET /books (get-with-body-only)", "GetWithBodyOnlyRequest.filter", "has no source");

        RouteDefinition route = single(
                RestBindings.builder().get("/books", new GetWithNullableFilterOperation()).build());
        assertThat(route.sources()).isEmpty();
    }

    @Test
    void commandOnGetFails() {
        assertFails(RestBindings.builder().get("/books", createBook).build(), "GET /books (create-book)",
                "CommandOperation", "GET");
        assertFails(RestBindings.builder().head("/books", createBook).build(), "CommandOperation", "HEAD");
    }

    @Test
    void entityTypeFails() {
        assertFails(RestBindings.builder().get("/books/{id}", new GetEntityOperation()).build(), "persistence entity",
                "BookEntity", "@jakarta.persistence.Entity");
        assertFails(RestBindings.builder().post("/books", new SaveEntityOperation()).build(), "input type",
                "persistence entity");
        assertFails(RestBindings.builder().get("/books/{id}", new GetDataOperation()).build(), "persistence entity",
                "implements io.americanexpress.synapse.data.restfixture.DataCapability");
        assertFails(RestBindings.builder().get("/books/{id}", new GetRowOperation()).build(), "persistence entity",
                "extends io.americanexpress.synapse.data.restfixture.DataRow");
    }

    @Test
    void duplicateRouteFails() {
        assertFails(RestBindings
                .builder()
                .get("/books/{id}", getBook)
                .get("/books/{bookId}", new GetPlainBookOperation())
                .path("bookId", GetBookRequest::id)
                .build(), "GET /books/{bookId} (get-plain-book)", "same method and path", "get-book");

        List<RouteDefinition> routes = validator
                .validate(RestBindingsAccess
                        .routes(RestBindings
                                .builder()
                                .get("/books/{id}", getBook)
                                .delete("/books/{id}", new GetPlainBookOperation())
                                .build()),
                        List.of());
        assertThat(routes).extracting(RouteDefinition::method).containsExactly(HttpMethod.GET, HttpMethod.DELETE);

        List<RouteDefinition> files = validator
                .validate(RestBindingsAccess
                        .routes(RestBindings
                                .builder()
                                .get("/files/{name}.{ext}", new GetFileOperation())
                                .get("/files/{id}", new GetPlainBookOperation())
                                .build()),
                        List.of());
        assertThat(files).extracting(RouteDefinition::path).containsExactly("/files/{name}.{ext}", "/files/{id}");
    }

    @Test
    void notFoundOnNonOptionalFails() {
        assertFails(RestBindings
                .builder()
                .get("/books/{id}", new GetPlainBookOperation())
                .notFound(BookErrors.BOOK_NOT_FOUND)
                .build(), ".notFound", "Optional", "BookResult");
        assertFails(RestBindings.builder().get("/books/{id}", new GetPlainBookOperation()).emptyAs(204).build(),
                ".emptyAs", "Optional");

        RouteDefinition route = single(
                RestBindings.builder().get("/books/{id}", getBook).notFound(BookErrors.BOOK_NOT_FOUND).build());
        assertThat(route.response().notFound()).isEqualTo(BookErrors.BOOK_NOT_FOUND);
    }

    @Test
    void conventionBindsComponentsByType() {
        RestBindings bindings = RestBindings.builder().put("/books/{id}", updateBook).build();

        RouteDefinition withoutConvention = validator
                .validate(RestBindingsAccess.routes(bindings), List.of())
                .getFirst();
        assertThat(source(withoutConvention, "options")).isInstanceOf(InputSource.Body.class);

        RouteDefinition route = validator
                .validate(RestBindingsAccess.routes(bindings),
                        List.of(RestConventions.builder().query(WriteOptions.class).build()))
                .getFirst();
        assertThat(route.sources())
                .extracting(source -> source.component().name())
                .containsExactly("id", "title", "options");
        assertThat(source(route, "id")).isEqualTo(new InputSource.Path(component(route, "id"), "id"));
        assertThat(source(route, "title")).isInstanceOf(InputSource.Body.class);
        assertThat(source(route, "options"))
                .isInstanceOfSatisfying(InputSource.QueryRecord.class,
                        query -> assertThat(query.components())
                                .extracting(ResolvedComponent::name)
                                .containsExactly("dryRun", "reason"));
    }

    @Test
    void routeDeclarationBeatsConvention() {
        RouteDefinition route = single(RestBindings
                .builder()
                .put("/books/{id}", updateBook)
                .header("X-Options", UpdateBookRequest::options)
                .build(), RestConventions.builder().query(WriteOptions.class).build());

        assertThat(source(route, "options"))
                .isEqualTo(new InputSource.Header(component(route, "options"), "X-Options"));
    }

    @Test
    void twoConventionsForOneComponentFail() {
        assertFails(RestBindings.builder().put("/books/{id}", updateBook).build(),
                List
                        .of(RestConventions.builder().query(WriteOptions.class).build(),
                                RestConventions.builder().header("X-Options", WriteOptions.class).build()),
                "UpdateBookRequest.options", "two sources");
    }

    @Test
    void headerConventionBindsByType() {
        RouteDefinition route = single(RestBindings
                .builder()
                .get("/headers", new HeadersOperation())
                .header("X-Channel", HeaderRequest::channel)
                .cookie("session", HeaderRequest::session)
                .context(HeaderRequest::requestedBy, RequestContext::correlationId)
                .from(HeaderRequest::region, request -> "eu")
                .build(), RestConventions.builder().header("X-Tenant", TenantId.class).build());

        assertThat(route.sources())
                .extracting(source -> source.component().name())
                .containsExactly("channel", "session", "requestedBy", "region", "tenant");
        assertThat(source(route, "channel"))
                .isEqualTo(new InputSource.Header(component(route, "channel"), "X-Channel"));
        assertThat(source(route, "session")).isEqualTo(new InputSource.Cookie(component(route, "session"), "session"));
        assertThat(source(route, "requestedBy")).isInstanceOf(InputSource.Context.class);
        assertThat(source(route, "region")).isInstanceOf(InputSource.Custom.class);
        assertThat(source(route, "tenant")).isEqualTo(new InputSource.Header(component(route, "tenant"), "X-Tenant"));
    }

    @Test
    void headerConventionMatchesPrimitivesAndTheirWrappers() {
        for (Class<?> type : List.of(Integer.class, int.class)) {
            RouteDefinition route = single(RestBindings.builder().get("/books", searchBooks).build(),
                    RestConventions.builder().header("X-Count", type).build());

            assertThat(source(route, "page")).isEqualTo(new InputSource.Header(component(route, "page"), "X-Count"));
            assertThat(source(route, "limit"))
                    .isEqualTo(new InputSource.Header(component(route, "limit"), "X-Count"));
        }
    }

    @Test
    void headBindsSimpleComponentsFromTheQueryString() {
        RouteDefinition route = single(RestBindings.builder().head("/books", searchBooks).build());

        assertThat(route.sources())
                .allSatisfy(source -> assertThat(source).isInstanceOf(InputSource.Query.class))
                .hasSize(7);
    }

    @Test
    void getBindsSimpleComponentsFromTheQueryString() {
        RouteDefinition route = single(RestBindings.builder().get("/books", searchBooks).build());

        assertThat(route.sources())
                .allSatisfy(source -> assertThat(source).isInstanceOf(InputSource.Query.class))
                .extracting(source -> ((InputSource.Query) source).parameter())
                .containsExactly("author", "page", "limit", "available", "genre", "since", "tags");
        assertThat(route.inputType()).isEqualTo(SearchBooksRequest.class);
        assertThat(route.outputType()).isEqualTo(List.class);
        assertThat(route.outputValueType()).isEqualTo(BookResult.class);
    }

    @Test
    void declaredQueryUsesTheGivenNameOrBuildsARecord() {
        RouteDefinition route = single(RestBindings
                .builder()
                .post("/books/{id}", updateBook)
                .query("t", UpdateBookRequest::title)
                .query(UpdateBookRequest::options)
                .build());

        assertThat(source(route, "title")).isEqualTo(new InputSource.Query(component(route, "title"), "t"));
        assertThat(source(route, "options")).isInstanceOf(InputSource.QueryRecord.class);
        assertThat(
                single(RestBindings
                        .builder()
                        .delete("/books", new GetPlainBookOperation())
                        .query(GetBookRequest::id)
                        .build())
                        .sources())
                .singleElement()
                .isInstanceOf(InputSource.Query.class);
    }

    @Test
    void simpleComponentsAreNotImplicitQueryParametersOnOtherMethods() {
        assertFails(RestBindings.builder().delete("/books", searchBooks).build(), "DELETE /books",
                "SearchBooksRequest.page", "has no source");
    }

    @Test
    void postBindsTheRestFromTheBody() {
        RouteDefinition route = single(RestBindings.builder().post("/books", createBook).build());

        assertThat(route.sources()).containsExactly(new InputSource.Body(component(route, "title")));
    }

    @Test
    void bodyComponentTakesTheWholeBody() {
        RouteDefinition route = single(
                RestBindings
                        .builder()
                        .put("/books/{id}", new PatchBookOperation())
                        .body(PatchBookRequest::changes)
                        .build());

        assertThat(route.sources())
                .containsExactly(new InputSource.Path(component(route, "id"), "id"),
                        new InputSource.BodyComponent(component(route, "changes")));
    }

    @Test
    void bodyComponentLeavesNoImplicitBody() {
        assertFails(RestBindings
                .builder()
                .put("/books/{x}", new PatchBookOperation())
                .body(PatchBookRequest::changes)
                .path("x", PatchBookRequest::changes)
                .build(), "PatchBookRequest.changes", "two sources");
        assertFails(RestBindings
                .builder()
                .put("/books", new PatchBookOperation())
                .body(PatchBookRequest::changes)
                .build(), "PatchBookRequest.id", "has no source");
    }

    @Test
    void requestBodyOnlyOnPostAndPut() {
        assertFails(RestBindings
                .builder()
                .delete("/books/{id}", new PatchBookOperation())
                .body(PatchBookRequest::changes)
                .build(), "DELETE /books/{id}", "request body", "POST and PUT");
    }

    @Test
    void outputHeadersAndBodyComponentResolve() {
        RouteDefinition route = single(RestBindings
                .builder()
                .get("/books/{id}", getBook)
                .header("X-Remaining", BookResult::remaining)
                .alsoInBody()
                .header("X-Title", BookResult::title)
                .body(BookResult::id)
                .build());

        assertThat(route.sources()).singleElement().isInstanceOf(InputSource.Path.class);
        assertThat(route.response().outputHeaders())
                .extracting(OutputHeader::name, header -> header.component().name(), OutputHeader::alsoInBody)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("X-Remaining", "remaining", true),
                        org.assertj.core.groups.Tuple.tuple("X-Title", "title", false));
        ResolvedComponent body = Objects.requireNonNull(route.response().bodyComponent());
        assertThat(body).isNotNull();
        assertThat(body.name()).isEqualTo("id");
    }

    @Test
    void conventionResponseHeadersApplyByOutputType() {
        RestConventions conventions = RestConventions
                .builder()
                .responseHeader("X-Remaining", BookResult.class, BookResult::remaining)
                .responseHeader("X-Title", BookResult.class, BookResult::title)
                .responseHeader("X-Other", OtherRecord.class, OtherRecord::value)
                .build();
        List<RouteDefinition> routes = validator
                .validate(RestBindingsAccess
                        .routes(RestBindings
                                .builder()
                                .get("/books/{id}", getBook)
                                .header("X-Name", BookResult::title)
                                .get("/books", searchBooks)
                                .get("/ping", new PingOperation())
                                .build()),
                        List.of(conventions));

        assertThat(routes.get(0).response().outputHeaders())
                .extracting(OutputHeader::name)
                .containsExactly("X-Name", "X-Remaining");
        assertThat(routes.get(1).response().outputHeaders()).isEmpty();
        assertThat(routes.get(2).response().outputHeaders()).isEmpty();
    }

    @Test
    void outputHeaderOrBodyOnAListOutputFails() {
        assertFails(RestBindings
                .builder()
                .get("/books", searchBooks)
                .header("X-Remaining", BookResult::remaining)
                .build(), "REST route GET /books (search-books): ",
                "an output header needs a single value, but the output is a List");
        assertFails(RestBindings.builder().get("/books", searchBooks).body(BookResult::title).build(),
                "a body component needs a single value, but the output is a List");
    }

    @Test
    void twoConventionHeadersForOneOutputComponentFail() {
        assertFails(RestBindings.builder().get("/books/{id}", getBook).build(),
                List
                        .of(RestConventions
                                .builder()
                                .responseHeader("X-Remaining", BookResult.class, BookResult::remaining)
                                .build(),
                                RestConventions
                                        .builder()
                                        .responseHeader("X-Left", BookResult.class, BookResult::remaining)
                                        .build()),
                "BookResult.remaining", "is mapped twice", "header X-Remaining", "header X-Left");
    }

    @Test
    void twoOutputHeadersWithTheSameNameFail() {
        assertFails(RestBindings
                .builder()
                .get("/books/{id}", getBook)
                .header("X-Book", BookResult::remaining)
                .header("x-book", BookResult::title)
                .build(), "response header x-book is mapped twice", "BookResult.remaining", "BookResult.title");
        assertFails(RestBindings.builder().get("/books/{id}", getBook).header("X-Book", BookResult::remaining).build(),
                List
                        .of(RestConventions
                                .builder()
                                .responseHeader("X-BOOK", BookResult.class, BookResult::title)
                                .build()),
                "response header X-BOOK is mapped twice");
    }

    @Test
    void statusOutsideTheHttpRangeFails() {
        for (int status : new int[]{99, 600, 0, -1}) {
            assertFails(RestBindings.builder().post("/books", createBook).status(status).build(),
                    "REST route POST /books (create-book): ", ".status(" + status + ")",
                    "not an HTTP status (100-599)");
            assertFails(RestBindings.builder().get("/books/{id}", getBook).emptyAs(status).build(),
                    "REST route GET /books/{id} (get-book): ", ".emptyAs(" + status + ")",
                    "not an HTTP status (100-599)");
        }

        for (int status : new int[]{100, 204, 599}) {
            assertThat(single(RestBindings.builder().post("/books", createBook).status(status).build())
                    .response()
                    .status()).isEqualTo(status);
            assertThat(single(RestBindings.builder().get("/books/{id}", getBook).emptyAs(status).build())
                    .response()
                    .emptyAs()).isEqualTo(status);
        }
    }

    @Test
    void statusWithCreatedOrAcceptedFails() {
        assertFails(RestBindings
                .builder()
                .post("/books", createBook)
                .created(book -> "/books/" + book.id())
                .status(201)
                .build(), "REST route POST /books (create-book): ", "status(...) conflicts with created(...)");
        assertFails(RestBindings
                .builder()
                .post("/books", createBook)
                .accepted(book -> "/jobs/" + book.id())
                .status(book -> 202)
                .build(), "status(...) conflicts with accepted(...)");
    }

    @Test
    void outputHeaderNeedsASimpleType() {
        RouteDefinition route = single(RestBindings
                .builder()
                .get("/shelves/{id}", new GetShelfOperation())
                .header("X-Label", Shelf::label)
                .header("X-Counts", Shelf::counts)
                .header("X-Name", Shelf::name)
                .build());
        assertThat(route.response().outputHeaders())
                .extracting(OutputHeader::name)
                .containsExactly("X-Label", "X-Counts", "X-Name");

        assertFails(
                RestBindings
                        .builder()
                        .get("/shelves/{id}", new GetShelfOperation())
                        .header("X-Book", Shelf::book)
                        .build(),
                "REST route GET /shelves/{id} (get-shelf): ", "output header X-Book needs a simple type",
                "Shelf.book");
        assertFails(
                RestBindings.builder().get("/shelves/{id}", new GetShelfOperation()).header("X-Id", Shelf::id).build(),
                "output header X-Id needs a simple type");
        assertFails(RestBindings.builder().get("/shelves/{id}", new GetShelfOperation()).build(),
                List.of(RestConventions.builder().responseHeader("X-Book", Shelf.class, Shelf::book).build()),
                "output header X-Book needs a simple type");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Location", "link", "CONTENT-TYPE", "Content-Length"})
    void reservedOutputHeaderNamesFail(String name) {
        assertFails(RestBindings.builder().get("/books/{id}", getBook).header(name, BookResult::title).build(),
                "REST route GET /books/{id} (get-book): ", "the output header " + name + " is reserved");
    }

    /** An output with components of every kind an output header may or may not take. */
    public record Shelf(UUID id, String name, BookResult book, Optional<String> label, List<Integer> counts) {
    }

    public static class GetShelfOperation implements QueryOperation<GetBookRequest, Shelf> {
        @Override
        public Shelf execute(GetBookRequest input) {
            throw new UnsupportedOperationException("not called");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"org.springframework.data.mongodb.core.mapping.Document",
            "org.springframework.data.relational.core.mapping.Table"})
    void storeAnnotationsMarkAPersistenceEntity(String annotation) {
        int dot = annotation.lastIndexOf('.');
        SourceFile annotationType = SourceFile
                .of("package " + annotation.substring(0, dot) + ";\n"
                        + "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n"
                        + "public @interface " + annotation.substring(dot + 1) + " {}\n");
        SourceFile row = SourceFile.of("package test;\n@" + annotation + "\npublic record Row(String id) {}\n");
        SourceFile operation = SourceFile.of("""
                package test;
                public class SaveRowOperation
                        implements io.americanexpress.synapse.core.operation.CommandOperation<Row, String> {
                    public String execute(Row input) { return input.id(); }
                }
                """);

        TestCompiler.forSystem().withSources(annotationType, row, operation).compile(compiled -> {
            ServiceOperation<?, ?> saveRow = compiled.getInstance(ServiceOperation.class, "test.SaveRowOperation");
            assertFails(RestBindings.builder().post("/rows", saveRow).build(), "the input type test.Row",
                    "persistence entity", "annotated @" + annotation);
        });
    }

    @Test
    void alsoInBodyWithoutOutputHeaderFails() {
        assertFails(RestBindings.builder().get("/books/{id}", getBook).alsoInBody().build(), ".alsoInBody()",
                "output .header");
        assertFails(RestBindings
                .builder()
                .get("/headers", new HeadersOperation())
                .header("X-Channel", HeaderRequest::channel)
                .alsoInBody()
                .build(), ".alsoInBody()");
    }

    @Test
    void inputMustBeARecordOrVoid() {
        assertFails(RestBindings.builder().get("/echo", new EchoOperation()).build(), "GET /echo (echo)",
                "java.lang.String", "not a record or Void");

        RouteDefinition route = single(RestBindings.builder().get("/ping", new PingOperation()).build());
        assertThat(route.inputType()).isEqualTo(Void.class);
        assertThat(route.sources()).isEmpty();
    }

    @Test
    void componentReferenceThatIsNotAMethodReferenceNamesTheRoute() {
        assertFails(RestBindings
                .builder()
                .get("/books/{id}", getBook)
                .header("X-Id", (GetBookRequest r) -> r.id())
                .build(), "GET /books/{id} (get-book)", "method reference to a record component");
        assertFails(RestBindings.builder().get("/books/{id}", getBook).build(),
                List.of(RestConventions.builder().responseHeader("X", BookResult.class, b -> b.title()).build()),
                "REST conventions", "method reference to a record component");
    }

    @Test
    void queryConventionMustNameARecord() {
        assertFails(RestBindings.builder().get("/books/{id}", getBook).build(),
                List.of(RestConventions.builder().query(String.class).build()), "REST conventions",
                "java.lang.String", "not a record");
    }

    @Test
    void invalidPathTemplateFails() {
        assertFails(RestBindings.builder().get("/books/{id", getBook).build(), "GET /books/{id (get-book)",
                "not a valid path template");
    }

    @Test
    void failuresCarryAnAction() {
        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> validator
                        .validate(RestBindingsAccess.routes(RestBindings.builder().get("/books", createBook).build()),
                                List.of()))
                .satisfies(e -> assertThat(e.action()).contains("POST"));
    }

    @Test
    void validRoutesKeepTheirDeclaredOptions() {
        RouteDefinition route = single(RestBindings
                .builder()
                .post("/books", createBook)
                .created(book -> "/books/" + book.id())
                .maxRequestSize(org.springframework.util.unit.DataSize.ofKilobytes(2))
                .build());

        assertThat(route.method()).isEqualTo(HttpMethod.POST);
        assertThat(route.path()).isEqualTo("/books");
        assertThat(route.operationName()).isEqualTo("create-book");
        assertThat(route.inputType()).isEqualTo(CreateBookRequest.class);
        assertThat(route.outputType()).isEqualTo(BookResult.class);
        assertThat(route.response().created()).isNotNull();
        assertThat(route.response().maxRequestSize()).isEqualTo(org.springframework.util.unit.DataSize.ofKilobytes(2));
    }

    @Test
    void pageQueryComponentBindsFromPagingParametersOnGetAndPost() {
        PagedBooksOperation pagedBooks = new PagedBooksOperation();
        List<RouteDefinition> routes = validator
                .validate(RestBindingsAccess
                        .routes(RestBindings
                                .builder()
                                .get("/books", pagedBooks)
                                .sortable(SortKey.class)
                                .post("/books", pagedBooks)
                                .build()),
                        List.of());

        RouteDefinition get = routes.get(0);
        assertThat(source(get, "page")).isInstanceOf(InputSource.Page.class);
        assertThat(source(get, "author")).isInstanceOf(InputSource.Query.class);
        PagingRules paging = Objects.requireNonNull(get.paging());
        assertThat(paging.component()).isEqualTo(component(get, "page"));
        assertThat(Objects.requireNonNull(paging.sortKeys()).wireToProperty())
                .containsExactly(entry("title", "TITLE"), entry("year", "PUBLISHED_YEAR"),
                        entry("authorName", "AUTHOR_NAME"));
        assertThat(get.pagedOutput()).isTrue();
        assertThat(get.outputValueType()).isEqualTo(BookResult.class);

        RouteDefinition post = routes.get(1);
        assertThat(source(post, "page")).isInstanceOf(InputSource.Page.class);
        assertThat(source(post, "author")).isInstanceOf(InputSource.Body.class);
        assertThat(Objects.requireNonNull(post.paging()).sortKeys()).isNull();
    }

    @Test
    void pageQueryCanBeTheWholeInput() {
        RouteDefinition route = single(RestBindings.builder().get("/all", new SearchAllOperation()).build());

        assertThat(route.sources()).isEmpty();
        PagingRules paging = Objects.requireNonNull(route.paging());
        assertThat(paging.component()).isNull();
        assertThat(paging.sortKeys()).isNull();

        RouteDefinition sorted = single(
                RestBindings.builder().post("/all", new SearchAllOperation()).sortable("title", "year").build());
        assertThat(sorted.sources()).isEmpty();
        assertThat(Objects.requireNonNull(Objects.requireNonNull(sorted.paging()).sortKeys()).allowed())
                .isEqualTo("title, year");
    }

    @Test
    void wholePageQueryInputTakesNoDeclarationsOrPathVariables() {
        assertFails(RestBindings.builder().get("/all", new SearchAllOperation()).query(PageQuery::size).build(),
                "REST route GET /all (search-all): ", "binds from the paging parameters");
        assertFails(RestBindings.builder().get("/all/{size}", new SearchAllOperation()).build(), "{size}");
    }

    @Test
    void twoPageQueryComponentsFail() {
        assertFails(RestBindings.builder().get("/books", new TwoPagesOperation()).build(),
                "REST route GET /books (two-pages): ", "more than one PageQuery", "TwoPagesRequest.a",
                "TwoPagesRequest.b");
    }

    @Test
    void declaredSourceForPageQueryFails() {
        PagedBooksOperation pagedBooks = new PagedBooksOperation();
        assertFails(RestBindings.builder().get("/books", pagedBooks).query(PagedBooksRequest::page).build(),
                "PagedBooksRequest.page", "binds from the paging parameters");
        assertFails(
                RestBindings.builder().get("/books", pagedBooks).header("X-Page", PagedBooksRequest::page).build(),
                "binds from the paging parameters", "header X-Page");
        assertFails(RestBindings.builder().post("/books", pagedBooks).body(PagedBooksRequest::page).build(),
                "binds from the paging parameters");
        assertFails(RestBindings
                .builder()
                .get("/books", pagedBooks)
                .from(PagedBooksRequest::page, request -> PageQuery.first(5))
                .build(), "binds from the paging parameters");
        assertFails(RestBindings.builder().get("/books", pagedBooks).build(),
                List.of(RestConventions.builder().query(PageQuery.class).build()), "PagedBooksRequest.page",
                "binds from the paging parameters", "convention");
        assertFails(RestBindings.builder().get("/books", pagedBooks).build(),
                List.of(RestConventions.builder().header("X-Page", PageQuery.class).build()),
                "binds from the paging parameters");
    }

    @Test
    void queryComponentWithPagingNameFails() {
        assertFails(RestBindings.builder().get("/books", new SizeFilterOperation()).build(),
                "REST route GET /books (size-filter): ", "query parameter size", "SizeFilterRequest.size",
                "paging parameter");
        assertFails(RestBindings
                .builder()
                .post("/books", new SizeFilterOperation())
                .query(SizeFilterRequest::size)
                .build(), "query parameter size", "paging parameter");
        assertFails(RestBindings
                .builder()
                .get("/books", new PagedBooksOperation())
                .query("cursor", PagedBooksRequest::author)
                .build(), "query parameter cursor", "paging parameter");
        assertFails(
                RestBindings
                        .builder()
                        .get("/books", new FilteredPageOperation())
                        .query(FilteredPageRequest::filter)
                        .build(),
                "query parameter offset", "OffsetFilter.offset", "paging parameter");
        assertFails(RestBindings.builder().get("/books", new FilteredPageOperation()).build(),
                List.of(RestConventions.builder().query(OffsetFilter.class).build()), "query parameter offset",
                "paging parameter");
    }

    @Test
    void pagingNameClashIgnoredWithoutPageQuery() {
        RouteDefinition route = single(RestBindings.builder().get("/books", new SizeOnlyOperation()).build());

        assertThat(source(route, "size"))
                .isInstanceOfSatisfying(InputSource.Query.class,
                        query -> assertThat(query.parameter()).isEqualTo("size"));
        assertThat(route.paging()).isNull();
        assertThat(route.pagedOutput()).isFalse();
    }

    @Test
    void renamedPagingParameterIsChecked() {
        PagingSettings defaults = PagingSettings.defaults();
        BindingValidator renamed = new BindingValidator(new PagingSettings(defaults.cursor(), defaults.offset(),
                "page_size", defaults.sort(), defaults.maxOffset(), defaults.defaultSize(), defaults.maxSize()));
        List<RouteDefinition> routes = RestBindingsAccess
                .routes(RestBindings
                        .builder()
                        .get("/books", new PageSizeOperation())
                        .query("page_size", PageSizeRequest::pageSize)
                        .build());

        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> renamed.validate(routes, List.of()))
                .satisfies(e -> assertThat(e.description())
                        .contains("query parameter page_size", "PageSizeRequest.pageSize", "paging parameter"));
        // With the default names, page_size is a filter like any other.
        assertThat(source(single(RestBindings
                .builder()
                .get("/books", new PageSizeOperation())
                .query("page_size", PageSizeRequest::pageSize)
                .build()), "pageSize")).isInstanceOf(InputSource.Query.class);
    }

    @Test
    void sortableNeedsPageQuery() {
        assertFails(RestBindings.builder().get("/books/{id}", getBook).sortable("title").build(),
                "REST route GET /books/{id} (get-book): ", "sortable", "PageQuery");
    }

    @Test
    void sortableTwiceFails() {
        assertFails(RestBindings
                .builder()
                .get("/books", new PagedBooksOperation())
                .sortable("title")
                .sortable(SortKey.class)
                .build(), "REST route GET /books (paged-books): ", ".sortable", "twice");
    }

    @Test
    void sortableKeysAreChecked() {
        PagedBooksOperation pagedBooks = new PagedBooksOperation();
        assertFails(RestBindings.builder().get("/books", pagedBooks).sortable().build(),
                "REST route GET /books (paged-books): ", "no sort keys");
        assertFails(RestBindings.builder().get("/books", pagedBooks).sortable("title", "-year").build(),
                "REST route GET /books (paged-books): ", "-year", "not a valid wire name");
        assertFails(RestBindings.builder().get("/books", pagedBooks).sortable("title", "title").build(),
                "REST route GET /books (paged-books): ", "title", "two sort keys");
    }

    @Test
    void pageResultRejectsOutputHeaderAndBody() {
        PagedBooksOperation pagedBooks = new PagedBooksOperation();
        assertFails(RestBindings.builder().get("/books", pagedBooks).header("X-Total", BookResult::title).build(),
                "REST route GET /books (paged-books): ", "an output header needs a single value",
                "the output is a PageResult");
        assertFails(RestBindings.builder().get("/books", pagedBooks).body(BookResult::title).build(),
                "a body component needs a single value", "the output is a PageResult");
        assertFails(RestBindings.builder().get("/books", pagedBooks).alsoInBody().build(), ".alsoInBody()");
    }

    @Test
    void conventionResponseHeadersSkipPageResult() {
        RouteDefinition route = single(RestBindings.builder().get("/books", new PagedBooksOperation()).build(),
                RestConventions.builder().responseHeader("X-Title", BookResult.class, BookResult::title).build());

        assertThat(route.response().outputHeaders()).isEmpty();
    }

    @Test
    void pageResultInsideOptionalFails() {
        assertFails(RestBindings.builder().get("/books", new OptionalPageOperation()).build(),
                "REST route GET /books (optional-page): ", "PageResult", "Optional");
        assertFails(RestBindings.builder().get("/books", new ListOfPagesOperation()).build(),
                "REST route GET /books (list-of-pages): ", "PageResult", "List");
    }

    @Test
    void pageResultNestedInTheOutputFails() {
        String direct = "a PageResult must be the operation's direct output";
        assertFails(RestBindings.builder().get("/shelf", new GetBookShelfOperation()).build(),
                "REST route GET /shelf (get-book-shelf): ", "BookShelf.books", direct);
        assertFails(RestBindings.builder().get("/shelf", new GetBookShelfOperation()).body(BookShelf::books).build(),
                "REST route GET /shelf (get-book-shelf): ", "BookShelf.books", direct);
        assertFails(RestBindings.builder().get("/shelves", new ListBookShelvesOperation()).build(),
                "REST route GET /shelves (list-book-shelves): ", "BookShelf.books", direct);
        assertFails(RestBindings.builder().get("/shelves", new PageOfBookShelvesOperation()).build(),
                "REST route GET /shelves (page-of-book-shelves): ", "BookShelf.books", direct);
    }

    @Test
    void eachParameterizationOfAGenericRecordIsChecked() {
        // Holder<String> is walked first; Holder<Principal> must still be checked on its own.
        assertFails(RestBindings.builder().post("/books/search", new HolderPageOperation()).build(),
                "REST route POST /books/search (holder-page): ", "HolderPageRequest.context",
                "can't be bound into a paging cursor");
        // Holder<String> is walked first; the PageResult in Holder<PageResult<String>> must still be found.
        assertFails(RestBindings.builder().get("/holder", new GetHolderOutputOperation()).build(),
                "REST route GET /holder (get-holder-output): ", "a PageResult must be the operation's direct output");
        // A recursive record terminates, and is digestible and free of pages.
        assertThat(single(RestBindings.builder().post("/trees", new TreePageOperation()).build()).pagedOutput())
                .isTrue();
    }

    @Test
    void pageOfPagesFails() {
        assertFails(RestBindings.builder().get("/pages", new PageOfPagesOperation()).build(),
                "REST route GET /pages (page-of-pages): ", "PageResult.items",
                "a PageResult must be the operation's direct output");
    }

    @Test
    void branchingGrowingGenericOutputStartsFast() {
        // Without a budget the walk visits about 2^32 parameterizations of Grow before the depth cap stops it.
        RouteDefinition route = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> single(RestBindings.builder().get("/grow", new GetGrowOperation()).build()));

        assertThat(route.pagedOutput()).isFalse();
    }

    @Test
    void anExhaustedTypeWalkLeavesPagesToTheWriterAndFailsDigestsClosed() {
        ResolvableType shelves = ResolvableType.forClassWithGenerics(List.class, BookShelf.class);
        ResolvableType tree = ResolvableType.forClass(TreeNode.class);
        assertThat(BindingValidator.nestedPage(shelves, new BindingValidator.TypeWalk()))
                .isEqualTo("BookShelf.books");
        assertThat(BindingValidator.digestible(tree, new BindingValidator.TypeWalk())).isTrue();

        // The one visit goes to the List; BookShelf is left to the response writer's guard.
        assertThat(BindingValidator.nestedPage(shelves, new BindingValidator.TypeWalk(1))).isNull();
        // A digest walk that runs out of budget refuses the type.
        assertThat(BindingValidator.digestible(tree, new BindingValidator.TypeWalk(1))).isFalse();
    }

    @Test
    void pagedRouteComponentsMustFitTheCursorDigest() {
        assertFails(RestBindings
                .builder()
                .get("/books", new PrincipalPageOperation())
                .from(PrincipalPageRequest::principal, request -> new Principal("a"))
                .build(), "REST route GET /books (principal-page): ",
                "component PrincipalPageRequest.principal (type " + Principal.class.getName()
                        + ") can't be bound into a paging cursor; use a record or simple type");

        // Records, enums, UUIDs, java.time and List, Set, Map<String, ...> and Optional of them fit.
        assertThat(single(RestBindings.builder().post("/books/search", new DigestiblePageOperation()).build())
                .pagedOutput()).isTrue();
        // A route that binds PageQuery unwraps cursors, so it digests its input whatever its output (R12).
        assertFails(RestBindings
                .builder()
                .get("/books", new PrincipalBookOperation())
                .from(PrincipalPageRequest::principal, request -> new Principal("a"))
                .build(), "REST route GET /books (principal-book): ",
                "component PrincipalPageRequest.principal (type " + Principal.class.getName()
                        + ") can't be bound into a paging cursor; use a record or simple type");
        // A route without PageQuery never digests its input.
        assertThat(single(RestBindings
                .builder()
                .get("/principal", new PrincipalOnlyOperation())
                .from(PrincipalRequest::principal, request -> new Principal("a"))
                .build()).paging()).isNull();
    }

    @Test
    void etagOnGetResolves() {
        RouteDefinition route = single(
                RestBindings.builder().get("/books/{id}", getVersionedBook).etag(VersionedBook::version).build());

        ConditionalRules conditional = route.conditional();
        assertThat(Objects.requireNonNull(conditional.etag()).name()).isEqualTo("version");
        assertThat(conditional.ifMatch()).isNull();
        assertThat(conditional.ifNoneMatch()).isNull();
        // The component stays in the body: it is not an output header.
        assertThat(route.response().outputHeaders()).isEmpty();
        assertThat(single(RestBindings
                .builder()
                .head("/books/{id}", getVersionedBook)
                .etag(VersionedBook::version)
                .build()).conditional().etag()).isNotNull();
    }

    @Test
    void routesWithoutConditionalDeclarationsHaveNoRules() {
        assertThat(single(RestBindings.builder().get("/books/{id}", getBook).build()).conditional())
                .isEqualTo(ConditionalRules.NONE);
        assertThat(RestBindingsAccess
                .routes(RestBindings.builder().get("/books/{id}", getBook).build())
                .getFirst()
                .conditional())
                .isEqualTo(ConditionalRules.NONE);
    }

    @Test
    void etagOnPutOrDeleteFails() {
        assertFails(RestBindings
                .builder()
                .put("/books/{id}", reviseBook)
                .header("If-Match", ReviseBookRequest::version)
                .etag(VersionedBook::version)
                .build(), "REST route PUT /books/{id} (revise-book): ", "ETag", "PUT", "RFC 9110");
        assertFails(RestBindings
                .builder()
                .delete("/books/{id}", new RemoveBookOperation())
                .etag(VersionedBook::version)
                .build(), "REST route DELETE /books/{id} (remove-book): ", "ETag", "DELETE", "RFC 9110");
    }

    @Test
    void etagOnPostNeedsCreated() {
        assertFails(RestBindings.builder().post("/books", createVersionedBook).etag(VersionedBook::version).build(),
                "REST route POST /books (create-versioned-book): ", ".etag", ".created");

        RouteDefinition route = single(RestBindings
                .builder()
                .post("/books", createVersionedBook)
                .created(book -> "/books/" + book.id())
                .etag(VersionedBook::version)
                .build());
        assertThat(Objects.requireNonNull(route.conditional().etag()).name()).isEqualTo("version");
    }

    @Test
    void etagOnListOrPageResultFails() {
        assertFails(
                RestBindings
                        .builder()
                        .get("/books/{id}", new ListVersionedBooksOperation())
                        .etag(VersionedBook::version)
                        .build(),
                "REST route GET /books/{id} (list-versioned-books): ", ".etag", "the output is a List");
        assertFails(
                RestBindings
                        .builder()
                        .get("/books", new PageVersionedBooksOperation())
                        .etag(VersionedBook::version)
                        .build(),
                "REST route GET /books (page-versioned-books): ", ".etag", "the output is a PageResult");
    }

    @Test
    void etagNeedsAVersionType() {
        // The version types of core's version token codec, the same for gRPC's .etag.
        assertFails(
                RestBindings.builder().get("/books/{id}", getVersionedBook).etag(VersionedBook::author).build(),
                "REST route GET /books/{id} (get-versioned-book): ", "VersionedBook.author", "needs a version type");
        // UUID is not a version type, and an ETag carries one value.
        assertFails(RestBindings.builder().get("/books/{id}", getVersionedBook).etag(VersionedBook::id).build(),
                "VersionedBook.id", "needs a version type");
        assertFails(
                RestBindings.builder().get("/books/{id}", getVersionedBook).etag(VersionedBook::edition).build(),
                "VersionedBook.edition", "needs a version type");
        assertThat(single(RestBindings
                .builder()
                .get("/books/{id}", getVersionedBook)
                .etag(VersionedBook::title)
                .build()).conditional().etag()).isNotNull();
    }

    @Test
    void etagNeedsAnOutputComponentAndOneDeclaration() {
        assertFails(RestBindings.builder().get("/books/{id}", getVersionedBook).etag(GetBookRequest::id).build(),
                "GetBookRequest.id", "not part of the output type VersionedBook");
        assertFails(RestBindings.builder().get("/books/{id}", getVersionedBook).etag(BookResult::title).build(),
                "BookResult.title", "not part of the output type VersionedBook");
        assertFails(RestBindings
                .builder()
                .get("/books/{id}", getVersionedBook)
                .etag(VersionedBook::version)
                .etag(VersionedBook::title)
                .build(), ".etag(...) is declared twice");
    }

    @Test
    void etagHeaderIsReserved() {
        assertFails(
                RestBindings
                        .builder()
                        .get("/books/{id}", getVersionedBook)
                        .header("ETag", VersionedBook::title)
                        .build(),
                "REST route GET /books/{id} (get-versioned-book): ", "the output header ETag is reserved");
        assertFails(RestBindings
                .builder()
                .get("/books/{id}", getVersionedBook)
                .header("Vary", VersionedBook::title)
                .etag(VersionedBook::version)
                .build(), "the output header Vary is reserved");
        assertFails(RestBindings.builder().get("/books/{id}", getVersionedBook).etag(VersionedBook::version).build(),
                List
                        .of(RestConventions
                                .builder()
                                .responseHeader("cache-control", VersionedBook.class, VersionedBook::title)
                                .build()),
                "the output header cache-control is reserved");
        assertFails(RestBindings
                .builder()
                .get("/books/{id}", getVersionedBook)
                .header("Last-Modified", VersionedBook::title)
                .etag(VersionedBook::version)
                .build(), "the output header Last-Modified is reserved");
        // Without .etag, Cache-Control, Vary and Last-Modified are the route's to set.
        RouteDefinition route = single(RestBindings
                .builder()
                .get("/books/{id}", getVersionedBook)
                .header("Vary", VersionedBook::title)
                .header("Cache-Control", VersionedBook::version)
                .build());
        assertThat(route.response().outputHeaders())
                .extracting(OutputHeader::name)
                .containsExactly("Vary", "Cache-Control");
        assertThat(single(RestBindings
                .builder()
                .get("/books/{id}", getVersionedBook)
                .header("Last-Modified", VersionedBook::title)
                .build()).response().outputHeaders()).extracting(OutputHeader::name).containsExactly("Last-Modified");
    }

    @Test
    void aReadsConditionalComponentMustBeNullable() {
        // A read binds a plain type: * and an absent header both bind null.
        assertFails(RestBindings.builder().get("/books", getBook).header("If-None-Match", GetBookRequest::id).build(),
                "REST route GET /books (get-book): ", "If-None-Match", "GetBookRequest.id",
                "* binds null; declare GetBookRequest.id @Nullable");
        // A read can't require a precondition: an absent header binds null.
        assertFailsWith(RestBindings
                .builder()
                .get("/books/{id}", new GetStrictConditionalBookOperation())
                .header("If-None-Match", StrictConditionalReadRequest::version)
                .build(), List.of(),
                "REST route GET /books/{id} (get-strict-conditional-book): the component "
                        + "StrictConditionalReadRequest.version is bound from If-None-Match on GET but isn't "
                        + "@Nullable: a read can't require a precondition, and an absent If-None-Match binds null",
                "Declare StrictConditionalReadRequest.version @Nullable.");
    }

    @Test
    void aNonNullVersionConditionOnAWriteIsRequired() {
        // The operation's input says the precondition is required: startup accepts it, and the route requires it.
        RouteDefinition delete = single(RestBindings
                .builder()
                .delete("/books/{id}", new StrictConditionOperation())
                .header("If-Match", StrictConditionRequest::version)
                .build());
        assertThat(Objects.requireNonNull(delete.conditional().ifMatch()).component().nullable()).isFalse();
        RouteDefinition post = single(RestBindings
                .builder()
                .post("/books/{id}/removals", new StrictConditionOperation())
                .header("If-Match", StrictConditionRequest::version)
                .build());
        assertThat(Objects.requireNonNull(post.conditional().ifMatch()).component().nullable()).isFalse();

        // If-None-Match, and a convention, alike.
        RouteDefinition put = single(RestBindings.builder().put("/books/{id}", new StrictPutBookOperation()).build(),
                RestConventions.builder().header("If-None-Match", VersionCondition.class).build());
        assertThat(Objects.requireNonNull(put.conditional().ifNoneMatch()).component().name()).isEqualTo("unless");
        assertThat(Objects.requireNonNull(put.conditional().ifNoneMatch()).component().nullable()).isFalse();

        // @Nullable keeps it optional.
        RouteDefinition optional = single(RestBindings
                .builder()
                .delete("/books/{id}", new RemoveBookOperation())
                .header("If-Match", RemoveBookRequest::version)
                .build());
        assertThat(Objects.requireNonNull(optional.conditional().ifMatch()).component().nullable()).isTrue();
    }

    @Test
    void routeSpecHasNoRequireIfMatch() {
        // Whether a precondition is required is the operation's input contract, the same over every protocol, so the
        // REST binding has no switch for it.
        assertThat(RouteSpec.class.getMethods()).extracting(Method::getName).doesNotContain("requireIfMatch");
    }

    @Test
    void plainTypeOnAWriteFailsStartup() {
        String problem = ", but its type is Long: a write needs a VersionCondition, so that * and an absent header "
                + "stay apart";
        String action = "Declare it as VersionCondition<Long> to require the header, or @Nullable "
                + "VersionCondition<Long> to make it optional (null without it): Version(v) for one entity tag, "
                + "AnyVersion for *.";
        PlainVersionOperation plain = new PlainVersionOperation();
        assertFailsWith(
                RestBindings
                        .builder()
                        .put("/books/{id}", plain)
                        .header("If-Match", PlainVersionRequest::version)
                        .build(),
                List.of(), "REST route PUT /books/{id} (plain-version): the component PlainVersionRequest.version is "
                        + "bound from If-Match on PUT" + problem,
                action);
        assertFailsWith(RestBindings
                .builder()
                .post("/books/{id}", plain)
                .header("If-None-Match", PlainVersionRequest::version)
                .build(), List.of(),
                "REST route POST /books/{id} (plain-version): the component "
                        + "PlainVersionRequest.version is bound from If-None-Match on POST" + problem,
                action);
        assertFailsWith(RestBindings
                .builder()
                .delete("/books/{id}", plain)
                .header("If-Match", PlainVersionRequest::version)
                .build(), List.of(),
                "REST route DELETE /books/{id} (plain-version): the component "
                        + "PlainVersionRequest.version is bound from If-Match on DELETE" + problem,
                action);
        // The type check comes before the nullability check, for a declared source and a convention.
        StrictReviseOperation strict = new StrictReviseOperation();
        assertFailsWith(RestBindings
                .builder()
                .put("/books/{id}", strict)
                .header("If-None-Match", StrictReviseRequest::version)
                .build(), List.of(),
                "the component StrictReviseRequest.version is bound from If-None-Match on PUT, "
                        + "but its type is long: a write needs a VersionCondition",
                action);
        assertFailsWith(RestBindings.builder().put("/books/{id}", strict).build(),
                List.of(RestConventions.builder().header("If-Match", long.class).build()),
                "the component StrictReviseRequest.version is bound from If-Match on PUT, but its type is long",
                action);
    }

    @Test
    void rawOrWildcardVersionConditionFails() {
        String action = "Declare the version type, for example VersionCondition<Long>.";
        LooseConditionOperation loose = new LooseConditionOperation();
        assertFailsWith(
                RestBindings
                        .builder()
                        .delete("/books/{id}", loose)
                        .header("If-Match", LooseConditionRequest::raw)
                        .build(),
                List.of(), "REST route DELETE /books/{id} (loose-condition): the component LooseConditionRequest.raw "
                        + "is a VersionCondition without a version type",
                action);
        assertFailsWith(RestBindings
                .builder()
                .delete("/books/{id}", loose)
                .header("If-None-Match", LooseConditionRequest::wildcard)
                .build(), List.of(),
                "the component LooseConditionRequest.wildcard is a VersionCondition without a "
                        + "version type",
                action);
        assertFailsWith(RestBindings
                .builder()
                .delete("/books/{id}", loose)
                .context(LooseConditionRequest::wildcard, context -> null)
                .build(), List.of(),
                "the component LooseConditionRequest.wildcard is a VersionCondition without a "
                        + "version type",
                action);
    }

    @Test
    void objectVersionTypeFails() {
        // Version("5").matches(5L) is false for every Object version, so each conditional write would answer 412.
        assertFailsWith(RestBindings
                .builder()
                .delete("/books/{id}", new LooseConditionOperation())
                .header("If-Match", LooseConditionRequest::object)
                .build(), List.of(),
                "REST route DELETE /books/{id} (loose-condition): the component LooseConditionRequest.object is "
                        + "bound as VersionCondition<Object>; the version type must be a concrete type such as Long or "
                        + "String",
                "Declare the type of the resource's version, for example VersionCondition<Long>: the bound value is "
                        + "converted to it and compared with the version the operation reads.");
    }

    @Test
    void supertypeOfStringVersionTypeFails() {
        // The header's text binds as a String, which never equals a Long version the operation reads as Serializable.
        SupertypeConditionOperation operation = new SupertypeConditionOperation();
        String action = "Declare the type of the resource's version, for example VersionCondition<Long>: the bound "
                + "value is converted to it and compared with the version the operation reads.";
        assertFailsWith(RestBindings
                .builder()
                .delete("/books/{id}", operation)
                .header("If-Match", SupertypeConditionRequest::serializable)
                .build(), List.of(),
                "REST route DELETE /books/{id} (supertype-condition): the component "
                        + "SupertypeConditionRequest.serializable is bound as VersionCondition<Serializable>; the "
                        + "version type must be a concrete type such as Long or String",
                action);
        assertFailsWith(RestBindings
                .builder()
                .delete("/books/{id}", operation)
                .header("If-None-Match", SupertypeConditionRequest::comparable)
                .build(), List.of(),
                "REST route DELETE /books/{id} (supertype-condition): the component "
                        + "SupertypeConditionRequest.comparable is bound as VersionCondition<Comparable>; the "
                        + "version type must be a concrete type such as Long or String",
                action);
        // String and CharSequence hold the text itself.
        for (RestBindings bindings : List
                .of(RestBindings
                        .builder()
                        .delete("/books/{id}", operation)
                        .header("If-Match", SupertypeConditionRequest::text)
                        .build(),
                        RestBindings
                                .builder()
                                .delete("/books/{id}", operation)
                                .header("If-Match", SupertypeConditionRequest::string)
                                .build())) {
            assertThat(validator.validate(RestBindingsAccess.routes(bindings), List.of())).hasSize(1);
        }
    }

    @Test
    void versionConditionFromAnotherSourceFails() {
        String action = "Bind it with .header(\"If-Match\", ...) or .header(\"If-None-Match\", ...), or supply it with "
                + ".context or .from.";
        String prefix = "REST route PUT /books/{id} (revise-book): the component ReviseBookRequest.version is a "
                + "VersionCondition bound from ";
        assertFailsWith(
                RestBindings.builder().put("/books/{id}", reviseBook).query("v", ReviseBookRequest::version).build(),
                List.of(), prefix + "query parameter v", action);
        assertFailsWith(
                RestBindings.builder().put("/books/{id}", reviseBook).cookie("v", ReviseBookRequest::version).build(),
                List.of(), prefix + "cookie v", action);
        assertFailsWith(RestBindings
                .builder()
                .put("/books/{id}", reviseBook)
                .header("X-Version", ReviseBookRequest::version)
                .build(), List.of(), prefix + "header X-Version", action);
        assertFailsWith(RestBindings.builder().put("/books/{id}", reviseBook).build(), List.of(),
                prefix + "the request body", action);
        assertFailsWith(RestBindings
                .builder()
                .put("/books/{id}", new RemoveBookOperation())
                .body(RemoveBookRequest::version)
                .build(), List.of(),
                "REST route PUT /books/{id} (remove-book): the component RemoveBookRequest.version is a "
                        + "VersionCondition bound from the whole request body",
                action);
        assertFailsWith(RestBindings.builder().put("/books/{id}", reviseBook).build(),
                List.of(RestConventions.builder().header("X-Version", VersionCondition.class).build()),
                prefix + "header X-Version", action);
        assertFailsWith(RestBindings
                .builder()
                .get("/books/{tag}", new GetConditionalBookOperation())
                .path("tag", ConditionalReadRequest::version)
                .header("X-Id", ConditionalReadRequest::id)
                .build(), List.of(),
                "ConditionalReadRequest.version is a VersionCondition bound from path variable {tag}",
                action);
        // Inside a query record, or wrapped in another type, it has no source either.
        assertFailsWith(RestBindings
                .builder()
                .get("/books", new ConditionFilterOperation())
                .query(ConditionFilterRequest::filter)
                .build(), List.of(),
                "REST route GET /books (condition-filter): the component ConditionFilter.version is a VersionCondition "
                        + "bound from the query string",
                action);
        LooseConditionOperation loose = new LooseConditionOperation();
        assertFailsWith(
                RestBindings
                        .builder()
                        .delete("/books/{id}", loose)
                        .header("If-Match", LooseConditionRequest::list)
                        .build(),
                List.of(), "REST route DELETE /books/{id} (loose-condition): the component LooseConditionRequest.list "
                        + "is a VersionCondition inside List, bound from header If-Match",
                action);
        assertFailsWith(RestBindings
                .builder()
                .delete("/books/{id}", loose)
                .header("If-None-Match", LooseConditionRequest::optional)
                .build(), List.of(),
                "the component LooseConditionRequest.optional is a VersionCondition inside "
                        + "Optional, bound from header If-None-Match",
                action);
    }

    @Test
    void versionConditionFromContextOrFromIsAllowed() {
        RouteDefinition context = single(RestBindings
                .builder()
                .put("/books/{id}", reviseBook)
                .context(ReviseBookRequest::version, requestContext -> VersionCondition.of(1L))
                .build());
        assertThat(source(context, "version")).isInstanceOf(InputSource.Context.class);
        assertThat(context.conditional().ifMatch()).isNull();

        RouteDefinition custom = single(RestBindings
                .builder()
                .put("/books/{id}", reviseBook)
                .from(ReviseBookRequest::version, request -> VersionCondition.any())
                .build());
        assertThat(source(custom, "version")).isInstanceOf(InputSource.Custom.class);
        assertThat(custom.conditional().ifMatch()).isNull();
    }

    @Test
    void readRouteBindsVersionConditionOrPlainType() {
        RouteDefinition condition = single(RestBindings
                .builder()
                .get("/books/{id}", new GetConditionalBookOperation())
                .header("If-None-Match", ConditionalReadRequest::version)
                .build());
        assertThat(Objects.requireNonNull(condition.conditional().ifNoneMatch()).component().name())
                .isEqualTo("version");
        // An unbound VersionCondition on a read has no default source.
        RouteDefinition unbound = single(
                RestBindings.builder().get("/books/{id}", new GetConditionalBookOperation()).build());
        assertThat(unbound.sources()).extracting(source -> source.component().name()).containsExactly("id");
    }

    @Test
    void ifMatchHeaderIsRecognisedCaseInsensitively() {
        RouteDefinition route = single(
                RestBindings
                        .builder()
                        .put("/books/{id}", reviseBook)
                        .header("if-match", ReviseBookRequest::version)
                        .build());

        InputSource.Header ifMatch = Objects.requireNonNull(route.conditional().ifMatch());
        assertThat(ifMatch.name()).isEqualTo("if-match");
        assertThat(ifMatch.component().name()).isEqualTo("version");
        assertThat(route.conditional().ifNoneMatch()).isNull();
        assertThat(route.conditional().etag()).isNull();

        RouteDefinition noneMatch = single(RestBindings
                .builder()
                .put("/books/{id}", reviseBook)
                .header("IF-NONE-MATCH", ReviseBookRequest::version)
                .build());
        assertThat(Objects.requireNonNull(noneMatch.conditional().ifNoneMatch()).component().name())
                .isEqualTo("version");
        assertThat(noneMatch.conditional().ifMatch()).isNull();

        RouteDefinition byConvention = single(RestBindings.builder().put("/books/{id}", reviseBook).build(),
                RestConventions.builder().header("If-Match", VersionCondition.class).build());
        assertThat(Objects.requireNonNull(byConvention.conditional().ifMatch()).component().name())
                .isEqualTo("version");
    }

    @Test
    void ifMatchBoundToTwoComponentsFails() {
        assertFails(RestBindings
                .builder()
                .put("/books/{id}", reviseBook)
                .header("If-Match", ReviseBookRequest::version)
                .header("if-match", ReviseBookRequest::title)
                .build(), "REST route PUT /books/{id} (revise-book): ", "If-Match", "ReviseBookRequest.version",
                "ReviseBookRequest.title");
    }

    private RouteDefinition single(RestBindings bindings, RestConventions... conventions) {
        List<RouteDefinition> routes = validator.validate(RestBindingsAccess.routes(bindings), List.of(conventions));
        assertThat(routes).hasSize(1);
        return routes.getFirst();
    }

    @Test
    void versionMustBeMajorMinorPatch() {
        String action = "Declare the version as numbers only, such as .version(\"2\") or .version(\"1.2\"), with no v "
                + "prefix, + or blank: the route is served at /v<version> followed by its path.";
        for (String version : List.of("2+", "v2", "", " 2", "1.2.3.4", "2.", "1..2", "-1", "99999999999")) {
            assertFailsExactly(RestBindings.builder().get("/books/{id}", getBook).version(version).build(),
                    "REST route GET /v" + version + "/books/{id} (get-book): .version(\"" + version + "\") is not a "
                            + "version: a version is major[.minor[.patch]], such as 2 or 1.2",
                    action);
        }
        List<RouteDefinition> routes = validator
                .validate(RestBindingsAccess
                        .routes(RestBindings
                                .builder()
                                .get("/books/{id}", getBook)
                                .version("1")
                                .get("/books/{id}", getBook)
                                .version("1.2")
                                .get("/books/{id}", getBook)
                                .version("1.2.3")
                                .build()),
                        List.of());
        assertThat(routes)
                .extracting(RouteDefinition::path)
                .containsExactly("/v1/books/{id}", "/v1.2/books/{id}", "/v1.2.3/books/{id}");
    }

    @Test
    void versionIsDeclaredOnce() {
        assertFailsExactly(RestBindings.builder().get("/books/{id}", getBook).version("1").version("2").build(),
                "REST route GET /v1/books/{id} (get-book): .version(\"2\") follows .version(\"1\"), but a route's "
                        + "version is declared once",
                "Keep one .version(...) on this route; to serve the route in another version too, declare it again "
                        + "with that version.");
    }

    @Test
    void sameRouteInTwoVersionsIsAllowed() {
        List<RouteDefinition> routes = validator
                .validate(RestBindingsAccess
                        .routes(RestBindings
                                .builder()
                                .get("/books", searchBooks)
                                .version("1")
                                .get("/books", searchBooks)
                                .version("2")
                                .get("/books", searchBooks)
                                .get("/books/{id}", getBook)
                                .version("2")
                                .delete("/books/{id}", new GetPlainBookOperation())
                                .version("2.0")
                                .build()),
                        List.of());

        // Equal versions are allowed on different methods: GET and DELETE /books/{id} in 2 and 2.0.
        assertThat(routes)
                .extracting(RouteDefinition::method, RouteDefinition::path, RouteDefinition::version)
                .containsExactly(tuple(HttpMethod.GET, "/v1/books", "1"), tuple(HttpMethod.GET, "/v2/books", "2"),
                        tuple(HttpMethod.GET, "/books", null), tuple(HttpMethod.GET, "/v2/books/{id}", "2"),
                        tuple(HttpMethod.DELETE, "/v2.0/books/{id}", "2.0"));
        // Each version binds its input as an unversioned route would.
        for (RouteDefinition route : routes.subList(0, 3)) {
            assertThat(route.sources())
                    .extracting(source -> source.getClass().getSimpleName() + " " + source.component().name())
                    .containsExactly("Query author", "Query page", "Query limit", "Query available", "Query genre",
                            "Query since", "Query tags");
        }
    }

    @Test
    void equalVersionsOfOneRouteFail() {
        String action = "Declare each version of a route once, in one spelling, such as .version(\"2\").";
        assertFailsExactly(RestBindings
                .builder()
                .get("/books", searchBooks)
                .version("2")
                .get("/books", searchBooks)
                .version("2.0")
                .build(),
                "REST route GET /v2.0/books (search-books): versions 2 and 2.0 are equal, so this route and GET "
                        + "/v2/books (search-books) are the same method and path in one version",
                action);
        // Path variables are compared by position, as for unversioned duplicates.
        assertFailsExactly(RestBindings
                .builder()
                .get("/books/{id}", getBook)
                .version("1.2.0")
                .get("/books/{bookId}", new GetPlainBookOperation())
                .path("bookId", GetBookRequest::id)
                .version("01.2")
                .build(),
                "REST route GET /v01.2/books/{bookId} (get-plain-book): versions 1.2.0 and 01.2 are equal, so this "
                        + "route and GET /v1.2.0/books/{id} (get-book) are the same method and path in one version",
                action);
        // The same version twice is a duplicate route.
        assertFails(RestBindings
                .builder()
                .get("/books", searchBooks)
                .version("2")
                .get("/books", searchBooks)
                .version("2")
                .build(), "REST route GET /v2/books (search-books): ", "same method and path");
    }

    @Test
    void pathVariablesOfVersionedRoutesStillBind() {
        RouteDefinition route = single(RestBindings.builder().get("/books/{id}", getBook).version("2").build());

        assertThat(route.path()).isEqualTo("/v2/books/{id}");
        assertThat(route.pathVariables()).containsExactly("id");
        assertThat(route.version()).isEqualTo("2");
        assertThat(route.declaredPath()).isEqualTo("/books/{id}");
        assertThat(source(route, "id"))
                .isInstanceOfSatisfying(InputSource.Path.class, path -> assertThat(path.variable()).isEqualTo("id"));
    }

    @Test
    void deprecationAndSunsetAreReservedOnVersionedRoutes() {
        String action = "Use another header name; to deprecate a version, declare an ApiVersionDeprecationHandler "
                + "bean, which sends Deprecation and Sunset.";
        for (String name : List.of("Deprecation", "sunset")) {
            assertFailsExactly(
                    RestBindings
                            .builder()
                            .get("/books/{id}", getBook)
                            .version("2")
                            .header(name, BookResult::title)
                            .build(),
                    "REST route GET /v2/books/{id} (get-book): the output header " + name + " is reserved on a "
                            + "versioned route: Spring's API versioning sets it",
                    action);
        }
        assertFailsWith(RestBindings.builder().get("/books/{id}", getBook).version("2").build(),
                List
                        .of(RestConventions
                                .builder()
                                .responseHeader("Sunset", BookResult.class, BookResult::title)
                                .build()),
                "REST route GET /v2/books/{id} (get-book): the output header Sunset is reserved on a versioned route",
                action);
        // Without .version, Deprecation and Sunset are the route's to set.
        RouteDefinition route = single(RestBindings
                .builder()
                .get("/books/{id}", getBook)
                .header("Deprecation", BookResult::title)
                .header("Sunset", BookResult::remaining)
                .build());
        assertThat(route.response().outputHeaders())
                .extracting(OutputHeader::name)
                .containsExactly("Deprecation", "Sunset");
    }

    /** Asserts a failure with exactly this description and action. */
    private void assertFailsExactly(RestBindings bindings, String description, String action) {
        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> validator.validate(RestBindingsAccess.routes(bindings), List.of()))
                .satisfies(e -> {
                    assertThat(e.description()).isEqualTo(description);
                    assertThat(e.action()).isEqualTo(action);
                });
    }

    private void assertFails(RestBindings bindings, String... fragments) {
        assertFails(bindings, List.of(), fragments);
    }

    private void assertFails(RestBindings bindings, List<RestConventions> conventions, String... fragments) {
        ThrowingCallable call = () -> validator.validate(RestBindingsAccess.routes(bindings), conventions);
        Consumer<SynapseConfigurationException> check = e -> {
            assertThat(e.description()).contains(fragments);
            assertThat(e.action()).isNotBlank();
        };
        assertThatExceptionOfType(SynapseConfigurationException.class).isThrownBy(call).satisfies(check);
    }

    /** Asserts a failure whose description contains {@code problem} and whose action is exactly {@code action}. */
    private void assertFailsWith(RestBindings bindings, List<RestConventions> conventions, String problem,
            String action) {
        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> validator.validate(RestBindingsAccess.routes(bindings), conventions))
                .satisfies(e -> {
                    assertThat(e.description()).contains(problem);
                    assertThat(e.action()).isEqualTo(action);
                });
    }

    private static InputSource source(RouteDefinition route, String component) {
        return route
                .sources()
                .stream()
                .filter(source -> source.component().name().equals(component))
                .findFirst()
                .orElseThrow();
    }

    private static ResolvedComponent component(RouteDefinition route, String component) {
        return source(route, component).component();
    }
}
