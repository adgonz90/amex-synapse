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
package io.americanexpress.synapse.api.rest.openapi.internal;

import java.time.DayOfWeek;
import java.time.Month;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.SpecVersion;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.api.rest.internal.error.RestErrorStatuses;
import io.americanexpress.synapse.api.rest.internal.paging.PagingSettings;

import static org.assertj.core.api.Assertions.assertThat;

class RouteDocumenterTest {

    private static final String SCHEMAS = "#/components/schemas/";

    private static final String PROBLEM_JSON = "application/problem+json";

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final PagingSettings PAGING = PagingSettings.defaults();

    private static final RestErrorStatuses NO_OVERRIDES = new RestErrorStatuses(Map.of(), Map.of());

    @Test
    void everyDeclaredRouteHasOneOperation() throws JsonProcessingException {
        DocumentContext doc = books();

        List<String> operationIds = doc.read("$.paths.*.*.operationId");
        assertThat(operationIds)
                .containsExactlyInAnyOrder("get-book", "search-books", "get-catalog-stats", "create-book",
                        "update-book", "delete-book", "get-book-2", "search-authors", "lend-book", "find-cover");
        // The second route of get-book is the versioned one, later in route order.
        assertThat(doc.read("$.paths['/v2/books/{id}'].get.operationId", String.class)).isEqualTo("get-book-2");
        List<Object> implicit = doc.read("$.paths.*.head");
        implicit.addAll(doc.read("$.paths.*.options"));
        assertThat(implicit).isEmpty();
        assertThat(map(doc, "$.paths['/books/{id}']")).containsOnlyKeys("get", "put", "delete");
    }

    @Test
    void pathQueryCookieAndHeaderParameters() throws JsonProcessingException {
        DocumentContext doc = books();

        assertThat(parameter(doc, "/books/{id}", "get", "id"))
                .containsEntry("in", "path")
                .containsEntry("required", true)
                .containsEntry("schema", Map.of("type", "string", "format", "uuid"));
        assertThat(parameter(doc, "/books", "get", "author"))
                .containsEntry("in", "query")
                .containsEntry("required", false)
                .containsEntry("schema", Map.of("type", "string"));
        // The QueryRecord convention: each component of AuthorFilter is a query parameter.
        assertThat(parameter(doc, "/authors", "get", "country"))
                .containsEntry("in", "query")
                .containsEntry("required", false);
        assertThat(parameter(doc, "/authors", "get", "minBooks"))
                .containsEntry("in", "query")
                .containsEntry("required", true)
                .containsEntry("schema", Map.of("type", "integer", "format", "int32"));
        assertThat(parameter(doc, "/books/{id}/loans", "post", "session"))
                .containsEntry("in", "cookie")
                .containsEntry("required", false);
        assertThat(parameter(doc, "/books/{id}/loans", "post", "X-Branch"))
                .containsEntry("in", "header")
                .containsEntry("required", false);
        // Body, context and custom components are not parameters.
        List<String> loanParameters = doc.read("$.paths['/books/{id}/loans'].post.parameters[*].name");
        assertThat(loanParameters)
                .containsExactlyInAnyOrder("id", "session", "X-Branch", "X-Correlation-Id", "Request-Timeout");
        // Every operation takes the correlation and deadline headers, optional.
        List<Map<String, Object>> correlation = doc
                .read("$.paths.*.*.parameters[?(@.name == 'X-Correlation-Id')]");
        assertThat(correlation)
                .hasSize(10)
                .allSatisfy(parameter -> assertThat(parameter)
                        .containsEntry("in", "header")
                        .containsEntry("required", false)
                        .containsEntry("schema", Map.of("type", "string")));
        List<Map<String, Object>> deadline = doc.read("$.paths.*.*.parameters[?(@.name == 'Request-Timeout')]");
        assertThat(deadline)
                .hasSize(10)
                .allSatisfy(parameter -> assertThat(parameter)
                        .containsEntry("in", "header")
                        .containsEntry("required", false)
                        .containsEntry("schema", Map.of("type", "integer", "format", "int64", "minimum", 1)));
    }

