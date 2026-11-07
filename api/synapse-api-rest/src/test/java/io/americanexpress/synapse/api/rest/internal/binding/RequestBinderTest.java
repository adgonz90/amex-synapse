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
import org.springframework.boot.autoconfigure.web.format.DateTimeFormatters;
import org.springframework.boot.autoconfigure.web.format.WebConversionService;
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
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.ConditionalReadRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.ConvertOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.ConvertRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.CountedBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.CreateBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.CreateBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.CustomBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.CustomBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.DatedOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.DelegatedBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.DelegatedBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.FindBooksOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.FindBooksRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.GetConditionalBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.GetTaggedBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.GuardedOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.GuardedRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.GuardedRevisionOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.GuardedRevisionRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.HeaderRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.HeadersOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.HiddenOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.Isbn;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.LimitedPageOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.LimitedPageRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.LooseConditionOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.LooseConditionRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PagedBooksOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PagedBooksRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PairFilter;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PatchBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PatchBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PathDatedOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PathDatedRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PathFilterOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PingOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PutBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.PutBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.RangeQuery;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.RangeSearchOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.RangeSearchRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.ReviseBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.ReviseBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.ScheduleBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.ScheduleBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.SearchAllOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.SimpleSourcesOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.SimpleSourcesRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.SortKey;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.StockBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.StockBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.StrictBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.StrictBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.StrictBothOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.StrictBothRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.StrictConditionOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.StrictConditionRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.StrictPutBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.StrictPutBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.TaggedBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.TaggedBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.TaggedReadRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.TenantId;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.ThrowingRecordOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.UpdateBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.UpdateBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.WholeRevisionOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.WholeRevisionRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.WriteOptions;
import io.americanexpress.synapse.api.rest.internal.edge.RequestTooLargeException;
import io.americanexpress.synapse.api.rest.internal.edge.SizeLimitedRequest;
import io.americanexpress.synapse.api.rest.internal.paging.PagingSettings;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.conditional.VersionCondition;
import io.americanexpress.synapse.core.context.RequestContext;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.SortOrder;
import io.americanexpress.synapse.core.paging.SortOrder.Direction;
import io.americanexpress.synapse.test.operation.RequestContexts;

