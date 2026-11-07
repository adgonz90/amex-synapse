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
package io.americanexpress.synapse.api.rest.internal.conditional;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.ServerRequest;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.internal.binding.BindingValidator;
import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.CreateBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.GetVersionedBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.RemoveBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.RemoveBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.ReviseBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.ReviseBookRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.StockBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.StrictBothOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.StrictBothRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.StrictConditionOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.StrictConditionRequest;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.StrictPutBookOperation;
import io.americanexpress.synapse.api.rest.internal.binding.fixture.BindingFixtures.StrictPutBookRequest;
import io.americanexpress.synapse.api.rest.internal.paging.PagingSettings;
import io.americanexpress.synapse.core.error.ErrorCategory;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class PreconditionsTest {

    /** A PUT that binds {@code If-Match} to its version. */
    private static final RouteDefinition REVISE = route(RestBindings
            .builder()
            .put("/books/{id}", new ReviseBookOperation())
            .header("If-Match", ReviseBookRequest::version)
            .build());

    /** A DELETE that binds {@code If-Match} to a non-null {@code VersionCondition}, so it requires it. */
    private static final RouteDefinition REMOVE = route(RestBindings
            .builder()
            .delete("/books/{id}", new StrictConditionOperation())
            .header("If-Match", StrictConditionRequest::version)
            .build());

    /** The same DELETE with a {@code @Nullable} condition: {@code If-Match} is optional. */
    private static final RouteDefinition REMOVE_OPTIONAL = route(RestBindings
            .builder()
            .delete("/books/{id}", new RemoveBookOperation())
            .header("If-Match", RemoveBookRequest::version)
            .build());

    /** A PUT that binds {@code If-None-Match} to a non-null {@code VersionCondition}, so it requires it. */
    private static final RouteDefinition CREATE_ONLY = route(RestBindings
            .builder()
            .put("/books/{id}", new StrictPutBookOperation())
            .header("If-None-Match", StrictPutBookRequest::unless)
            .build());

    /** A PUT whose input requires both precondition headers. */
    private static final RouteDefinition BOTH = route(RestBindings
            .builder()
            .put("/books/{id}", new StrictBothOperation())
            .header("If-Match", StrictBothRequest::version)
            .header("If-None-Match", StrictBothRequest::unless)
            .build());

    /** A PUT that binds neither precondition header. */
    private static final RouteDefinition STOCK = route(
            RestBindings.builder().put("/stock", new StockBookOperation()).build());

    private static final RouteDefinition CREATE = route(
            RestBindings.builder().post("/books", new CreateBookOperation()).build());

    private static final RouteDefinition GET = route(
            RestBindings.builder().get("/books/{id}", new GetVersionedBookOperation()).build());

    /** Another conflict, which a request with {@code If-Match} must still see as 409. */
    enum CatalogErrors implements ErrorDetails {
        DUPLICATE_ISBN;

        @Override
        public String code() {
            return name();
        }

        @Override
        public String messageTemplate() {
            return "The ISBN is already in the catalog";
        }

        @Override
        public ErrorCategory category() {
            return ErrorCategory.CONFLICT;
        }
    }

    @Test
    void unboundIfMatchOrIfNoneMatchOnUnsafeIs412() {
        assertPreconditionFailed(STOCK, request("PUT", "If-Match", "\"1\""));
        assertPreconditionFailed(STOCK, request("PUT", "If-None-Match", "*"));
        assertPreconditionFailed(CREATE, request("POST", "If-None-Match", "\"1\""));
        // Present but empty still names a precondition the route can't evaluate.
        assertPreconditionFailed(STOCK, request("PUT", "If-Match", ""));
        // The route binds If-Match only: If-None-Match is still unbound.
        assertPreconditionFailed(REVISE, request("PUT", "If-None-Match", "\"1\""));
        // The unbound header is judged before the missing required If-Match.
        assertPreconditionFailed(REMOVE, request("DELETE", "If-None-Match", "*"));
        assertPreconditionFailed(CREATE_ONLY, request("PUT", "If-Match", "\"1\""));
    }

    @Test
    void everyMethodButGetAndHeadIsChecked() {
        // RestBindings has no PATCH today; a route on any method other than GET or HEAD must still be checked.
        for (HttpMethod method : List.of(HttpMethod.PATCH, HttpMethod.valueOf("PURGE"))) {
            RouteDefinition unsafe = new RouteDefinition(method, STOCK.path(), STOCK.operation(),
                    STOCK.operationName(), STOCK.inputType(), STOCK.outputType(), STOCK.sources(), STOCK.response(),
                    STOCK.declarations(), STOCK.paging(), STOCK.conditional(), STOCK.version());

            assertPreconditionFailed(unsafe, request(method.name(), "If-Match", "\"1\""));
            assertPreconditionFailed(unsafe, request(method.name(), "If-None-Match", "*"));
        }
    }

    @Test
    void unboundIfMatchOnGetIsIgnored() {
        assertPasses(GET, request("GET", "If-Match", "\"1\""));
        assertPasses(GET, request("HEAD", "If-Match", "W/\"1\""));
        assertPasses(GET, request("GET", "If-None-Match", "\"1\""));
    }

    @Test
    void weakBoundIfMatchIs412() {
        assertPreconditionFailed(REVISE, request("PUT", "If-Match", "W/\"5\""));
        assertPreconditionFailed(REMOVE, request("DELETE", "If-Match", "W/\"5\""));
        assertPasses(REVISE, request("PUT", "If-Match", "\"5\""));
        assertPasses(REVISE, request("PUT", "If-Match", "*"));
        assertPasses(REVISE, request("PUT"));
        // Binding answers a list or a malformed header 400 first; the check still fails closed on its own.
        assertPreconditionFailed(REVISE, request("PUT", "If-Match", "W/\"5\", W/\"6\""));
        assertPreconditionFailed(REVISE, request("PUT", "If-Match", "\"5\", W/\"6\""));
        assertPreconditionFailed(REVISE, request("PUT", "If-Match", "W/5"));
        assertPreconditionFailed(REVISE, request("PUT", "If-Match", "5"));
    }

    @Test
    void aRequiredConditionWithoutItsHeaderIs428() {
        assertPreconditionRequired(REMOVE, request("DELETE"),
                new Violation("If-Match", "is required; send the ETag from a GET of this resource"));
        assertPasses(REMOVE, request("DELETE", "If-Match", "*"));
        assertPasses(REMOVE, request("DELETE", "If-Match", "\"1\""));

        assertPreconditionRequired(CREATE_ONLY, request("PUT"), new Violation("If-None-Match",
                "is required; send * to write only if the resource doesn't exist, or the ETag the resource must not "
                        + "have"));
        assertPasses(CREATE_ONLY, request("PUT", "If-None-Match", "*"));
        assertPasses(CREATE_ONLY, request("PUT", "If-None-Match", "\"1\""));
    }

    @Test
    void aRouteThatRequiresBothHeadersGetsAViolationForEachMissingOne() {
        Violation ifMatch = new Violation("If-Match", "is required; send the ETag from a GET of this resource");
        Violation ifNoneMatch = new Violation("If-None-Match",
                "is required; send * to write only if the resource doesn't exist, or the ETag the resource must not "
                        + "have");
        assertPreconditionRequired(BOTH, request("PUT"), ifMatch, ifNoneMatch);
        assertPreconditionRequired(BOTH, request("PUT", "If-Match", "\"1\""), ifNoneMatch);
        assertPreconditionRequired(BOTH, request("PUT", "If-None-Match", "*"), ifMatch);
        assertPasses(BOTH, request("PUT", "If-Match", "\"1\"", "If-None-Match", "\"2\""));
    }

    @Test
    void missingPreconditionAnswersInCheckOrder() {
        // What binding throws when the input can't be built: the 428, unless an unverifiable header comes first.
        SynapseException required = Preconditions.missingPrecondition(BOTH, request("PUT"));
        assertThat(required.code()).isEqualTo("PRECONDITION_REQUIRED");
        assertThat(required.violations()).hasSize(2);
        SynapseException weak = Preconditions.missingPrecondition(REMOVE, request("DELETE", "If-None-Match", "*"));
        assertThat(weak.details()).isEqualTo(SynapseErrors.PRECONDITION_FAILED);
        assertThat(weak.violations()).isEmpty();
    }

    @Test
    void aNullableConditionWithoutItsHeaderPasses() {
        assertPasses(REMOVE_OPTIONAL, request("DELETE"));
        assertPasses(REVISE, request("PUT"));
    }

    private static void assertPreconditionRequired(RouteDefinition route, ServerRequest request,
            Violation... violations) {
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> Preconditions.check(route, request))
                .satisfies(e -> {
                    assertThat(e.code()).isEqualTo("PRECONDITION_REQUIRED");
                    // Core's code, which every protocol can raise; REST answers it 428.
                    assertThat(e.details()).isInstanceOf(SynapseErrors.class);
                    assertThat(e.violations()).containsExactly(violations);
                });
    }

    @Test
    void staleVersionWithIfMatchBecomesPreconditionFailed() {
        for (String ifMatch : List.of("\"5\"", "*")) {
            SynapseException stale = new SynapseException(SynapseErrors.STALE_VERSION);

            SynapseException translated = Preconditions.translate(REVISE, request("PUT", "If-Match", ifMatch), stale);

            assertThat(translated.details()).as(ifMatch).isEqualTo(SynapseErrors.PRECONDITION_FAILED);
            assertThat(translated.getCause()).as(ifMatch).isSameAs(stale);
        }
        SynapseException stale = new SynapseException(SynapseErrors.STALE_VERSION);
        assertThat(Preconditions.translate(REVISE, request("PUT"), stale)).isSameAs(stale);
        // A route that doesn't bind If-Match never translates.
        assertThat(Preconditions.translate(STOCK, request("PUT", "If-Match", "\"5\""), stale)).isSameAs(stale);
    }

    @Test
    void otherConflictsAreNotTranslated() {
        for (ErrorDetails conflict : List
                .<ErrorDetails>of(CatalogErrors.DUPLICATE_ISBN, SynapseErrors.CONFLICT,
                        SynapseErrors.NOT_FOUND)) {
            SynapseException failure = new SynapseException(conflict);

            assertThat(Preconditions.translate(REVISE, request("PUT", "If-Match", "\"5\""), failure))
                    .as(conflict.code())
                    .isSameAs(failure);
        }
    }

    private static void assertPreconditionFailed(RouteDefinition route, ServerRequest request) {
        assertThatExceptionOfType(SynapseException.class)
                .isThrownBy(() -> Preconditions.check(route, request))
                .satisfies(e -> {
                    assertThat(e.details()).isEqualTo(SynapseErrors.PRECONDITION_FAILED);
                    assertThat(e.violations()).isEmpty();
                });
    }

    private static void assertPasses(RouteDefinition route, ServerRequest request) {
        assertThatCode(() -> Preconditions.check(route, request)).doesNotThrowAnyException();
    }

    private static ServerRequest request(String method, String... header) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/books/1");
        for (int i = 0; i < header.length; i += 2) {
            request.addHeader(header[i], header[i + 1]);
        }
        return ServerRequest.create(request, List.of());
    }

    private static RouteDefinition route(RestBindings bindings) {
        return new BindingValidator(PagingSettings.defaults()).validate(bindings.routes(), List.of()).getFirst();
    }
}