    @Test
    void collectionsAreRequiredOnlyWhenTheyMustHaveItems() throws JsonProcessingException {
        DocumentContext doc = json(documenter(PAGING, NO_OVERRIDES).document(BookRoutes.collections()));

        // An unsent list binds empty, so a client may leave it out, unless its constraints need items: then an absent
        // one is rejected, so it is required, with minItems.
        assertThat(parameter(doc, "/labels", "get", "tag"))
                .containsEntry("required", true)
                .containsEntry("schema", Map.of("type", "array", "items", Map.of("type", "string"), "minItems", 1));
        assertThat(parameter(doc, "/labels", "get", "X-Tags"))
                .containsEntry("required", true)
                .containsEntry("schema", Map.of("type", "array", "items", Map.of("type", "string"), "minItems", 1));
        assertThat(parameter(doc, "/labels", "get", "any")).containsEntry("required", false);
        assertThat(parameter(doc, "/labels", "get", "tags"))
                .containsEntry("in", "cookie")
                .containsEntry("required",
                        false);
        assertThat(parameter(doc, "/labels", "get", "owner")).containsEntry("required", true);
        // A body of collections only may be left out: it counts as {}, and every component binds empty.
        assertThat(doc.read("$.paths['/labels'].post.requestBody.required", Boolean.class)).isFalse();
        assertThat(map(doc, "$.components.schemas.LabelsInput"))
                .containsKey("properties")
                .doesNotContainKey("required");
        // One with a @NotEmpty list may not.
        assertThat(doc.read("$.paths['/labels'].put.requestBody.required", Boolean.class)).isTrue();
        List<String> required = doc.read("$.components.schemas.TaggedLabelsInput.required");
        assertThat(required).containsExactly("tags");
    }

    @Test
    void pagingParametersFollowTheSettings() throws JsonProcessingException {
        DocumentContext doc = books();

        assertThat(parameter(doc, "/books", "get", "cursor"))
                .containsEntry("in", "query")
                .containsEntry("required", false)
                .containsEntry("schema", Map.of("type", "string"));
        assertThat(parameter(doc, "/books", "get", "offset"))
                .containsEntry("in", "query")
                .containsEntry("schema", Map.of("type", "integer", "format", "int64", "minimum", 0, "maximum", 1000));
        assertThat(parameter(doc, "/books", "get", "size"))
                .containsEntry("in", "query")
                .containsEntry("schema", Map.of("type", "integer", "minimum", 1, "maximum", 100, "default", 20));
        assertThat(parameter(doc, "/books", "get", "sort"))
                .containsEntry("in", "query")
                .containsEntry("required", false)
                .containsEntry("style", "form")
                .containsEntry("explode", false)
                .containsEntry("schema", Map
                        .of("type", "array", "items",
                                Map.of("type", "string", "enum", List.of("title", "-title", "year", "-year"))));
        // Routes that don't bind PageQuery have no paging parameters.
        List<String> stats = doc.read("$.paths['/books/{id}'].get.parameters[*].name");
        assertThat(stats).containsExactlyInAnyOrder("id", "If-None-Match", "X-Correlation-Id", "Request-Timeout");

        PagingSettings renamed = new PagingSettings("after", "skip", "page_size", "order", 500, 10, 50);
        DocumentContext custom = json(
                documenter(renamed, NO_OVERRIDES).document(BookRoutes.books(renamed)));
        List<String> names = custom.read("$.paths['/books'].get.parameters[*].name");
        assertThat(names)
                .containsExactlyInAnyOrder("author", "after", "skip", "page_size", "order", "X-Correlation-Id",
                        "Request-Timeout");
        assertThat(parameter(custom, "/books", "get", "skip"))
                .containsEntry("schema", Map.of("type", "integer", "format", "int64", "minimum", 0, "maximum", 500));
        assertThat(parameter(custom, "/books", "get", "page_size"))
                .containsEntry("schema", Map.of("type", "integer", "minimum", 1, "maximum", 50, "default", 10));
        // A route without .sortable has no sort parameter.
        DocumentContext others = json(documenter(PAGING, NO_OVERRIDES).document(BookRoutes.others(PAGING)));
        List<String> pages = others.read("$.paths['/pages'].get.parameters[*].name");
        assertThat(pages).containsExactlyInAnyOrder("cursor", "offset", "size", "X-Correlation-Id", "Request-Timeout");
    }

