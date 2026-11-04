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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Cookie;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.http.MediaType;
import org.springframework.mock.web.DelegatingServletInputStream;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import tools.jackson.databind.exc.InvalidDefinitionException;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.RestConventions;
import io.americanexpress.synapse.api.rest.RestErrors;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.AliasedOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.AliasedRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.AnnotateBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.AnnotateBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.AuditBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.AuditedBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.BookChanges;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.CheckedBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.ClashOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.CodedOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.CountedBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.CreateBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.CreateBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.CustomBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.CustomBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.DatedOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.FindBooksOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.FindBooksRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.GuardedOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.GuardedRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.HeaderRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.HeadersOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.HiddenOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PatchBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PatchBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PathDatedOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PathDatedRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PingOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.RangeQuery;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.RangeSearchOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.RangeSearchRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.ScheduleBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.ScheduleBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.StockBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.StockBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.StrictBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.StrictBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.TenantId;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.ThrowingRecordOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.UpdateBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.UpdateBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.WriteOptions;
import io.americanexpress.synapse.api.rest.internal.edge.RequestTooLargeException;
import io.americanexpress.synapse.api.rest.internal.edge.SizeLimitedRequest;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.context.RequestContext;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.test.operation.RequestContexts;

import static io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.BLANK_TITLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class RequestBinderTest {

    private static final UUID ONE = new UUID(0, 1);

    private static final UUID TWO = new UUID(0, 2);

    private final RequestBinder binder = new RequestBinder(JsonMapper.builder().build(),
            new DefaultFormattingConversionService());

    private final RestConventions writeOptionsFromQuery = RestConventions.builder().query(WriteOptions.class).build();

    private final RouteDefinition updateBook = route(
            RestBindings.builder().put("/books/{id}", new UpdateBookOperation()).build(), writeOptionsFromQuery);

    private final RouteDefinition createBook = route(
            RestBindings.builder().post("/books", new CreateBookOperation()).build());

    private final RouteDefinition findBooks = route(
            RestBindings.builder().get("/books", new FindBooksOperation()).build(), writeOptionsFromQuery);

    @Test
    void bindsIdFromPathAndRestFromBody() {
        Object input = bind(updateBook, json(MockMvcRequestBuilders.put("/books/{id}?dryRun=false", ONE),
                "{\"title\":\"T\"}"), Map.of("id", ONE.toString()));

        assertThat(input).isEqualTo(new UpdateBookRequest(ONE, "T", new WriteOptions(false, null)));
    }

    @Test
    void conventionBindsRecordFromQuery() {
        UpdateBookRequest input = (UpdateBookRequest) bind(updateBook,
                json(MockMvcRequestBuilders.put("/books/{id}?dryRun=true&reason=audit", ONE), "{\"title\":\"T\"}"),
                Map.of("id", ONE.toString()));

        assertThat(Objects.requireNonNull(input).options()).isEqualTo(new WriteOptions(true, "audit"));
        assertThat(input.options().dryRun()).isTrue();
    }

    @Test
    void mismatchedBodyIdIs400() {
        assertViolations(() -> bind(updateBook,
                json(MockMvcRequestBuilders.put("/books/{id}?dryRun=true", ONE),
                        "{\"id\":\"" + TWO + "\",\"title\":\"T\"}"),
                Map.of("id", ONE.toString())), new Violation("id", "conflicts with the value from the path"));
        assertViolations(() -> bind(updateBook,
                json(MockMvcRequestBuilders.put("/books/{id}?dryRun=true", ONE), "{\"id\":null,\"title\":\"T\"}"),
                Map.of("id", ONE.toString())), new Violation("id", "conflicts with the value from the path"));
    }

    @Test
    void sameBodyIdIsAccepted() {
        Object input = bind(updateBook,
                json(MockMvcRequestBuilders.put("/books/{id}?dryRun=true", ONE),
                        "{\"id\":\"" + ONE + "\",\"title\":\"T\",\"options\":{\"dryRun\":true}}"),
                Map.of("id", ONE.toString()));

        assertThat(input).isEqualTo(new UpdateBookRequest(ONE, "T", new WriteOptions(true, null)));
    }

    @Test
    void unknownFieldsAreIgnored() {
        Object implicit = bind(createBook,
                json(MockMvcRequestBuilders.post("/books"), "{\"title\":\"T\",\"extra\":1}"), Map.of());
        RouteDefinition patch = route(RestBindings
                .builder()
                .put("/books/{id}", new PatchBookOperation())
                .body(PatchBookRequest::changes)
                .build());
        Object whole = bind(patch,
                json(MockMvcRequestBuilders.put("/books/{id}", ONE), "{\"title\":\"T\",\"extra\":1}"),
                Map.of("id", ONE.toString()));

        Object nested = bind(route(RestBindings.builder().post("/books/search", new FindBooksOperation()).build()),
                json(MockMvcRequestBuilders.post("/books/search"), "{\"options\":{\"dryRun\":true,\"extra\":1}}"),
                Map.of());

        assertThat(implicit).isEqualTo(new CreateBookRequest("T"));
        assertThat(whole).isEqualTo(new PatchBookRequest(ONE, new BookChanges("T")));
        assertThat(nested).isEqualTo(new FindBooksRequest(null, null, new WriteOptions(true, null)));
    }

    @Test
    void ignoredBodyPropertyIsNotRead() {
        RouteDefinition audit = route(RestBindings.builder().post("/audits", new AuditBookOperation()).build());

        assertThat(bind(audit, json(MockMvcRequestBuilders.post("/audits"), "{\"title\":\"T\",\"auditNote\":\"x\"}"),
                Map.of())).isEqualTo(new AuditedBookRequest("T", null));
    }

    @Test
    void componentsJacksonWouldNotReadAreNotSetFromTheBody() {
        RouteDefinition guarded = route(RestBindings.builder().post("/guarded", new GuardedOperation()).build());

        assertThat(bind(guarded,
                json(MockMvcRequestBuilders.post("/guarded"),
                        "{\"name\":\"n\",\"note\":\"x\",\"role\":\"admin\",\"secret\":\"s\"}"),
                Map.of())).isEqualTo(new GuardedRequest("n", null, null, null));
    }

    @Test
    void jacksonComponentAnnotationsApplyToTheBody() {
        RouteDefinition schedule = route(RestBindings.builder().post("/schedule", new ScheduleBookOperation()).build());

        assertThat(bind(schedule,
                json(MockMvcRequestBuilders.post("/schedule"), "{\"title\":\"T\",\"releaseOn\":\"24/12/2026\"}"),
                Map.of())).isEqualTo(new ScheduleBookRequest("T", LocalDate.of(2026, 12, 24)));
        assertViolations(() -> bind(schedule,
                json(MockMvcRequestBuilders.post("/schedule"), "{\"title\":\"T\",\"releaseOn\":\"2026-12-24\"}"),
                Map.of()), new Violation("releaseOn", "is not a valid value"));
    }

    @Test
    void propertyJacksonReadsAsNullIsMissing() {
        RouteDefinition schedule = route(RestBindings.builder().post("/schedule", new ScheduleBookOperation()).build());

        assertViolations(() -> bind(schedule,
                json(MockMvcRequestBuilders.post("/schedule"), "{\"title\":\"T\",\"releaseOn\":\"\"}"), Map.of()),
                new Violation("releaseOn", "must not be null"));
    }

    @Test
    void absentValueBoundElsewhereOverridesTheBody() {
        RouteDefinition search = route(RestBindings
                .builder()
                .post("/books/search", new FindBooksOperation())
                .query(FindBooksRequest::limit)
                .build());

        assertThat(bind(search,
                json(MockMvcRequestBuilders.post("/books/search"),
                        "{\"author\":[\"a\"],\"options\":{\"dryRun\":true}}"),
                Map.of())).isEqualTo(new FindBooksRequest(List.of("a"), null, new WriteOptions(true, null)));
        assertViolations(() -> bind(search,
                json(MockMvcRequestBuilders.post("/books/search"), "{\"limit\":3}"), Map.of()),
                new Violation("limit", "conflicts with the value from the query string"));
    }

    @Test
    void recordThatChecksItsOwnComponentsBindsWithAPathId() {
        RouteDefinition strict = route(RestBindings.builder().put("/books/{id}", new StrictBookOperation()).build());

        assertThat(bind(strict, json(MockMvcRequestBuilders.put("/books/{id}", ONE), "{\"title\":\"T\"}"),
                Map.of("id", ONE.toString()))).isEqualTo(new StrictBookRequest(ONE, "T"));
    }

    @Test
    void queryValuesComeOnlyFromTheQueryString() {
        assertThat(bind(findBooks,
                MockMvcRequestBuilders
                        .get("/books")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("limit=5&author=form"),
                Map.of())).isEqualTo(new FindBooksRequest(null, null, null));
        assertThat(bind(findBooks,
                MockMvcRequestBuilders.get(URI.create("/books?author=a%20b&author=c+d&author=%C3%A9&&limit=7")),
                Map.of())).isEqualTo(new FindBooksRequest(List.of("a b", "c d", "é"), 7, null));
    }

    @Test
    void malformedQueryEscapeIsMalformedRequest() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/books");
        servletRequest.setQueryString("limit=%zz");

        assertError(() -> binder.bind(findBooks, ServerRequest.create(servletRequest, List.of())),
                SynapseErrors.MALFORMED_REQUEST);
    }

    @Test
    void implicitQueryOnlyOnGet() {
        Object found = bind(findBooks, MockMvcRequestBuilders.get("/books?author=a&author=b"), Map.of());
        RouteDefinition putBook = route(RestBindings.builder().put("/books", new CreateBookOperation()).build());

        assertThat(found).isEqualTo(new FindBooksRequest(List.of("a", "b"), null, null));
        assertViolations(() -> bind(putBook, MockMvcRequestBuilders.put("/books?title=x"), Map.of()),
                new Violation("title", "must not be null"));
    }

    @Test
    void conversionFailureIsViolation() {
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> bind(findBooks, MockMvcRequestBuilders.get("/books?limit=abc"), Map.of()))
                .satisfies(e -> {
                    assertThat(e.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED);
                    assertThat(e.violations()).containsExactly(new Violation("limit", "is not a valid value"));
                    assertThat(e.getMessage()).doesNotContain("abc").doesNotContain("Integer");
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test
    void missingNonNullableComponentIsViolation() {
        assertViolations(() -> bind(createBook, json(MockMvcRequestBuilders.post("/books"), "{}"), Map.of()),
                new Violation("title", "must not be null"));
    }

    @Test
    void missingPrimitiveBodyPropertyIsViolation() {
        RouteDefinition stock = route(RestBindings.builder().post("/stock", new StockBookOperation()).build());

        assertViolations(
                () -> bind(stock, json(MockMvcRequestBuilders.post("/stock"), "{\"title\":\"T\"}"), Map.of()),
                new Violation("copies", "must not be null"));
        assertThat(bind(stock, json(MockMvcRequestBuilders.post("/stock"), "{\"title\":\"T\",\"copies\":0}"),
                Map.of())).isEqualTo(new StockBookRequest("T", 0));
    }

    @Test
    void malformedJsonIsMalformedRequest() {
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> bind(createBook, json(MockMvcRequestBuilders.post("/books"), "{bad"), Map.of()))
                .satisfies(e -> {
                    assertThat(e.details()).isEqualTo(SynapseErrors.MALFORMED_REQUEST);
                    assertThat(e.violations()).isEmpty();
                    assertThat(e.getMessage()).isEqualTo("The request is malformed");
                });
    }

    @Test
    void bodyThatIsNotAnObjectIsMalformed() {
        assertError(() -> bind(createBook, json(MockMvcRequestBuilders.post("/books"), "[\"T\"]"), Map.of()),
                SynapseErrors.MALFORMED_REQUEST);
    }

    @Test
    void nonJsonContentTypeIsUnsupported() {
        assertError(() -> bind(createBook,
                MockMvcRequestBuilders.post("/books").contentType(MediaType.TEXT_PLAIN).content("{\"title\":\"T\"}"),
                Map.of()), RestErrors.UNSUPPORTED_MEDIA_TYPE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/xml", "text/json", "not a media type", "*/*"})
    void otherContentTypesAreUnsupported(String contentType) {
        assertError(() -> bind(createBook,
                MockMvcRequestBuilders.post("/books").contentType(contentType).content("{\"title\":\"T\"}"),
                Map.of()), RestErrors.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void bodyWithoutContentTypeIsUnsupported() {
        assertError(
                () -> bind(createBook, MockMvcRequestBuilders.post("/books").content("{\"title\":\"T\"}"), Map.of()),
                RestErrors.UNSUPPORTED_MEDIA_TYPE);
        assertError(() -> bind(createBook, MockMvcRequestBuilders.post("/books").header("Transfer-Encoding", "chunked"),
                Map.of()), RestErrors.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void bodyStreamFailureIsMalformedRequest() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/books") {
            @Override
            public ServletInputStream getInputStream() {
                return new DelegatingServletInputStream(new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("connection reset");
                    }

                    @Override
                    public int read(byte[] buffer, int offset, int length) throws IOException {
                        throw new IOException("connection reset");
                    }
                });
            }
        };
        servletRequest.setContentType("application/json");

        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> binder.bind(createBook, ServerRequest.create(servletRequest, List.of())))
                .satisfies(e -> {
                    assertThat(e.details()).isEqualTo(SynapseErrors.MALFORMED_REQUEST);
                    assertThat(e.getMessage()).isEqualTo("The request is malformed");
                    assertThat(e).rootCause().isInstanceOf(IOException.class);
                });
    }

    @Test
    void bodyOverTheLimitIsPayloadTooLarge() {
        byte[] body = "{\"title\":\"a title longer than the limit\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest declared = new MockHttpServletRequest("POST", "/books");
        declared.setContentType("application/json");
        declared.setContent(body);
        // Chunked: no declared length, so the limit trips while Jackson reads the stream.
        MockHttpServletRequest chunked = new MockHttpServletRequest("POST", "/books") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        chunked.setContentType("application/json");
        chunked.setContent(body);

        for (MockHttpServletRequest request : List.of(declared, chunked)) {
            SizeLimitedRequest limited = new SizeLimitedRequest(request, 16);
            assertThatExceptionOfType(SynapseException.class)
                    .isThrownBy(() -> binder.bind(createBook, ServerRequest.create(limited, List.of())))
                    .satisfies(e -> {
                        assertThat(e.details()).isEqualTo(RestErrors.PAYLOAD_TOO_LARGE);
                        assertThat(e).rootCause().isInstanceOf(RequestTooLargeException.class);
                    });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "application/json;charset=UTF-8", "application/merge-patch+json"})
    void jsonContentTypesAreRead(String contentType) {
        Object input = bind(createBook,
                MockMvcRequestBuilders.post("/books").contentType(contentType).content("{\"title\":\"T\"}"), Map.of());

        assertThat(input).isEqualTo(new CreateBookRequest("T"));
    }

    @Test
    void absentOrEmptyBodyCountsAsEmptyObject() {
        assertViolations(() -> bind(createBook, MockMvcRequestBuilders.post("/books"), Map.of()),
                new Violation("title", "must not be null"));
        assertViolations(() -> bind(createBook, json(MockMvcRequestBuilders.post("/books"), ""), Map.of()),
                new Violation("title", "must not be null"));
    }

    @Test
    void bodyValueOfTheWrongTypeIsViolation() {
        RouteDefinition stock = route(RestBindings.builder().post("/stock", new StockBookOperation()).build());

        assertViolations(() -> bind(stock,
                json(MockMvcRequestBuilders.post("/stock"), "{\"title\":{\"secret\":1},\"copies\":\"many\"}"),
                Map.of()), new Violation("title", "is not a valid value"),
                new Violation("copies", "is not a valid value"));
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> bind(stock,
                        json(MockMvcRequestBuilders.post("/stock"), "{\"title\":\"T\",\"copies\":\"many\"}"), Map.of()))
                .satisfies(e -> {
                    assertThat(e.violations()).containsExactly(new Violation("copies", "is not a valid value"));
                    assertThat(e.getMessage()).doesNotContain("many").doesNotContain("int");
                });
        assertViolations(() -> bind(stock,
                json(MockMvcRequestBuilders.post("/stock"), "{\"title\":null,\"copies\":null}"), Map.of()),
                new Violation("title", "must not be null"), new Violation("copies", "must not be null"));
    }

    @Test
    void headerCookieContextAndCustomSources() {
        RouteDefinition headers = route(RestBindings
                .builder()
                .get("/books", new HeadersOperation())
                .header("X-Channel", HeaderRequest::channel)
                .cookie("session", HeaderRequest::session)
                .context(HeaderRequest::requestedBy, RequestContext::correlationId)
                .from(HeaderRequest::region, request -> request.servletRequest().getRemoteAddr())
                .build(), RestConventions.builder().header("X-Tenant", TenantId.class).build());
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders
                .get("/books")
                .header("X-Channel", "web")
                .header("X-Tenant", "acme")
                .cookie(new Cookie("session", "s-1"));

        Object input = RequestContexts
                .with()
                .correlationId("c-1")
                .call(() -> Objects.requireNonNull(bind(headers, request, Map.of())));

        assertThat(input).isEqualTo(new HeaderRequest("web", "s-1", "c-1", "127.0.0.1", new TenantId("acme")));
    }

    @Test
    void missingHeaderAndCookieAreViolations() {
        RouteDefinition headers = route(RestBindings
                .builder()
                .get("/books", new HeadersOperation())
                .header("X-Channel", HeaderRequest::channel)
                .cookie("session", HeaderRequest::session)
                .context(HeaderRequest::requestedBy, RequestContext::correlationId)
                .from(HeaderRequest::region, request -> "eu")
                .header("X-Tenant", HeaderRequest::tenant)
                .build());

        assertViolations(() -> RequestContexts
                .with()
                .call(() -> Objects.requireNonNull(bind(headers, MockMvcRequestBuilders.get("/books"), Map.of()))),
                new Violation("channel", "must not be null"), new Violation("session", "must not be null"),
                new Violation("tenant", "must not be null"));
    }

    @Test
    void bodyComponentBindsWholeBody() {
        RouteDefinition patch = route(RestBindings
                .builder()
                .put("/books/{id}", new PatchBookOperation())
                .body(PatchBookRequest::changes)
                .build());

        PatchBookRequest input = (PatchBookRequest) bind(patch,
                json(MockMvcRequestBuilders.put("/books/{id}", ONE), "{\"title\":\"T\"}"),
                Map.of("id", ONE.toString()));

        assertThat(Objects.requireNonNull(input).changes()).isEqualTo(new BookChanges("T"));
        assertThat(input.id()).isEqualTo(ONE);
    }

    @Test
    void bodyComponentOfTheWrongShapeIsViolation() {
        RouteDefinition patch = route(RestBindings
                .builder()
                .put("/books/{id}", new PatchBookOperation())
                .body(PatchBookRequest::changes)
                .build());

        assertViolations(() -> bind(patch, json(MockMvcRequestBuilders.put("/books/{id}", ONE), "[1]"),
                Map.of("id", ONE.toString())), new Violation("changes", "is not a valid value"));
        assertViolations(() -> bind(patch, json(MockMvcRequestBuilders.put("/books/{id}", ONE), "null"),
                Map.of("id", ONE.toString())), new Violation("changes", "must not be null"));
    }

    @Test
    void conflictsNameEachLocationInComponentOrder() {
        RouteDefinition annotate = annotateRoute();
        String body = "{\"id\":\"" + TWO + "\",\"channel\":\"app\",\"session\":\"s-2\",\"requestedBy\":\"mallory\","
                + "\"region\":\"us\",\"note\":\"other\",\"text\":\"hello\"}";

        assertViolations(() -> RequestContexts
                .with()
                .correlationId("c-1")
                .call(() -> Objects
                        .requireNonNull(bind(annotate, annotateRequest(body), Map.of("id", ONE.toString())))),
                new Violation("id", "conflicts with the value from the path"),
                new Violation("channel", "conflicts with the value from the header"),
                new Violation("session", "conflicts with the value from the cookie"),
                new Violation("requestedBy", "conflicts with the value from the request context"),
                new Violation("region", "conflicts with the value from the request"),
                new Violation("note", "conflicts with the value from the query string"));
    }

    @Test
    void equalValuesFromEveryLocationAreAccepted() {
        RouteDefinition annotate = annotateRoute();
        String body = "{\"id\":\"" + ONE + "\",\"channel\":\"web\",\"session\":\"s-1\",\"requestedBy\":\"c-1\","
                + "\"region\":\"eu\",\"note\":\"first\",\"text\":\"hello\"}";

        Object input = RequestContexts
                .with()
                .correlationId("c-1")
                .call(() -> Objects
                        .requireNonNull(bind(annotate, annotateRequest(body), Map.of("id", ONE.toString()))));

        assertThat(input).isEqualTo(new AnnotateBookRequest(ONE, "web", "s-1", "c-1", "eu", "first", "hello"));
    }

    @Test
    void bodyValueThatCannotBeReadConflicts() {
        assertViolations(() -> bind(updateBook,
                json(MockMvcRequestBuilders.put("/books/{id}?dryRun=true", ONE),
                        "{\"title\":\"T\",\"options\":{\"dryRun\":\"perhaps\"}}"),
                Map.of("id", ONE.toString())),
                new Violation("options", "conflicts with the value from the query string"));
    }

    @Test
    void invalidValueSkipsTheConflictCheck() {
        assertViolations(() -> bind(updateBook,
                json(MockMvcRequestBuilders.put("/books/not-a-uuid?dryRun=true"),
                        "{\"id\":\"" + TWO + "\",\"title\":\"T\"}"),
                Map.of("id", "not-a-uuid")), new Violation("id", "is not a valid value"));
    }

    @Test
    void queryRecordWithoutParametersIsNullWhenNullable() {
        assertThat(bind(findBooks, MockMvcRequestBuilders.get("/books"), Map.of()))
                .isEqualTo(new FindBooksRequest(null, null, null));
        assertThat(bind(findBooks, MockMvcRequestBuilders.get("/books?dryRun=true&limit=5"), Map.of()))
                .isEqualTo(new FindBooksRequest(null, 5, new WriteOptions(true, null)));
    }

    @Test
    void queryRecordComponentsReportTheirOwnViolations() {
        assertViolations(() -> bind(findBooks, MockMvcRequestBuilders.get("/books?dryRun=perhaps&limit=x"), Map.of()),
                new Violation("limit", "is not a valid value"), new Violation("dryRun", "is not a valid value"));
        assertViolations(() -> bind(findBooks, MockMvcRequestBuilders.get("/books?reason=audit"), Map.of()),
                new Violation("dryRun", "must not be null"));
        assertViolations(() -> bind(updateBook,
                json(MockMvcRequestBuilders.put("/books/{id}", ONE), "{\"title\":\"T\"}"),
                Map.of("id", ONE.toString())),
                new Violation("dryRun", "must not be null"));
    }

    @Test
    void voidInputBindsNull() {
        RouteDefinition ping = route(RestBindings.builder().get("/ping", new PingOperation()).build());

        assertThat(bind(ping, MockMvcRequestBuilders.get("/ping"), Map.of())).isNull();
    }

    @Test
    void bodyIsNotReadOnGet() {
        RouteDefinition find = route(RestBindings.builder().get("/books", new FindBooksOperation()).build());

        assertThat(bind(find,
                MockMvcRequestBuilders.get("/books?limit=1").contentType(MediaType.TEXT_PLAIN).content("{bad"),
                Map.of())).isEqualTo(new FindBooksRequest(null, 1, null));
    }

    @Test
    void exceptionFromTheConstructorPropagates() {
        RouteDefinition checked = route(RestBindings.builder().post("/checked", new CheckedBookOperation()).build());

        // The record's own error reaches the caller as it is, not wrapped in VALIDATION_FAILED.
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> bind(checked, json(MockMvcRequestBuilders.post("/checked"), "{\"title\":\" \"}"),
                        Map.of()))
                .satisfies(e -> {
                    assertThat(e).isSameAs(BLANK_TITLE.get());
                    assertThat(e.details()).isEqualTo(SynapseErrors.CONFLICT);
                });
    }

    @Test
    void componentDeserializerFailureIsViolationWithoutItsMessage() {
        RouteDefinition coded = route(RestBindings.builder().post("/coded", new CodedOperation()).build());

        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> bind(coded,
                        json(MockMvcRequestBuilders.post("/coded"), "{\"code\":\"a\",\"title\":\"T\"}"), Map.of()))
                .satisfies(e -> {
                    assertThat(e.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED);
                    assertThat(e.violations()).containsExactly(new Violation("code", "is not a valid value"));
                    assertThat(e.getMessage()).doesNotContain("SECRET");
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test
    void componentDeserializerFailureInTheConflictCheckIsAConflict() {
        RouteDefinition coded = route(
                RestBindings.builder().put("/coded/{code}", new CodedOperation()).build());

        assertViolations(() -> bind(coded,
                json(MockMvcRequestBuilders.put("/coded/a"), "{\"code\":\"a\",\"title\":\"T\"}"), Map.of("code", "a")),
                new Violation("code", "conflicts with the value from the path"));
    }

    @Test
    void recordDeserializerFailureIsMalformedWithoutItsMessage() {
        RouteDefinition throwing = route(
                RestBindings.builder().post("/throwing", new ThrowingRecordOperation()).build());

        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> bind(throwing,
                        json(MockMvcRequestBuilders.post("/throwing"), "{\"title\":\"T\"}"), Map.of()))
                .satisfies(e -> {
                    assertThat(e.details()).isEqualTo(SynapseErrors.MALFORMED_REQUEST);
                    assertThat(e.violations()).isEmpty();
                    assertThat(e.getMessage()).isEqualTo("The request is malformed");
                    assertThat(e).rootCause().isInstanceOf(IllegalStateException.class);
                });
    }

    @Test
    void queryRecordRejectionIsReportedWithTheOtherViolations() {
        RouteDefinition ranges = route(RestBindings
                .builder()
                .post("/ranges", new RangeSearchOperation())
                .query(RangeSearchRequest::range)
                .build());

        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> bind(ranges,
                        json(MockMvcRequestBuilders.post(URI.create("/ranges?from=5&to=1")), "{}"), Map.of()))
                .satisfies(e -> {
                    assertThat(e.violations())
                            .containsExactly(new Violation("range", "is not a valid value"),
                                    new Violation("title", "must not be null"));
                    assertThat(e.getMessage()).doesNotContain("SECRET");
                });
        assertThat(
                bind(ranges, json(MockMvcRequestBuilders.post(URI.create("/ranges?from=1&to=5")), "{\"title\":\"T\"}"),
                        Map.of()))
                .isEqualTo(new RangeSearchRequest(new RangeQuery(1, 5), "T"));
    }

    @Test
    void constructorRejectionIsValidationFailedWithoutDetail() {
        RouteDefinition counted = route(RestBindings.builder().post("/counted", new CountedBookOperation()).build());

        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> bind(counted,
                        json(MockMvcRequestBuilders.post("/counted"), "{\"title\":\"T\",\"copies\":-1}"), Map.of()))
                .satisfies(e -> {
                    assertThat(e.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED);
                    assertThat(e.violations()).isEmpty();
                    assertThat(e.getMessage()).isEqualTo("The request is invalid");
                    assertThat(e.getCause()).isInstanceOf(IllegalArgumentException.class);
                });
    }

    @Test
    void valueJacksonReadsAsNullNeverReachesAGuardedConstructor() {
        RouteDefinition dated = route(RestBindings.builder().post("/dated", new DatedOperation()).build());

        assertViolations(() -> bind(dated,
                json(MockMvcRequestBuilders.post("/dated"), "{\"title\":\"T\",\"releaseOn\":\"\"}"), Map.of()),
                new Violation("releaseOn", "must not be null"));
    }

    @Test
    void recordWithItsOwnDeserializerBinds() {
        RouteDefinition custom = route(
                RestBindings.builder().put("/custom/{id}", new CustomBookOperation()).build());

        assertThat(bind(custom, json(MockMvcRequestBuilders.put("/custom/{id}", ONE), "{\"title\":\"T\",\"copies\":2}"),
                Map.of("id", ONE.toString()))).isEqualTo(new CustomBookRequest(ONE, "T", 2));
        assertError(() -> bind(custom, json(MockMvcRequestBuilders.put("/custom/{id}", ONE), "{\"copies\":2}"),
                Map.of("id", ONE.toString())), SynapseErrors.MALFORMED_REQUEST);
    }

    @Test
    void formattedComponentBoundFromThePathBinds() {
        RouteDefinition dated = route(RestBindings.builder().put("/p/{day}", new PathDatedOperation()).build());
        LocalDate day = LocalDate.of(2026, 12, 24);

        assertThat(bind(dated, json(MockMvcRequestBuilders.put("/p/2026-12-24"), "{\"title\":\"T\"}"),
                Map.of("day", "2026-12-24"))).isEqualTo(new PathDatedRequest(day, "T"));
        assertThat(bind(dated,
                json(MockMvcRequestBuilders.put("/p/2026-12-24"), "{\"day\":\"24/12/2026\",\"title\":\"T\"}"),
                Map.of("day", "2026-12-24"))).isEqualTo(new PathDatedRequest(day, "T"));
        assertViolations(() -> bind(dated,
                json(MockMvcRequestBuilders.put("/p/2026-12-24"), "{\"day\":\"25/12/2026\",\"title\":\"T\"}"),
                Map.of("day", "2026-12-24")), new Violation("day", "conflicts with the value from the path"));
    }

    @Test
    void everyViolationIsReportedInComponentOrder() {
        RouteDefinition search = route(RestBindings
                .builder()
                .post("/books/search", new FindBooksOperation())
                .query(FindBooksRequest::limit)
                .build());

        assertViolations(() -> bind(search,
                json(MockMvcRequestBuilders.post("/books/search?limit=abc"),
                        "{\"author\":{\"name\":\"a\"},\"options\":{\"dryRun\":\"perhaps\"}}"),
                Map.of()), new Violation("author", "is not a valid value"),
                new Violation("limit", "is not a valid value"), new Violation("options", "is not a valid value"));
    }

    @Test
    void aliasCountsAsPresent() {
        RouteDefinition aliased = route(RestBindings.builder().post("/aliased", new AliasedOperation()).build());

        assertThat(bind(aliased, json(MockMvcRequestBuilders.post("/aliased"), "{\"heading\":\"T\"}"), Map.of()))
                .isEqualTo(new AliasedRequest("T"));
    }

    @Test
    void componentJacksonIgnoresCannotBeBoundFromTheBody() {
        RouteDefinition hidden = route(RestBindings.builder().post("/hidden", new HiddenOperation()).build());

        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> binder.checkBindable(hidden))
                .satisfies(e -> {
                    assertThat(e.description())
                            .isEqualTo("REST route POST /hidden (hidden): component HiddenRequest.internal is bound "
                                    + "from the body but Jackson ignores it");
                    assertThat(e.action()).contains(".path", ".query", ".header", "@JsonIgnore");
                });
    }

    @Test
    void inputTypeJacksonCannotDescribeFailsAtStartup() {
        RouteDefinition clash = route(RestBindings.builder().post("/clash", new ClashOperation()).build());

        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> binder.checkBindable(clash))
                .satisfies(e -> {
                    assertThat(e.description())
                            .startsWith("REST route POST /clash (clash): Jackson cannot read the input type ")
                            .contains("ClashRequest");
                    assertThat(e.action()).contains("ClashRequest");
                    assertThat(e).hasCauseInstanceOf(InvalidDefinitionException.class);
                });
    }

    @Test
    void bindableRoutesPassTheStartupCheck() {
        binder.checkBindable(createBook);
        binder.checkBindable(findBooks);
        binder.checkBindable(route(RestBindings.builder().post("/guarded", new GuardedOperation()).build()));
        binder.checkBindable(route(RestBindings.builder().put("/custom/{id}", new CustomBookOperation()).build()));
        binder.checkBindable(route(RestBindings.builder().get("/ping", new PingOperation()).build()));
    }

    @Test
    void queryEscapesMustBeUtf8() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/books");
        servletRequest.setQueryString("author=%FF");

        assertError(() -> binder.bind(findBooks, ServerRequest.create(servletRequest, List.of())),
                SynapseErrors.MALFORMED_REQUEST);
        assertThat(bind(findBooks, MockMvcRequestBuilders.get(URI.create("/books?author=%E2%82%AC%F0%9F%93%9A")),
                Map.of())).isEqualTo(new FindBooksRequest(List.of("€📚"), null, null));
    }

    @Test
    void emptyQueryValues() {
        // An empty number is no value; an empty string stays an empty string.
        assertThat(bind(findBooks, MockMvcRequestBuilders.get(URI.create("/books?limit=")), Map.of()))
                .isEqualTo(new FindBooksRequest(null, null, null));
        Object annotated = RequestContexts
                .with()
                .correlationId("c-1")
                .call(() -> Objects
                        .requireNonNull(bind(annotateRoute(),
                                json(MockMvcRequestBuilders.post(URI.create("/books/" + ONE + "/notes?note=")),
                                        "{\"text\":\"hello\"}")
                                        .header("X-Channel", "web")
                                        .cookie(new Cookie("session", "s-1")),
                                Map.of("id", ONE.toString()))));
        assertThat(annotated).isEqualTo(new AnnotateBookRequest(ONE, "web", "s-1", "c-1", "eu", "", "hello"));
    }

    private RouteDefinition annotateRoute() {
        return route(RestBindings
                .builder()
                .post("/books/{id}/notes", new AnnotateBookOperation())
                .header("X-Channel", AnnotateBookRequest::channel)
                .cookie("session", AnnotateBookRequest::session)
                .context(AnnotateBookRequest::requestedBy, RequestContext::correlationId)
                .from(AnnotateBookRequest::region, request -> "eu")
                .query("note", AnnotateBookRequest::note)
                .build());
    }

    private static MockHttpServletRequestBuilder annotateRequest(String body) {
        return json(MockMvcRequestBuilders.post("/books/{id}/notes?note=first", ONE), body)
                .header("X-Channel", "web")
                .cookie(new Cookie("session", "s-1"));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private @Nullable Object bind(RouteDefinition route, MockHttpServletRequestBuilder builder,
            Map<String, String> pathVariables) {
        MockHttpServletRequest servletRequest = builder.buildRequest(new MockServletContext());
        servletRequest.setAttribute(RouterFunctions.URI_TEMPLATE_VARIABLES_ATTRIBUTE, pathVariables);
        return binder.bind(route, ServerRequest.create(servletRequest, List.of()));
    }

    private static RouteDefinition route(RestBindings bindings, RestConventions... conventions) {
        return new BindingValidator().validate(bindings.routes(), List.of(conventions)).getFirst();
    }

    private static void assertViolations(Runnable call, Violation... violations) {
        assertThatExceptionOfType(SynapseException.class).isThrownBy(call::run).satisfies(e -> {
            assertThat(e.details()).isEqualTo(SynapseErrors.VALIDATION_FAILED);
            assertThat(e.violations()).containsExactly(violations);
        });
    }

    private static void assertError(Runnable call, ErrorDetails details) {
        assertThatExceptionOfType(SynapseException.class).isThrownBy(call::run).satisfies(e -> {
            assertThat(e.details()).isEqualTo(details);
            assertThat(e.violations()).isEmpty();
        });
    }
}