import static io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.BLANK_TITLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class RequestBinderTest {

    private static final UUID ONE = new UUID(0, 1);

    private static final UUID TWO = new UUID(0, 2);

    private final RequestBinder binder = new RequestBinder(JsonMapper.builder().build(),
            new DefaultFormattingConversionService(), PagingSettings.defaults());

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
    void recordWithADelegatingFactoryIsReadByTheFactory() {
        RouteDefinition delegated = route(
                RestBindings.builder().put("/delegated/{id}", new DelegatedBookOperation()).build());
        binder.checkBindable(delegated);

        // The factory reads its own keys; the path still supplies the id.
        assertThat(bind(delegated,
                json(MockMvcRequestBuilders.put("/delegated/{id}", ONE), "{\"name\":\"Dune\",\"count\":2}"),
                Map.of("id", ONE.toString()))).isEqualTo(new DelegatedBookRequest(ONE, "Dune", 2));
        // The canonical constructor's names mean nothing to the factory.
        assertThat(bind(delegated,
                json(MockMvcRequestBuilders.put("/delegated/{id}", ONE), "{\"title\":\"Dune\",\"copies\":2}"),
                Map.of("id", ONE.toString()))).isEqualTo(new DelegatedBookRequest(ONE, "null", 0));
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
    void checkBindableRejectsUnconvertibleSources() {
        ConvertOperation convert = new ConvertOperation();
        assertNotConvertible(RestBindings.builder().get("/x", convert).query("filter", ConvertRequest::filter).build(),
                "REST route GET /x (convert): the component ConvertRequest.filter (type " + PairFilter.class.getName()
                        + ") can't be converted from a string");
        assertNotConvertible(
                RestBindings.builder().get("/x", convert).header("X-Filter", ConvertRequest::filter).build(),
                "REST route GET /x (convert): the component ConvertRequest.filter (type " + PairFilter.class.getName()
                        + ") can't be converted from a string");
        assertNotConvertible(RestBindings.builder().get("/x", convert).cookie("f", ConvertRequest::filter).build(),
                "REST route GET /x (convert): the component ConvertRequest.filter (type " + PairFilter.class.getName()
                        + ") can't be converted from a string");
        // A record bound from the query string is built from its own components, each converted on its own.
        assertNotConvertible(RestBindings.builder().get("/x", convert).query(ConvertRequest::nested).build(),
                "REST route GET /x (convert): the component PairQuery.pair (type " + PairFilter.class.getName()
                        + ") can't be converted from a string");
        assertNotConvertible(RestBindings.builder().get("/x/{filter}", new PathFilterOperation()).build(),
                "REST route GET /x/{filter} (path-filter): the component PathFilterRequest.filter (type "
                        + PairFilter.class.getName() + ") can't be converted from a string");
        assertNotConvertible(RestBindings.builder().get("/x", convert).build(),
                List.of(RestConventions.builder().header("X-Filter", PairFilter.class).build()),
                "the component ConvertRequest.filter (type " + PairFilter.class.getName() + ")");

        // Observed with spring-core 7.0.x: canConvert(String, Isbn) is true, both for the binder's
        // DefaultFormattingConversionService and for Boot's WebConversionService (the class of mvcConversionService),
        // because ObjectToObjectConverter uses the record's canonical Isbn(String) constructor. PairFilter has no
        // String constructor or valueOf/of/from(String) method, so canConvert is false.
        WebConversionService mvcConversionService = new WebConversionService(new DateTimeFormatters());
        assertThat(mvcConversionService.canConvert(String.class, Isbn.class)).isTrue();
        assertThat(new DefaultFormattingConversionService().canConvert(String.class, Isbn.class)).isTrue();
        assertThat(mvcConversionService.canConvert(String.class, PairFilter.class)).isFalse();
        RouteDefinition isbn = route(
                RestBindings.builder().get("/x", convert).query("isbn", ConvertRequest::isbn).build());
        new RequestBinder(JsonMapper.builder().build(), mvcConversionService, PagingSettings.defaults())
                .checkBindable(isbn);
        binder.checkBindable(isbn);
        assertThat(bind(isbn, MockMvcRequestBuilders.get(URI.create("/x?isbn=978-0")), Map.of()))
                .isEqualTo(new ConvertRequest(null, new Isbn("978-0"), null));
    }

    @Test
    void checkBindableAcceptsSimpleAndListTypes() {
        RouteDefinition route = route(RestBindings
                .builder()
                .get("/items/{id}", new SimpleSourcesOperation())
                .query("tag", SimpleSourcesRequest::tags)
                .header("X-Version", SimpleSourcesRequest::version)
                .cookie("since", SimpleSourcesRequest::since)
                .build());

        binder.checkBindable(route);

        Object input = bind(route,
                MockMvcRequestBuilders
                        .get(URI.create("/items/" + ONE + "?tag=a&tag=b"))
                        .header("X-Version", "7")
                        .cookie(new Cookie("since", "2026-11-04")),
                Map.of("id", ONE.toString()));
        assertThat(input)
                .isEqualTo(new SimpleSourcesRequest(ONE, List.of("a", "b"), 7L, LocalDate.of(2026, 11, 4)));
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

    @Test
    void bindsPageQueryComponentOnGetAndPost() {
        RouteDefinition get = route(
                RestBindings.builder().get("/books", new PagedBooksOperation()).sortable(SortKey.class).build());
        RouteDefinition post = route(
                RestBindings.builder().post("/books", new PagedBooksOperation()).sortable(SortKey.class).build());
        binder.checkBindable(get);
        binder.checkBindable(post);

        Object fromGet = bind(get, MockMvcRequestBuilders.get(URI.create("/books?size=5&author=a&sort=-year")),
                Map.of());
        Object fromPost = bind(post,
                json(MockMvcRequestBuilders.post(URI.create("/books?size=5&sort=-year")), "{\"author\":\"a\"}"),
                Map.of());

        PagedBooksRequest expected = new PagedBooksRequest("a",
                new PageQuery(null, null, 5, List.of(new SortOrder("PUBLISHED_YEAR", Direction.DESC))));
        assertThat(fromGet).isEqualTo(expected);
        assertThat(fromPost).isEqualTo(expected);
        assertThat(bind(get, MockMvcRequestBuilders.get(URI.create("/books")), Map.of()))
                .isEqualTo(new PagedBooksRequest(null, PageQuery.first(20)));
    }

    @Test
    void bindsPageQueryAsWholeInput() {
        RouteDefinition get = route(
                RestBindings.builder().get("/books", new SearchAllOperation()).sortable(SortKey.class).build());
        RouteDefinition post = route(RestBindings.builder().post("/books/search", new SearchAllOperation()).build());

        assertThat(bind(get, MockMvcRequestBuilders.get(URI.create("/books?offset=40&size=10&sort=title")), Map.of()))
                .isEqualTo(new PageQuery(null, 40L, 10, List.of(new SortOrder("TITLE", Direction.ASC))));
        assertThat(bind(post, MockMvcRequestBuilders.post(URI.create("/books/search?cursor=c")), Map.of()))
                .isEqualTo(new PageQuery("c", null, 20, List.of()));
        assertViolations(() -> bind(get, MockMvcRequestBuilders.get(URI.create("/books?size=101&sort=price")),
                Map.of()), new Violation("size", "must be less than or equal to 100"),
                new Violation("sort", "has a key that is not allowed (allowed: title, year, authorName)"));
        assertViolations(() -> bind(post, MockMvcRequestBuilders.post(URI.create("/books/search?sort=title")),
                Map.of()), new Violation("sort", "is not supported by this route"));
    }

    @Test
    void pagingViolationsJoinOtherViolations() {
        RouteDefinition limited = route(RestBindings.builder().get("/books", new LimitedPageOperation()).build());

        assertViolations(() -> bind(limited, MockMvcRequestBuilders.get(URI.create("/books?limit=abc&size=0")),
                Map.of()), new Violation("limit", "is not a valid value"),
                new Violation("size", "must be greater than or equal to 1"));
        assertThat(bind(limited, MockMvcRequestBuilders.get(URI.create("/books?limit=3&size=7")), Map.of()))
                .isEqualTo(new LimitedPageRequest(3, PageQuery.first(7)));
    }

    @Test
    void pagingParametersNeverComeFromTheBody() {
        RouteDefinition post = route(RestBindings.builder().post("/books", new PagedBooksOperation()).build());
        RouteDefinition whole = route(RestBindings.builder().post("/books/search", new SearchAllOperation()).build());

        assertThat(bind(post,
                json(MockMvcRequestBuilders.post(URI.create("/books")),
                        "{\"author\":\"a\",\"size\":5,\"offset\":2000,\"cursor\":\"c\"}"),
                Map.of())).isEqualTo(new PagedBooksRequest("a", PageQuery.first(20)));
        assertThat(bind(whole, json(MockMvcRequestBuilders.post(URI.create("/books/search")), "{\"size\":5}"),
                Map.of())).isEqualTo(PageQuery.first(20));
    }

    @Test
    void bodyPropertyNamedLikeThePageQueryComponentIsIgnored() {
        RouteDefinition post = route(RestBindings.builder().post("/books", new PagedBooksOperation()).build());

        assertThat(bind(post, json(MockMvcRequestBuilders.post(URI.create("/books?size=2")),
                "{\"author\":\"a\",\"page\":{\"size\":5}}"), Map.of()))
                .isEqualTo(new PagedBooksRequest("a", PageQuery.first(2)));
        assertThat(bind(post,
                json(MockMvcRequestBuilders.post(URI.create("/books")), "{\"author\":\"a\",\"page\":\"not a page\"}"),
                Map.of())).isEqualTo(new PagedBooksRequest("a", PageQuery.first(20)));
    }

    @Test
    void malformedEscapeInAPagingParameterIsMalformed() {
        RouteDefinition whole = route(RestBindings.builder().get("/books", new SearchAllOperation()).build());
        RouteDefinition component = route(RestBindings.builder().get("/books", new PagedBooksOperation()).build());

        for (RouteDefinition route : List.of(whole, component)) {
            for (String query : List.of("offset=%G1", "offset=1%", "size=%FF", "%6=1")) {
                MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/books");
                servletRequest.setQueryString(query);
                assertError(() -> binder.bind(route, ServerRequest.create(servletRequest, List.of())),
                        SynapseErrors.MALFORMED_REQUEST);
            }
        }
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

    private static RouteDefinition ifMatchRoute() {
        return route(RestBindings
                .builder()
                .put("/books/{id}", new ReviseBookOperation())
                .header("If-Match", ReviseBookRequest::version)
                .build());
    }

    private @Nullable ReviseBookRequest bindIfMatch(RouteDefinition route, String... lines) {
        MockHttpServletRequestBuilder request = json(MockMvcRequestBuilders.put("/books/{id}", ONE),
                "{\"title\":\"T\"}");
        for (String line : lines) {
            request.header("If-Match", line);
        }
        return (ReviseBookRequest) bind(route, request, Map.of("id", ONE.toString()));
    }

    @Test
    void strongTagBindsVersion() {
        assertThat(Objects.requireNonNull(bindIfMatch(ifMatchRoute(), "\"5\"")).version())
                .isEqualTo(VersionCondition.of(5L));
    }

    @Test
    void wildcardAndAbsentBindDifferently() {
        RouteDefinition ifMatch = ifMatchRoute();
        assertThat(Objects.requireNonNull(bindIfMatch(ifMatch, "*")).version()).isSameAs(VersionCondition.any());
        assertThat(Objects.requireNonNull(bindIfMatch(ifMatch)).version()).isNull();

        RouteDefinition createOnly = route(RestBindings
                .builder()
                .put("/books/{id}", new PutBookOperation())
                .header("If-None-Match", PutBookRequest::unless)
                .build());
        assertThat(putBook(createOnly, "*").unless()).isSameAs(VersionCondition.any());
        assertThat(putBook(createOnly, null).unless()).isNull();
        assertThat(putBook(createOnly, "\"7\"").unless()).isEqualTo(VersionCondition.of(7L));
    }

    @Test
    void aRequiredConditionWithoutItsHeaderIs428() {
        RouteDefinition remove = strictRemoveRoute();
        // * counts as present, and one tag binds its version.
        assertThat(strictRemove(remove, "If-Match", "*").version()).isSameAs(VersionCondition.any());
        assertThat(strictRemove(remove, "If-Match", "\"3\"").version()).isEqualTo(VersionCondition.of(3L));
        // Without the header: 428 with the violation that says how to retry, not 400 "must not be null".
        assertPreconditionRequired(() -> strictRemove(remove), new Violation("If-Match", IF_MATCH_REQUIRED));

        RouteDefinition createOnly = strictPutRoute();
        assertThat(strictPut(createOnly, "{\"title\":\"T\"}", "*").unless()).isSameAs(VersionCondition.any());
        assertThat(strictPut(createOnly, "{\"title\":\"T\"}", "\"7\"").unless()).isEqualTo(VersionCondition.of(7L));
        assertPreconditionRequired(() -> strictPut(createOnly, "{\"title\":\"T\"}", null),
                new Violation("If-None-Match", IF_NONE_MATCH_REQUIRED));
    }

    @Test
    void aMissingRequiredConditionComesAfterTheBindingFailuresAndTheUnboundPreconditions() {
        // A body that doesn't bind is 400 first.
        assertViolations(() -> strictPut(strictPutRoute(), "{}", null), new Violation("title", "must not be null"));
        // An If-None-Match the route doesn't bind is 412 before the missing If-Match is 428, as Preconditions orders them.
        assertError(() -> strictRemove(strictRemoveRoute(), "If-None-Match", "\"1\""),
                SynapseErrors.PRECONDITION_FAILED);
    }

    @Test
    void bothRequiredHeadersMissingGetBothViolations() {
        RouteDefinition both = route(RestBindings
                .builder()
                .put("/books/{id}", new StrictBothOperation())
                .header("If-Match", StrictBothRequest::version)
                .header("If-None-Match", StrictBothRequest::unless)
                .build());
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.put("/books/{id}", ONE);

        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> bind(both, request, Map.of("id", ONE.toString())))
                .satisfies(e -> {
                    assertThat(e.code()).isEqualTo("PRECONDITION_REQUIRED");
                    assertThat(e.violations())
                            .containsExactly(new Violation("If-Match", IF_MATCH_REQUIRED),
                                    new Violation("If-None-Match", IF_NONE_MATCH_REQUIRED));
                });
    }

    @Test
    void aRecordReadWholeStillGets428ForAMissingRequiredCondition() {
        RouteDefinition whole = route(RestBindings
                .builder()
                .put("/books/{id}", new WholeRevisionOperation())
                .header("If-Match", WholeRevisionRequest::version)
                .build());
        Map<String, String> id = Map.of("id", ONE.toString());

        assertThat(bind(whole, json(MockMvcRequestBuilders.put("/books/{id}", ONE), "{\"title\":\"T\"}")
                .header("If-Match", "\"4\""), id))
                .isEqualTo(new WholeRevisionRequest(ONE, VersionCondition.of(4L), "T"));
        assertPreconditionRequired(
                () -> bind(whole, json(MockMvcRequestBuilders.put("/books/{id}", ONE), "{\"title\":\"T\"}"), id),
                new Violation("If-Match", IF_MATCH_REQUIRED));

        // Jackson builds a record it reads whole before the other sources are read, so a constructor that rejects
        // the null version fails it as 400, with the header or without (documented in rest.adoc).
        RouteDefinition guarded = route(RestBindings
                .builder()
                .put("/books/{id}", new GuardedRevisionOperation())
                .header("If-Match", GuardedRevisionRequest::version)
                .build());
        assertError(() -> bind(guarded, json(MockMvcRequestBuilders.put("/books/{id}", ONE), "{\"title\":\"T\"}")
                .header("If-Match", "\"4\""), id), SynapseErrors.MALFORMED_REQUEST);
        assertError(
                () -> bind(guarded, json(MockMvcRequestBuilders.put("/books/{id}", ONE), "{\"title\":\"T\"}"), id),
                SynapseErrors.MALFORMED_REQUEST);
    }

    @Test
    void aNullableConditionWithoutItsHeaderBindsNull() {
        assertThat(Objects.requireNonNull(bindIfMatch(ifMatchRoute())).version()).isNull();
    }

    private static final String IF_MATCH_REQUIRED = "is required; send the ETag from a GET of this resource";

    private static final String IF_NONE_MATCH_REQUIRED = "is required; send * to write only if the resource doesn't "
            + "exist, or the ETag the resource must not have";

    private static RouteDefinition strictRemoveRoute() {
        return route(RestBindings
                .builder()
                .delete("/books/{id}", new StrictConditionOperation())
                .header("If-Match", StrictConditionRequest::version)
                .build());
    }

    private static RouteDefinition strictPutRoute() {
        return route(RestBindings
                .builder()
                .put("/books/{id}", new StrictPutBookOperation())
                .header("If-None-Match", StrictPutBookRequest::unless)
                .build());
    }

    private StrictConditionRequest strictRemove(RouteDefinition route, String... header) {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.delete("/books/{id}", ONE);
        for (int i = 0; i < header.length; i += 2) {
            request.header(header[i], header[i + 1]);
        }
        return Objects.requireNonNull((StrictConditionRequest) bind(route, request, Map.of("id", ONE.toString())));
    }

    private StrictPutBookRequest strictPut(RouteDefinition route, String body, @Nullable String ifNoneMatch) {
        MockHttpServletRequestBuilder request = json(MockMvcRequestBuilders.put("/books/{id}", ONE), body);
        if (ifNoneMatch != null) {
            request.header("If-None-Match", ifNoneMatch);
        }
        return Objects.requireNonNull((StrictPutBookRequest) bind(route, request, Map.of("id", ONE.toString())));
    }

    private static void assertPreconditionRequired(Runnable call, Violation violation) {
        assertThatExceptionOfType(SynapseException.class).isThrownBy(call::run).satisfies(e -> {
            assertThat(e.code()).isEqualTo("PRECONDITION_REQUIRED");
            assertThat(e.details()).isInstanceOf(SynapseErrors.class);
            assertThat(e.violations()).containsExactly(violation);
        });
    }

    @Test
    void weakTagBindsItsValue() {
        assertThat(Objects.requireNonNull(bindIfMatch(ifMatchRoute(), "W/\"5\"")).version())
                .isEqualTo(VersionCondition.of(5L));
    }

    @Test
    void listMalformedAndUnconvertibleKeepTheirViolations() {
        RouteDefinition route = ifMatchRoute();
        Violation single = new Violation("version", "must be a single entity tag");
        assertViolations(() -> bindIfMatch(route, "\"5\", \"6\""), single);
        assertViolations(() -> bindIfMatch(route, "\"5\"", "\"6\""), single);
        for (String bad : List.of("5", "\"5", "*, \"5\"", "\"5\",,", "", "\"5\" \"6\"")) {
            assertViolations(() -> bindIfMatch(route, bad), new Violation("version", "must be an entity tag"));
        }
        assertViolations(() -> bindIfMatch(route, "\"abc\""), new Violation("version", "is not a valid value"));
        // An empty tag converts to no number, so it names no version.
        assertViolations(() -> bindIfMatch(route, "\"\""), new Violation("version", "is not a valid value"));
    }

    @Test
    void readRouteStillBindsPlainTypes() {
        RouteDefinition route = route(RestBindings
                .builder()
                .get("/books/{id}", new GetTaggedBookOperation())
                .header("If-None-Match", TaggedReadRequest::tag)
                .build());
        assertThat(taggedRead(route, "\"x\"").tag()).isEqualTo("x");
        assertThat(taggedRead(route, "*").tag()).isNull();
        assertThat(taggedRead(route, "W/\"x\"").tag()).isEqualTo("x");
        assertViolations(() -> taggedRead(route, "x"), new Violation("tag", "must be an entity tag"));
    }

    @Test
    void readRouteMayBindAVersionCondition() {
        RouteDefinition route = route(RestBindings
                .builder()
                .get("/books/{id}", new GetConditionalBookOperation())
                .header("If-None-Match", ConditionalReadRequest::version)
                .build());
        binder.checkBindable(route);
        assertThat(conditionalRead(route, "\"3\"").version()).isEqualTo(VersionCondition.of(3L));
        assertThat(conditionalRead(route, "*").version()).isSameAs(VersionCondition.any());
        assertThat(conditionalRead(route, null).version()).isNull();
    }

    private ConditionalReadRequest conditionalRead(RouteDefinition route, @Nullable String ifNoneMatch) {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.get("/books/{id}", ONE);
        if (ifNoneMatch != null) {
            request.header("If-None-Match", ifNoneMatch);
        }
        return Objects.requireNonNull((ConditionalReadRequest) bind(route, request, Map.of("id", ONE.toString())));
    }

    @Test
    void versionTypeMustConvertFromAString() {
        assertNotConvertible(
                RestBindings
                        .builder()
                        .delete("/books/{id}", new LooseConditionOperation())
                        .header("If-Match", LooseConditionRequest::pair)
                        .build(),
                "LooseConditionRequest.pair (type io.americanexpress.synapse.core.conditional.VersionCondition<");
    }

    @Test
    void bodyVersionIsComparedWithTheTagByValue() {
        RouteDefinition route = ifMatchRoute();
        assertThat(Objects
                .requireNonNull(bindIfMatchWithBody(route, "{\"title\":\"T\",\"version\":5}", "\"5\""))
                .version()).isEqualTo(VersionCondition.of(5L));
        Violation conflict = new Violation("version", "conflicts with the value from the header");
        assertViolations(() -> bindIfMatchWithBody(route, "{\"title\":\"T\",\"version\":6}", "\"5\""), conflict);
        assertViolations(() -> bindIfMatchWithBody(route, "{\"title\":\"T\",\"version\":\"x\"}", "\"5\""), conflict);
        // As for a plain type, * and an absent header carry no value, so a body version conflicts with them...
        assertViolations(() -> bindIfMatchWithBody(route, "{\"title\":\"T\",\"version\":5}", "*"), conflict);
        assertViolations(() -> bindIfMatchWithBody(route, "{\"title\":\"T\",\"version\":5}", null), conflict);
        // ...and a null one doesn't.
        assertThat(Objects
                .requireNonNull(bindIfMatchWithBody(route, "{\"title\":\"T\",\"version\":null}", "*"))
                .version()).isSameAs(VersionCondition.any());
        assertThat(Objects
                .requireNonNull(bindIfMatchWithBody(route, "{\"title\":\"T\",\"version\":null}", null))
                .version()).isNull();
    }

    private @Nullable ReviseBookRequest bindIfMatchWithBody(RouteDefinition route, String body,
            @Nullable String ifMatch) {
        MockHttpServletRequestBuilder request = json(MockMvcRequestBuilders.put("/books/{id}", ONE), body);
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return (ReviseBookRequest) bind(route, request, Map.of("id", ONE.toString()));
    }

    @Test
    void otherHeadersAreNotParsedAsTags() {
        RouteDefinition route = route(RestBindings
                .builder()
                .put("/books/{id}", new TaggedBookOperation())
                .header("X-Version", TaggedBookRequest::other)
                .build());
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders
                .put("/books/{id}", ONE)
                .header("X-Version", "\"5\"");
        TaggedBookRequest input = (TaggedBookRequest) bind(route, request, Map.of("id", ONE.toString()));
        assertThat(Objects.requireNonNull(input).other()).isEqualTo("\"5\"");
    }

    private PutBookRequest putBook(RouteDefinition route, @Nullable String ifNoneMatch) {
        MockHttpServletRequestBuilder request = json(MockMvcRequestBuilders.put("/books/{id}", ONE),
                "{\"title\":\"T\"}");
        if (ifNoneMatch != null) {
            request.header("If-None-Match", ifNoneMatch);
        }
        return Objects.requireNonNull((PutBookRequest) bind(route, request, Map.of("id", ONE.toString())));
    }

    private TaggedReadRequest taggedRead(RouteDefinition route, String value) {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders
                .get("/books/{id}", ONE)
                .header("If-None-Match", value);
        return Objects.requireNonNull((TaggedReadRequest) bind(route, request, Map.of("id", ONE.toString())));
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
        return new BindingValidator(PagingSettings.defaults())
                .validate(bindings.routes(), List.of(conventions))
                .getFirst();
    }

    private void assertNotConvertible(RestBindings bindings, String description) {
        assertNotConvertible(bindings, List.of(), description);
    }

    private void assertNotConvertible(RestBindings bindings, List<RestConventions> conventions, String description) {
        RouteDefinition route = route(bindings, conventions.toArray(RestConventions[]::new));
        assertThatExceptionOfType(SynapseConfigurationException.class)
                .isThrownBy(() -> binder.checkBindable(route))
                .satisfies(e -> {
                    assertThat(e.description()).contains(description).endsWith("can't be converted from a string");
                    assertThat(e.action()).contains("String", "Converter");
                });
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