    @Test
    void conditionalHeadersAndResponses() throws JsonProcessingException {
        DocumentContext doc = books();

        Map<String, Object> ok = doc.read("$.paths['/books/{id}'].get.responses['200'].headers");
        assertThat(ok).containsKeys("ETag", "Cache-Control", "Vary", "X-Correlation-Id");
        Map<String, Object> notModified = doc.read("$.paths['/books/{id}'].get.responses['304']");
        assertThat(notModified).doesNotContainKey("content");
        assertThat(map(doc, "$.paths['/books/{id}'].get.responses['304'].headers"))
                .containsKeys("ETag", "Cache-Control", "Vary", "X-Correlation-Id");
        // The 304 is decided on If-None-Match, which the client sends without the route binding it.
        assertThat(parameter(doc, "/books/{id}", "get", "If-None-Match"))
                .containsEntry("in", "header")
                .containsEntry("required", false)
                .containsEntry("description", "An entity tag (\"5\"), or *.")
                .containsEntry("schema", Map.of("type", "string"));

        assertThat(parameter(doc, "/books/{id}", "put", "If-Match"))
                .containsEntry("in", "header")
                .containsEntry("required", false)
                .containsEntry("description", "An entity tag (\"5\"), or *.")
                .containsEntry("schema", Map.of("type", "string"));
        assertThat(responses(doc, "/books/{id}", "put")).containsKeys("412", "409").doesNotContainKey("428");
        assertThat(description(doc, "/books/{id}", "put", "412")).contains("PRECONDITION_FAILED");
        assertThat(description(doc, "/books/{id}", "put", "409")).contains("STALE_VERSION");

        // DeleteBookRequest.version isn't @Nullable, so If-Match is required: no If-Match is 428, and with one a
        // STALE_VERSION is answered 412, so never 409.
        assertThat(parameter(doc, "/books/{id}", "delete", "If-Match"))
                .containsEntry("in", "header")
                .containsEntry("required", true)
                .containsEntry("description", "An entity tag (\"5\"), or *.");
        assertThat(responses(doc, "/books/{id}", "delete")).containsKeys("428", "412").doesNotContainKey("409");
        assertThat(description(doc, "/books/{id}", "delete", "428")).contains("PRECONDITION_REQUIRED");
        assertThat(description(doc, "/books/{id}", "delete", "412")).doesNotContain("STALE_VERSION");
        // Reads are not checked by the edge.
        assertThat(responses(doc, "/books/{id}", "get")).doesNotContainKeys("409", "412", "428");

        // If-None-Match on a write: 412, and no STALE_VERSION.
        DocumentContext others = json(documenter(PAGING, NO_OVERRIDES).document(BookRoutes.others(PAGING)));
        assertThat(parameter(others, "/drafts/{id}", "put", "If-None-Match"))
                .containsEntry("required", false)
                .containsEntry("schema", Map.of("type", "string"));
        assertThat(responses(others, "/drafts/{id}", "put")).containsKey("412").doesNotContainKeys("409", "428");

        // A non-null If-None-Match condition is required too, with its 428.
        DocumentContext createOnly = json(documenter(PAGING, NO_OVERRIDES).document(BookRoutes.createOnly()));
        assertThat(parameter(createOnly, "/drafts/{id}", "put", "If-None-Match"))
                .containsEntry("required", true)
                .containsEntry("schema", Map.of("type", "string"));
        assertThat(responses(createOnly, "/drafts/{id}", "put")).containsKeys("412", "428").doesNotContainKey("409");
        assertThat(description(createOnly, "/drafts/{id}", "put", "428")).contains("PRECONDITION_REQUIRED");
    }

    @Test
    void conditionalRoutesReportNothingUndescribed() {
        SchemaGenerator schemas = new SchemaGenerator(MAPPER, VALIDATOR);
        RouteDocumenter documenter = new RouteDocumenter(schemas, PAGING, NO_OVERRIDES, "X-Correlation-Id",
                "Request-Timeout");
        List<RouteDefinition> conditional = BookRoutes
                .books(PAGING)
                .stream()
                .filter(route -> route.conditional().ifMatch() != null || route.conditional().ifNoneMatch() != null)
                .toList();
        List<RouteDefinition> others = BookRoutes
                .others(PAGING)
                .stream()
                .filter(route -> route.conditional().ifNoneMatch() != null)
                .toList();
        assertThat(conditional).hasSize(2);
        assertThat(others).hasSize(1);

        RouteDocumenter.Fragment fragment = documenter
                .document(List
                        .of(conditional.get(0), conditional.get(1),
                                others.getFirst()));

        // A VersionCondition never reaches the generator, which would document it as {} and report it.
        assertThat(fragment.undescribed()).isEmpty();
        assertThat(schemas.undescribed()).isEmpty();
        assertThat(documenter(PAGING, NO_OVERRIDES).document(BookRoutes.books(PAGING)).undescribed()).isEmpty();
    }

    @Test
    void undescribedTypesNameTheFirstRouteThatUsedThem() {
        RouteDocumenter documenter = documenter(PAGING, NO_OVERRIDES);

        RouteDocumenter.Fragment fragment = documenter.document(BookRoutes.opaque());

        String opaque = BookRoutes.Opaque.class.getCanonicalName();
        assertThat(fragment.undescribed()).containsExactly(Map.entry(opaque, "GET /opaque (get-opaque)"));
        // Documenting again names the same route.
        assertThat(documenter.document(BookRoutes.opaque()).undescribed())
                .containsExactly(Map.entry(opaque, "GET /opaque (get-opaque)"));
    }

    @Test
    void successStatusesAndHeaders() throws JsonProcessingException {
        DocumentContext doc = books();

        assertThat(responses(doc, "/books", "post")).containsKey("201").doesNotContainKey("200");
        assertThat(map(doc, "$.paths['/books'].post.responses['201'].headers"))
                .containsKeys("Location", "ETag", "X-Correlation-Id")
                .doesNotContainKeys("Cache-Control", "Vary");
        assertThat(doc
                .read("$.paths['/books'].post.responses['201'].content['application/json'].schema['$ref']",
                        String.class))
                .isEqualTo(SCHEMAS + "Book");

        assertThat(responses(doc, "/books/{id}", "delete")).containsKey("204").doesNotContainKey("200");
        assertThat(map(doc, "$.paths['/books/{id}'].delete.responses['204']"))
                .doesNotContainKey("content");

        Map<String, Object> total = doc.read("$.paths['/books/stats'].get.responses['200'].headers['X-Books-Total']");
        assertThat(total).containsEntry("schema", Map.of("type", "integer", "format", "int32"));
        // .alsoInBody() keeps the component in the body: the output's own component.
        assertThat(doc
                .read("$.paths['/books/stats'].get.responses['200'].content['application/json'].schema['$ref']",
                        String.class))
                .isEqualTo(SCHEMAS + "CatalogStats");
        assertThat(map(doc, "$.components.schemas.CatalogStats.properties")).containsKey("books");

        // .status(fn): any success status.
        assertThat(responses(doc, "/books/{id}/loans", "post")).containsKey("2XX").doesNotContainKeys("200", "201");
        assertThat(doc
                .read("$.paths['/books/{id}/loans'].post.responses['2XX'].content['application/json']"
                        + ".schema['$ref']", String.class))
                .isEqualTo(SCHEMAS + "Loan");

        // .emptyAs(204): a 204 without content next to the 200.
        assertThat(responses(doc, "/books/{id}/cover", "get")).containsKeys("200", "204");
        assertThat(map(doc, "$.paths['/books/{id}/cover'].get.responses['204']"))
                .doesNotContainKey("content")
                .containsKey("headers");
        // A List output is an array.
        assertThat(map(doc, "$.paths['/authors'].get.responses['200'].content['application/json'].schema"))
                .isEqualTo(Map.of("type", "array", "items", Map.of("$ref", SCHEMAS + "Author")));
    }

    @Test
    void otherSuccessShapes() throws JsonProcessingException {
        DocumentContext doc = json(documenter(PAGING, NO_OVERRIDES).document(BookRoutes.others(PAGING)));

        // .accepted: 202 with the status resource in Location and in a Link.
        assertThat(responses(doc, "/jobs", "post")).containsKey("202");
        assertThat(map(doc, "$.paths['/jobs'].post.responses['202'].headers"))
                .containsKeys("Location", "Link", "X-Correlation-Id");
        // A Void output: 204 without content.
        assertThat(responses(doc, "/books/{id}/cover", "put")).containsKey("204").doesNotContainKey("200");
        assertThat(map(doc, "$.paths['/books/{id}/cover'].put.responses['204']"))
                .doesNotContainKey("content");
        // .status(203).
        assertThat(responses(doc, "/drafts/{id}", "put")).containsKey("203").doesNotContainKey("200");
        // An output header without .alsoInBody() is left out of the body, which is an inline object.
        Map<String, Object> quota = doc
                .read("$.paths['/quota'].get.responses['200'].content['application/json'].schema");
        assertThat(quota).containsEntry("type", "object").doesNotContainKey("$ref");
        assertThat(map(doc, "$.paths['/quota'].get.responses['200'].content['application/json'].schema.properties"))
                .containsOnlyKeys("limit");
        assertThat(map(doc, "$.paths['/quota'].get.responses['200'].headers")).containsKey("X-Remaining");
        // A declared HEAD is documented.
        assertThat(doc.read("$.paths['/books/{id}'].head.operationId", String.class)).isEqualTo("book-exists");
        // A route that binds the correlation header itself documents it once.
        List<String> trace = doc.read("$.paths['/trace'].get.parameters[*].name");
        assertThat(trace).containsExactlyInAnyOrder("X-Correlation-Id", "Request-Timeout");
    }

    @Test
    void requestBodiesHoldOnlyBodyComponents() throws JsonProcessingException {
        DocumentContext doc = books();

        Map<String, Object> put = doc.read("$.paths['/books/{id}'].put.requestBody");
        assertThat(put).containsEntry("required", true);
        assertThat(map(doc, "$.paths['/books/{id}'].put.requestBody.content['application/json'].schema.properties"))
                .containsOnlyKeys("isbn", "title", "author", "year");
        List<String> required = doc
                .read("$.paths['/books/{id}'].put.requestBody.content['application/json'].schema.required");
        assertThat(required).containsExactlyInAnyOrder("isbn", "title", "author");
        // Every component from the body: the input record itself.
        assertThat(doc
                .read("$.paths['/books'].post.requestBody.content['application/json'].schema['$ref']",
                        String.class))
                .startsWith(SCHEMAS + "CreateBookRequest");
        assertThat(map(doc, "$.paths['/books/{id}/loans'].post.requestBody.content['application/json'].schema"
                + ".properties"))
                .containsOnlyKeys("borrower");
        // No body on reads and on a DELETE.
        List<Object> bodies = doc.read("$.paths.*.get.requestBody");
        bodies.addAll(doc.read("$.paths.*.delete.requestBody"));
        assertThat(bodies).isEmpty();

        // .body(...): the component's schema.
        DocumentContext others = json(documenter(PAGING, NO_OVERRIDES).document(BookRoutes.others(PAGING)));
        assertThat(others
                .read("$.paths['/books/{id}/cover'].put.requestBody.content['application/json']"
                        + ".schema['$ref']", String.class))
                .startsWith(SCHEMAS + "Image");
        assertThat(others.read("$.paths['/books/{id}/cover'].put.requestBody.required", Boolean.class)).isTrue();
    }

    @Test
    void problemResponsesUseTheEdgesStatuses() throws JsonProcessingException {
        DocumentContext doc = books();

        assertThat(description(doc, "/books/{id}", "get", "400")).contains("VALIDATION_FAILED", "MALFORMED_REQUEST");
        assertThat(description(doc, "/books/{id}", "get", "404")).contains("BOOK_NOT_FOUND");
        // The versioned route has no .notFound: the default code.
        assertThat(description(doc, "/v2/books/{id}", "get", "404")).contains("NOT_FOUND");
        assertThat(responses(doc, "/books", "post")).containsKeys("400", "413", "415");
        assertThat(description(doc, "/books", "post", "413")).contains("PAYLOAD_TOO_LARGE");
        assertThat(description(doc, "/books", "post", "415")).contains("UNSUPPORTED_MEDIA_TYPE");
        // A Void input on a path without variables: neither 400 nor 404.
        assertThat(responses(doc, "/books/stats", "get")).doesNotContainKeys("400", "404", "413", "415");
        // No body: no 413 or 415.
        assertThat(responses(doc, "/books/{id}", "delete")).doesNotContainKeys("413", "415");

        List<Object> defaults = doc.read("$.paths.*.*.responses.default");
        assertThat(defaults).hasSize(10);
        List<String> problems = doc.read("$.paths.*.*.responses.*.content['" + PROBLEM_JSON + "'].schema['$ref']");
        assertThat(problems).hasSizeGreaterThan(20).containsOnly(SCHEMAS + "SynapseProblem");
        List<Object> correlated = doc.read("$.paths.*.*.responses.*.headers['X-Correlation-Id']['$ref']");
        List<Object> all = doc.read("$.paths.*.*.responses.*");
        assertThat(correlated).hasSameSizeAs(all).containsOnly("#/components/headers/X-Correlation-Id");

        assertThat(map(doc, "$.components.schemas.SynapseProblem.properties"))
                .containsOnlyKeys("type", "title", "status", "detail", "code", "correlationId", "violations");
        List<String> required = doc.read("$.components.schemas.SynapseProblem.required");
        assertThat(required).containsExactlyInAnyOrder("type", "title", "status", "code", "correlationId");
        assertThat(doc.read("$.components.schemas.SynapseProblem.properties.violations.items['$ref']", String.class))
                .isEqualTo(SCHEMAS + "SynapseViolation");
        List<String> violation = doc.read("$.components.schemas.SynapseViolation.required");
        assertThat(violation).containsExactlyInAnyOrder("field", "message");
        assertThat(map(doc, "$.components.headers['X-Correlation-Id'].schema"))
                .isEqualTo(Map.of("type", "string"));
    }

    @Test
    void statusOverridesChangeTheDocumentedStatus() throws JsonProcessingException {
        RestErrorStatuses global = new RestErrorStatuses(Map.of("STALE_VERSION", 422), Map.of());

        DocumentContext doc = json(documenter(PAGING, global).document(BookRoutes.books(PAGING)));

        assertThat(responses(doc, "/books/{id}", "put")).containsKey("422").doesNotContainKey("409");
        assertThat(description(doc, "/books/{id}", "put", "422")).contains("STALE_VERSION");
        // The edge never sends STALE_VERSION on a route that requires If-Match, overridden or not.
        assertThat(responses(doc, "/books/{id}", "delete")).doesNotContainKeys("409", "422");

        // A per-operation override beats the global one.
        RestErrorStatuses perOperation = new RestErrorStatuses(Map.of("STALE_VERSION", 422),
                Map.of("update-book", Map.of("STALE_VERSION", 410)));
        DocumentContext overridden = json(documenter(PAGING, perOperation).document(BookRoutes.books(PAGING)));
        assertThat(responses(overridden, "/books/{id}", "put")).containsKey("410").doesNotContainKeys("409", "422");
        assertThat(description(overridden, "/books/{id}", "put", "410")).contains("STALE_VERSION");
    }

    @Test
    void versionedRoutesAreDocumentedAtTheirPath() throws JsonProcessingException {
        DocumentContext doc = books();

        assertThat(map(doc, "$.paths['/v2/books/{id}']")).containsOnlyKeys("get");
        assertThat(parameter(doc, "/v2/books/{id}", "get", "id")).containsEntry("in", "path");
        assertThat(doc.read("$.paths['/books/{id}'].get.operationId", String.class)).isEqualTo("get-book");
        Map<String, Object> paths = doc.read("$.paths");
        assertThat(paths.keySet())
                .containsExactly("/books/{id}", "/books", "/books/stats", "/v2/books/{id}", "/authors",
                        "/books/{id}/loans", "/books/{id}/cover");
    }

    @Test
    void pagedResponsesUseThePageComponent() throws JsonProcessingException {
        DocumentContext doc = books();

        assertThat(doc
                .read("$.paths['/books'].get.responses['200'].content['application/json'].schema['$ref']",
                        String.class))
                .isEqualTo(SCHEMAS + "BookPage");
        assertThat(map(doc, "$.paths['/books'].get.responses['200'].headers")).containsKey("Link");
        assertThat(map(doc, "$.components.schemas.BookPage.properties"))
                .containsOnlyKeys("items", "nextCursor", "hasNext", "total");
        // No Link where nothing is paged.
        assertThat(map(doc, "$.paths['/authors'].get.responses['200'].headers")).doesNotContainKey("Link");
    }

    @Test
    void groupsBecomeTags() throws JsonProcessingException {
        DocumentContext doc = books();

        List<String> tags = doc.read("$.paths['/books/stats'].get.tags");
        assertThat(tags).containsExactly("books");
        List<Object> others = doc.read("$.paths['/books/{id}'].*.tags");
        assertThat(others).isEmpty();
    }

    @Test
    void notModifiedNeedsASuccessOf200() throws JsonProcessingException {
        DocumentContext doc = others();

        // .etag with .status(203): the edge evaluates If-None-Match only on a 200, so there is no 304.
        assertThat(responses(doc, "/editions/{id}", "get")).containsKey("203").doesNotContainKey("304");
        assertThat(map(doc, "$.paths['/editions/{id}'].get.responses['203'].headers"))
                .doesNotContainKeys("ETag", "Cache-Control", "Vary");
        List<String> names = doc.read("$.paths['/editions/{id}'].get.parameters[*].name");
        assertThat(names).containsExactlyInAnyOrder("id", "X-Correlation-Id", "Request-Timeout");
    }

    @Test
    void aDeclaredHeadHasNoBody() throws JsonProcessingException {
        DocumentContext doc = others();

        assertThat(map(doc, "$.paths['/books/{id}'].head.responses['200']"))
                .containsKey("headers")
                .doesNotContainKey("content");
    }

    @Test
    void aNullableQueryRecordRequiresNoParameter() throws JsonProcessingException {
        DocumentContext doc = others();

        // Without any of its parameters, a @Nullable QueryRecord is null, so minBooks is optional here.
        assertThat(parameter(doc, "/reviews", "get", "minBooks"))
                .containsEntry("in", "query")
                .containsEntry("required", false);
        assertThat(parameter(doc, "/reviews", "get", "country")).containsEntry("required", false);
    }

    @Test
    void outputHeadersListEnumConstantNames() throws JsonProcessingException {
        DocumentContext doc = others();

        // The ConversionService writes a header value: an enum's constant name.
        assertThat(map(doc, "$.paths['/stock'].get.responses['200'].headers['X-Availability'].schema"))
                .isEqualTo(Map.of("type", "string", "enum", List.of("IN_STOCK", "GONE")));
        assertThat(map(doc, "$.paths['/stock'].get.responses['200'].headers['X-Previous'].schema"))
                .isEqualTo(Map.of("type", "string", "enum", List.of("IN_STOCK", "GONE")));
        // A collection is one header value per element.
        assertThat(map(doc, "$.paths['/stock'].get.responses['200'].headers['X-History'].schema"))
                .isEqualTo(Map
                        .of("type", "array", "items", Map
                                .of("type", "string", "enum", List
                                        .of("IN_STOCK",
                                                "GONE"))));
        // The mapper writes the body: the enum's JSON names.
        List<String> body = doc.read("$.components.schemas.Availability.enum");
        assertThat(body).containsExactly("in-stock", "gone");
    }

    @Test
    void operationIdsFollowTheDeclarationOrder() throws JsonProcessingException {
        List<RouteDefinition> declared = BookRoutes.books(PAGING);
        // The routes bean holds the most specific template first: /v2/books/{id} before /books/{id}.
        List<RouteDefinition> compiled = new ArrayList<>(declared);
        Collections.reverse(compiled);

        List<RouteDefinition> ordered = RouteDocumenter.inDeclarationOrder(compiled, declared);
        DocumentContext doc = json(documenter(PAGING, NO_OVERRIDES).document(ordered));

        assertThat(ordered).isEqualTo(declared);
        // The first declared route of get-book keeps the plain name; the version 2 route reusing it gets -2.
        assertThat(doc.read("$.paths['/books/{id}'].get.operationId", String.class)).isEqualTo("get-book");
        assertThat(doc.read("$.paths['/v2/books/{id}'].get.operationId", String.class)).isEqualTo("get-book-2");
        // A route that wasn't declared comes last, in its order.
        List<RouteDefinition> undeclared = RouteDocumenter.inDeclarationOrder(compiled, declared.subList(1, 3));
        assertThat(undeclared.subList(0, 2)).isEqualTo(declared.subList(1, 3));
        assertThat(undeclared.subList(2, undeclared.size()))
                .containsExactlyElementsOf(compiled
                        .stream()
                        .filter(route -> !declared.subList(1, 3).contains(route))
                        .toList());
    }

    @Test
    void springdocPathFiltersSelectTheRoutes() {
        List<RouteDefinition> routes = BookRoutes.books(PAGING);

        assertThat(RouteDocumenter.matching(routes, null, null)).isEqualTo(routes);
        assertThat(RouteDocumenter.matching(routes, List.of(), List.of())).isEqualTo(routes);
        // Ant patterns against the documented template: /books/** also matches /books itself.
        assertThat(paths(RouteDocumenter.matching(routes, List.of("/books/**"), null)))
                .containsOnly("/books/{id}", "/books", "/books/stats", "/books/{id}/loans", "/books/{id}/cover");
        assertThat(paths(RouteDocumenter.matching(routes, null, List.of("/v2/**", "/books/*/loans"))))
                .doesNotContain("/v2/books/{id}", "/books/{id}/loans")
                .contains("/books/{id}", "/authors");
        // A route must match and not be excluded.
        assertThat(paths(RouteDocumenter.matching(routes, List.of("/books/**"), List.of("/books/stats", "/*/*/c*"))))
                .containsOnly("/books/{id}", "/books", "/books/{id}/loans");
    }

    private static List<String> paths(List<RouteDefinition> routes) {
        return routes.stream().map(RouteDefinition::path).toList();
    }

    @Test
    void enumParametersListConstantNames() throws JsonProcessingException {
        DocumentContext doc = json(documenter(PAGING, NO_OVERRIDES).document(BookRoutes.enums()));
        Map<String, Object> shelving = Map.of("type", "string", "enum", List.of("ON_SHELF", "LENT"));
        Map<String, Object> months = Map
                .of("type", "string", "enum", Arrays.stream(Month.values()).map(Enum::name).toList());

        // The ConversionService binds a parameter: Enum.valueOf, so the constant names, not the JSON names.
        assertThat(parameter(doc, "/calendar/{month}", "get", "month"))
                .containsEntry("in", "path")
                .containsEntry("schema", months);
        assertThat(parameter(doc, "/calendar/{month}", "get", "shelving"))
                .containsEntry("in", "query")
                .containsEntry("schema", shelving);
        assertThat(parameter(doc, "/calendar/{month}", "get", "since"))
                .containsEntry("in", "query")
                .containsEntry("schema", months);
        assertThat(parameter(doc, "/calendar/{month}", "get", "any"))
                .containsEntry("in", "query")
                .containsEntry("schema", Map.of("type", "array", "items", shelving));
        assertThat(parameter(doc, "/calendar/{month}", "get", "day"))
                .containsEntry("in", "query")
                .containsEntry("required", false)
                .containsEntry("schema", Map
                        .of("type", "string", "enum", Arrays.stream(DayOfWeek.values()).map(Enum::name).toList()));
        assertThat(parameter(doc, "/calendar/{month}", "get", "X-Shelving"))
                .containsEntry("in", "header")
                .containsEntry("schema", shelving);
        // The mapper reads the body: the same enum there is its component, with the JSON names.
        assertThat(doc
                .read("$.paths['/shelving/{id}'].put.requestBody.content['application/json'].schema.properties"
                        + ".shelving['$ref']", String.class))
                .isEqualTo(SCHEMAS + "Shelving");
        List<String> body = doc.read("$.components.schemas.Shelving.enum");
        assertThat(body).containsExactly("on-shelf", "lent");
        // Parameters reference no component.
        assertThat(map(doc, "$.components.schemas")).doesNotContainKeys("Month", "DayOfWeek");
    }

    @Test
    void contextAndCustomComponentsAreNotParameters() throws JsonProcessingException {
        DocumentContext doc = others();

        List<String> names = doc.read("$.paths['/whoami'].get.parameters[*].name");
        assertThat(names).containsExactlyInAnyOrder("X-Correlation-Id", "Request-Timeout");
        assertThat(map(doc, "$.paths['/whoami'].get")).doesNotContainKey("requestBody");
    }

    @Test
    void aNullableBodyComponentMayBeNoContent() throws JsonProcessingException {
        DocumentContext doc = others();

        assertThat(responses(doc, "/covers/{id}", "get")).containsKeys("200", "204");
        assertThat(doc
                .read("$.paths['/covers/{id}'].get.responses['200'].content['application/json'].schema['$ref']",
                        String.class))
                .isEqualTo(SCHEMAS + "Image");
        assertThat(map(doc, "$.paths['/covers/{id}'].get.responses['204']")).doesNotContainKey("content");
        // A non-nullable body component is always sent.
        assertThat(responses(doc, "/books/{id}/cover", "put")).containsKey("204");
    }

    @Test
    void generatedOperationIdsSkipRealOperationNames() throws JsonProcessingException {
        DocumentContext doc = json(documenter(PAGING, NO_OVERRIDES).document(BookRoutes.clashes()));

        assertThat(doc.read("$.paths['/a'].get.operationId", String.class)).isEqualTo("list-shelves");
        assertThat(doc.read("$.paths['/b'].get.operationId", String.class)).isEqualTo("list-shelves-3");
        assertThat(doc.read("$.paths['/c'].get.operationId", String.class)).isEqualTo("list-shelves-2");
    }

    @Test
    void templatesThatDifferInVariableNamesShareOnePath() throws JsonProcessingException {
        DocumentContext doc = json(documenter(PAGING, NO_OVERRIDES).document(BookRoutes.clashes()));

        assertThat(map(doc, "$.paths")).containsKey("/shelves/{id}").doesNotContainKey("/shelves/{shelfId}");
        assertThat(map(doc, "$.paths['/shelves/{id}']")).containsOnlyKeys("get", "put");
        // The later route's variable takes the first template's name.
        assertThat(parameter(doc, "/shelves/{id}", "put", "id"))
                .containsEntry("in", "path")
                .containsEntry("required", true);
        List<String> put = doc.read("$.paths['/shelves/{id}'].put.parameters[*].name");
        assertThat(put).doesNotContain("shelfId");
    }

    @Test
    void synapseComponentNamesAreReserved() throws JsonProcessingException {
        DocumentContext doc = json(documenter(PAGING, NO_OVERRIDES).document(BookRoutes.clashes()));

        // An application record named SynapseProblem takes its qualified name, as any clash does.
        String qualified = BookRoutes.SynapseProblem.class.getCanonicalName();
        assertThat(doc
                .read("$.paths['/c'].get.responses['200'].content['application/json'].schema['$ref']",
                        String.class))
                .isEqualTo(SCHEMAS + qualified);
        assertThat(map(doc, "$.components.schemas['" + qualified + "'].properties")).containsOnlyKeys("reason");
        assertThat(map(doc, "$.components.schemas.SynapseProblem.properties")).containsKeys("code", "correlationId");
    }

    private static DocumentContext others() throws JsonProcessingException {
        return json(documenter(PAGING, NO_OVERRIDES).document(BookRoutes.others(PAGING)));
    }

    private static RouteDocumenter documenter(PagingSettings paging, RestErrorStatuses statuses) {
        return new RouteDocumenter(new SchemaGenerator(MAPPER, VALIDATOR), paging, statuses, "X-Correlation-Id",
                "Request-Timeout");
    }

    private static DocumentContext books() throws JsonProcessingException {
        return json(documenter(PAGING, NO_OVERRIDES).document(BookRoutes.books(PAGING)));
    }

    /** The fragment as springdoc serves it: OpenAPI 3.1 JSON. */
    private static DocumentContext json(RouteDocumenter.Fragment fragment) throws JsonProcessingException {
        Paths paths = new Paths();
        paths.putAll(fragment.paths());
        Components components = new Components();
        fragment.schemas().forEach(components::addSchemas);
        fragment.headers().forEach(components::addHeaders);
        OpenAPI api = new OpenAPI(SpecVersion.V31).openapi("3.1.0").paths(paths).components(components);
        return JsonPath.parse(Json31.mapper().writeValueAsString(api));
    }

    private static Map<String, Object> parameter(DocumentContext doc, String path, String method, String name) {
        List<Map<String, Object>> found = doc
                .read("$.paths['" + path + "']." + method + ".parameters[?(@.name == '" + name + "')]");
        assertThat(found).as("%s %s parameter %s", method, path, name).hasSize(1);
        return found.getFirst();
    }

    private static Map<String, Object> map(DocumentContext doc, String path) {
        return doc.read(path);
    }

    private static Map<String, Object> responses(DocumentContext doc, String path, String method) {
        return doc.read("$.paths['" + path + "']." + method + ".responses");
    }

    private static String description(DocumentContext doc, String path, String method, String status) {
        Map<String, Object> response = doc.read("$.paths['" + path + "']." + method + ".responses['" + status + "']");
        assertThat(response).containsKey("content");
        assertThat(doc
                .read("$.paths['" + path + "']." + method + ".responses['" + status + "'].content['"
                        + PROBLEM_JSON + "'].schema['$ref']", String.class))
                .isEqualTo(SCHEMAS + "SynapseProblem");
        return doc.read("$.paths['" + path + "']." + method + ".responses['" + status + "'].description");
    }
}
