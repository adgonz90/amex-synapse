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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.internal.error.BookErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.operation.CommandOperation;
import io.americanexpress.synapse.core.operation.QueryOperation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Serves Synapse routes next to an escape-hatch controller on the same path, and a Synapse template that also matches
 * the error path, on a real server.
 */
@SpringBootTest(classes = EscapeHatchRoutesIT.TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.logging.export.otlp.enabled=false", "management.tracing.export.otlp.enabled=false",
        "management.otlp.metrics.export.enabled=false"})
@AutoConfigureRestTestClient
@ActiveProfiles("test")
class EscapeHatchRoutesIT {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The header {@link CatchAllAdvice} marks its answers with. */
    private static final String ADVICE_HEADER = "X-Advice";

    @Autowired
    private RestTestClient client;

    @Autowired
    private Calls calls;

    /** A response: its status, content type, {@code Allow} header, body and headers. */
    record Result(int status, String contentType, @Nullable String allow, String body, HttpHeaders headers) {

        JsonNode json() {
            return MAPPER.readTree(body);
        }

        /** The marker header of {@link CatchAllAdvice}'s answers, or {@code null} when another resolver answered. */
        @Nullable
        String advice() {
            return headers.getFirst(ADVICE_HEADER);
        }
    }

    static Result call(RestTestClient.RequestHeadersSpec<?> spec) {
        var result = spec.exchange().returnResult(String.class);
        return new Result(result.getStatus().value(), String.valueOf(result.getResponseHeaders().getContentType()),
                result.getResponseHeaders().getFirst("Allow"), String.valueOf(result.getResponseBody()),
                result.getResponseHeaders());
    }

    @Test
    void routeNotAcceptableListsWhatItProduces() {
        Result result = call(client.get().uri("/things/7").accept(MediaType.APPLICATION_XML));

        assertThat(result.status()).isEqualTo(406);
        assertThat(result.json().get("code").asString()).isEqualTo("NOT_ACCEPTABLE");
        assertThat(result.headers().getFirst(HttpHeaders.ACCEPT)).isEqualTo("application/json");
    }

    @Test
    @Tag("smoke")
    void controllerMethodOnASynapsePathReachesTheController() {
        int before = calls.things.get();

        Result patched = call(client.patch().uri("/things/7"));

        assertThat(patched.status()).isEqualTo(200);
        assertThat(patched.body()).isEqualTo("patched 7");
        assertThat(calls.things.get()).isEqualTo(before);

        Result got = call(client.get().uri("/things/7"));
        assertThat(got.status()).isEqualTo(200);
        assertThat(got.json().get("id").asString()).isEqualTo("7");
        assertThat(calls.things.get()).isEqualTo(before + 1);
    }

    @Test
    void methodNeitherServesIsStill405() {
        Result result = call(client.post().uri("/things/7").contentType(MediaType.APPLICATION_JSON).body("{}"));

        assertThat(result.status()).isEqualTo(405);
        assertThat(result.contentType()).startsWith("application/problem+json");
        assertThat(result.json().get("code").asString()).isEqualTo("METHOD_NOT_ALLOWED");
        // The path matches a controller mapping, so Spring MVC answers 405 and OPTIONS from the controller's methods.
        assertThat(result.allow()).isEqualTo("PATCH");
        Result options = call(client.options().uri("/things/7"));
        assertThat(options.status()).isEqualTo(200);
        assertThat(options.allow()).isEqualTo("PATCH,OPTIONS");
    }

    @Test
    void synapseFallbackStillAnswersWhereNoControllerMatches() {
        Result result = call(client.delete().uri("/widgets/7"));

        assertThat(result.status()).isEqualTo(405);
        assertThat(result.json().get("code").asString()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(result.allow()).isEqualTo("GET, HEAD, OPTIONS");

        Result options = call(client.options().uri("/widgets/7"));
        assertThat(options.status()).isEqualTo(200);
        assertThat(options.allow()).isEqualTo("GET, HEAD, OPTIONS");
    }

    @Test
    void filterFailureIsAnsweredByTheErrorControllerNotASynapseRoute() {
        int before = calls.slugs.get();

        Result result = call(client.get().uri("/filter-fail"));

        assertThat(result.status()).isEqualTo(500);
        assertThat(result.contentType()).startsWith("application/problem+json");
        assertThat(result.json().get("code").asString()).isEqualTo("INTERNAL");
        assertThat(result.body()).doesNotContain("filter failed");
        assertThat(calls.slugs.get()).isEqualTo(before);
    }

    @Test
    void sendErrorIsAnsweredByTheErrorControllerNotASynapseRoute() {
        int before = calls.slugs.get();

        // Boot's FormContentFilter reads the PUT form body before Spring MVC; the size limit filter answers with
        // sendError(413), which the container turns into an error dispatch to /error.
        Result result = call(client
                .put()
                .uri("/anything")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("data=" + "x".repeat(1_100_000)));

        assertThat(result.status()).isEqualTo(413);
        assertThat(result.json().get("code").asString()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(calls.slugs.get()).isEqualTo(before);
    }

    @Test
    void requestToASlugStillReachesTheSynapseRoute() {
        int before = calls.slugs.get();

        Result result = call(client.get().uri("/hello"));

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.json().get("slug").asString()).isEqualTo("hello");
        assertThat(calls.slugs.get()).isEqualTo(before + 1);
    }

    @Test
    void controllerCallOnAnotherThreadIsValidationFailed() {
        int before = calls.renames.get();

        Result result = call(client.patch().uri("/things/7/name?name="));

        // The controller is not an operation call: the rejection it rethrows is the operation's own, of its input.
        assertThat(result.status()).isEqualTo(400);
        assertThat(result.contentType()).startsWith("application/problem+json");
        assertThat(result.json().get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(result.json().get("violations")).hasSize(1);
        assertThat(result.json().get("violations").get(0).get("field").asString()).isEqualTo("name");
        assertThat(calls.renames.get()).isEqualTo(before);

        Result renamed = call(client.patch().uri("/things/7/name?name=Dune"));
        assertThat(renamed.status()).isEqualTo(200);
        assertThat(renamed.body()).isEqualTo("renamed 7 to Dune");
        assertThat(calls.renames.get()).isEqualTo(before + 1);
    }

    @Test
    void unprocessableKeeps422WithClientError() {
        Result result = call(client.get().uri("/statuses/unprocessable"));

        assertThat(result.status()).isEqualTo(422);
        assertThat(result.contentType()).startsWith("application/problem+json");
        assertThat(result.json().get("status").asInt()).isEqualTo(422);
        assertThat(result.json().get("code").asString()).isEqualTo("CLIENT_ERROR");
        assertThat(result.json().get("correlationId").asString()).isNotEmpty();
        assertThat(result.body()).doesNotContain("secret-reason");
    }

    @Test
    void goneKeeps410() {
        Result result = call(client.get().uri("/statuses/gone"));

        assertThat(result.status()).isEqualTo(410);
        assertThat(result.json().get("code").asString()).isEqualTo("CLIENT_ERROR");
    }

    @Test
    void badGatewayKeeps502WithServerErrorAtWarn() {
        List<ILoggingEvent> events = new ArrayList<>();
        Result result = logged(events, () -> call(client.get().uri("/statuses/bad-gateway")));

        assertThat(result.status()).isEqualTo(502);
        assertThat(result.json().get("code").asString()).isEqualTo("SERVER_ERROR");
        assertThat(result.body()).doesNotContain("secret-upstream");
        assertThat(events)
                .filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getThrowableProxy()).isNull();
                });
    }

    @Test
    void responseStatusConflictIs409Conflict() {
        Result result = call(client.get().uri("/statuses/taken"));

        assertThat(result.status()).isEqualTo(409);
        assertThat(result.json().get("code").asString()).isEqualTo("CONFLICT");
    }

    @Test
    void responseStatusOnACauseIsFound() {
        Result result = call(client.get().uri("/statuses/wrapped"));

        assertThat(result.status()).isEqualTo(410);
        assertThat(result.json().get("code").asString()).isEqualTo("CLIENT_ERROR");
    }

    @Test
    void headersAreCopied() {
        Result result = call(client.get().uri("/statuses/busy"));

        assertThat(result.status()).isEqualTo(503);
        assertThat(result.json().get("code").asString()).isEqualTo("UNAVAILABLE");
        assertThat(result.headers().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
    }

    @Test
    void reasonIsNotTheDetailWithoutIncludeDetail() {
        // Pin (R-11): without include-detail no detail was ever written; the kept reason does not change that.
        Result result = call(client.get().uri("/statuses/quota"));

        assertThat(result.status()).isEqualTo(429);
        assertThat(result.json().get("code").asString()).isEqualTo("RATE_LIMITED");
        assertThat(result.json().has("detail")).isFalse();
        assertThat(result.body()).doesNotContain("quota");
    }

    /** {@code synapse.errors.include-detail=true}, in a context of its own: a kept reason is the problem's detail. */
    @Nested
    @TestPropertySource(properties = "synapse.errors.include-detail=true")
    class IncludeDetail {

        @Autowired
        private RestTestClient nestedClient;

        @Test
        void reasonIsDetailOnlyWithIncludeDetail() {
            // Without include-detail the default context writes no detail (reasonIsNotTheDetailWithoutIncludeDetail).
            Result result = call(nestedClient.get().uri("/statuses/quota"));

            assertThat(result.status()).isEqualTo(429);
            assertThat(result.json().get("code").asString()).isEqualTo("RATE_LIMITED");
            assertThat(result.json().get("detail").asString()).isEqualTo("quota");

            Result annotated = call(nestedClient.get().uri("/statuses/taken"));
            assertThat(annotated.json().get("detail").asString()).isEqualTo("name taken");
        }
    }

    /** Runs the call and adds the events logged on the REST logger meanwhile, DEBUG and above, to {@code events}. */
    private static Result logged(List<ILoggingEvent> events, Supplier<Result> call) {
        Logger logger = (Logger) LoggerFactory.getLogger("io.americanexpress.synapse.api.rest");
        Level level = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            return call.get();
        } finally {
            logger.setLevel(level);
            logger.detachAppender(appender);
            events.addAll(appender.list);
        }
    }

    /**
     * The application's mapper names properties in snake case, and a controller validates its
     * {@code @RequestBody}: the violations use the JSON names, in a context of their own.
     */
    @Nested
    @TestPropertySource(properties = "spring.jackson.property-naming-strategy=SNAKE_CASE")
    @Import(EditionsController.class)
    class SnakeCase {

        @Autowired
        private RestTestClient nestedClient;

        @Test
        void validBodyViolationUsesTheJsonName() {
            Result result = call(nestedClient
                    .post()
                    .uri("/editions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"published_year\":\"20266\",\"author\":{\"country_code\":\"\"}}"));

            assertThat(result.status()).isEqualTo(400);
            assertThat(result.json().get("code").asString()).isEqualTo("VALIDATION_FAILED");
            assertThat(result.json().get("violations").findValuesAsString("field"))
                    .containsExactlyInAnyOrder("published_year", "author.country_code");
        }
    }

    /**
     * The application's {@code @ControllerAdvice} and a controller's own {@code @ExceptionHandler} answer first for its
     * controllers, in a context of their own.
     */
    @Nested
    @Import({IllegalStateAdvice.class, GadgetsController.class})
    class Advice {

        @Autowired
        private RestTestClient nestedClient;

        @Test
        void controllerAdviceAnswersFirst() {
            Result result = call(nestedClient.get().uri("/gadgets/broken"));

            assertThat(result.status()).isEqualTo(418);
            assertThat(result.body()).isEqualTo("advised");
        }

        @Test
        void localExceptionHandlerAnswersFirst() {
            Result result = call(nestedClient.get().uri("/gadgets/7/parts"));

            assertThat(result.status()).isEqualTo(409);
            assertThat(result.body()).isEqualTo("handled locally");
        }

        /** An exception no advice handles is still Synapse's problem. */
        @Test
        void unhandledControllerExceptionIsSynapsesProblem() {
            Result result = call(nestedClient.get().uri("/gadgets/7/wheels"));

            assertThat(result.status()).isEqualTo(500);
            assertThat(result.contentType()).startsWith("application/problem+json");
            assertThat(result.json().get("code").asString()).isEqualTo("INTERNAL");
            assertThat(result.body()).doesNotContain("no wheels");
        }
    }

    /**
     * A catch-all {@code @ControllerAdvice} that extends Spring's {@code ResponseEntityExceptionHandler}, next to Synapse
     * routes and a controller that shares a Synapse path, in a context of its own.
     */
    @Nested
    @Import({CatchAllAdvice.class, BookRoutes.class, BooksController.class})
    class CatchAll {

        @Autowired
        private RestTestClient nestedClient;

        @Test
        void synapseRouteKeepsSynapsesProblemWithACatchAllAdvice() {
            // Pin (R-11): the Synapse-paths instance answers a Synapse route's failure before the advice resolver runs.
            Result result = call(nestedClient.get().uri("/books/7"));

            assertThat(result.status()).isEqualTo(404);
            assertThat(result.contentType()).startsWith("application/problem+json");
            assertThat(result.json().get("code").asString()).isEqualTo("BOOK_NOT_FOUND");
            assertThat(result.json().has("correlationId")).isTrue();
            assertThat(result.advice()).isNull();
        }

        /**
         * {@code GET /books} is a Synapse route and {@code POST /books} a controller's JSON mapping: a text body fails
         * the controller's mapping lookup (415) before a handler is found, so the error is Synapse's (review n1).
         */
        @Test
        void preHandlerErrorOnASharedPathIsSynapsesProblem() {
            // Pin (R-11): Synapse answered every exception before this change; the Synapse-paths instance keeps it.
            Result result = call(nestedClient.post().uri("/books").contentType(MediaType.TEXT_PLAIN).body("Dune"));

            assertThat(result.status()).isEqualTo(415);
            assertThat(result.contentType()).startsWith("application/problem+json");
            assertThat(result.json().get("code").asString()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
            // Spring's 415 from the controller's mapping lookup keeps its Accept: the controller's consumes.
            assertThat(result.headers().getFirst(HttpHeaders.ACCEPT)).isEqualTo("application/json");
            assertThat(result.json().has("correlationId")).isTrue();
            assertThat(result.advice()).isNull();

            Result created = call(
                    nestedClient.post().uri("/books").contentType(MediaType.APPLICATION_JSON).body("\"Dune\""));
            assertThat(created.status()).isEqualTo(200);
            assertThat(created.body()).isEqualTo("created");
            Result listed = call(nestedClient.get().uri("/books"));
            assertThat(listed.status()).isEqualTo(200);
            assertThat(listed.json().get("titles").get(0).asString()).isEqualTo("Dune");
        }

        /** The same 415 on a controller's own path goes to the advice. */
        @Test
        void preHandlerErrorOnAControllerOnlyPathGoesToTheAdvice() {
            Result result = call(
                    nestedClient.post().uri("/catalog/books").contentType(MediaType.TEXT_PLAIN).body("Dune"));

            assertThat(result.status()).isEqualTo(415);
            assertThat(result.advice()).isEqualTo("catch-all");
        }
    }

    /**
     * {@code spring.mvc.apiversion.required=true} with the catch-all advice, in a context of its own: the version then
     * applies to every route and controller.
     */
    @Nested
    @TestPropertySource(properties = "spring.mvc.apiversion.required=true")
    @Import({CatchAllAdvice.class, GizmoRoutes.class, GadgetsController.class})
    class RequiredVersion {

        @Autowired
        private RestTestClient nestedClient;

        @Test
        void preHandlerErrorOnASynapsePathIsSynapsesProblem() {
            // Pin (R-11) for the 415: a route's own media type check runs in its handler, before and after this change.
            Result missing = call(nestedClient.get().uri("/things/7"));

            assertThat(missing.status()).isEqualTo(400);
            assertThat(missing.contentType()).startsWith("application/problem+json");
            assertThat(missing.json().get("code").asString()).isEqualTo("MISSING_API_VERSION");
            assertThat(missing.json().get("correlationId").asString()).isNotEmpty();
            assertThat(missing.advice()).isNull();

            Result unsupported = call(
                    nestedClient.put().uri("/v1/gizmos/7").contentType(MediaType.TEXT_PLAIN).body("name=Dial"));
            assertThat(unsupported.status()).isEqualTo(415);
            assertThat(unsupported.json().get("code").asString()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
            // The route lists the body types it reads, as a controller's 415 lists its consumes.
            assertThat(unsupported.headers().getFirst(HttpHeaders.ACCEPT))
                    .isEqualTo("application/json, application/*+json");
            assertThat(unsupported.advice()).isNull();

            Result versioned = call(nestedClient
                    .put()
                    .uri("/v1/gizmos/7")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"name\":\"Dial\"}"));
            assertThat(versioned.status()).isEqualTo(200);
            assertThat(versioned.json().get("name").asString()).isEqualTo("Dial");
        }

        @Test
        void preHandlerErrorOnAControllerPathGoesToTheAdvice() {
            Result result = call(nestedClient.get().uri("/gadgets/7"));

            assertThat(result.status()).isEqualTo(400);
            assertThat(result.advice()).isEqualTo("catch-all");
            assertThat(result.body()).doesNotContain("\"code\"").doesNotContain("correlationId");
        }
    }

    /**
     * A catch-all advice next to unversioned and versioned Synapse routes, in a context of its own: the 404 of an
     * unknown path under a Synapse route's literal prefix (under any version, for a versioned route) is Synapse's, and
     * any other 404 is the advice's.
     */
    @Nested
    @Import({CatchAllAdvice.class, BookRoutes.class, GizmoRoutes.class, PrefixRoutes.class, BooksController.class})
    class UnknownPaths {

        @Autowired
        private RestTestClient nestedClient;

        @Test
        void unknownPathUnderASynapsePrefixIsRouteNotFound() {
            for (String uri : List
                    .of("/v3/gizmos/7", "/books/7/reviews", "/api/books", "/api/books/7/nope",
                            "/v9/books/x", "/v2/books/x")) {
                Result result = call(nestedClient.get().uri(uri));

                assertThat(result.status()).as(uri).isEqualTo(404);
                assertThat(result.contentType()).as(uri).startsWith("application/problem+json");
                assertThat(result.json().get("code").asString()).as(uri).isEqualTo("ROUTE_NOT_FOUND");
                assertThat(result.json().get("correlationId").asString()).as(uri).isNotEmpty();
                assertThat(result.advice()).as(uri).isNull();
            }
        }

        /** A shared first segment, or a version without a route's prefix after it, is not Synapse's. */
        @Test
        void unknownPathElsewhereGoesToTheAdvice() {
            for (String uri : List
                    .of("/catalog/unknown", "/api/other", "/v2/legacy/x", "/v9/unknown",
                            "/v1/unknown")) {
                Result result = call(nestedClient.get().uri(uri));

                assertThat(result.status()).as(uri).isEqualTo(404);
                assertThat(result.advice()).as(uri).isEqualTo("catch-all");
                assertThat(result.body()).as(uri).doesNotContain("\"code\"").doesNotContain("correlationId");
            }
        }
    }

    /**
     * {@code spring.mvc.problemdetails.enabled=true} next to the same routes, in a context of its own: Boot's
     * {@code ProblemDetailsExceptionHandler} answers only the 404s outside Synapse's prefixes.
     */
    @Nested
    @TestPropertySource(properties = "spring.mvc.problemdetails.enabled=true")
    @Import({BookRoutes.class, GizmoRoutes.class, PrefixRoutes.class})
    class UnknownPathsWithProblemDetails {

        @Autowired
        private RestTestClient nestedClient;

        @Test
        void unknownPathUnderASynapsePrefixIsRouteNotFound() {
            for (String uri : List.of("/v3/gizmos/7", "/api/books/7/nope", "/v9/books/x")) {
                Result result = call(nestedClient.get().uri(uri));

                assertThat(result.status()).as(uri).isEqualTo(404);
                assertThat(result.json().get("code").asString()).as(uri).isEqualTo("ROUTE_NOT_FOUND");
                assertThat(result.json().get("correlationId").asString()).as(uri).isNotEmpty();
            }
        }

        @Test
        void unknownPathElsewhereIsSpringsProblem() {
            for (String uri : List.of("/catalog/unknown", "/api/other", "/v2/legacy/x", "/v9/unknown")) {
                Result result = call(nestedClient.get().uri(uri));

                assertThat(result.status()).as(uri).isEqualTo(404);
                assertThat(result.contentType()).as(uri).startsWith("application/problem+json");
                assertThat(result.body()).as(uri).doesNotContain("\"code\"").doesNotContain("correlationId");
            }
        }
    }

    /**
     * Spring Security's {@code ExceptionTranslationFilter} in front of a controller, as an authenticated user, in a
     * context of its own without advice: Security's exceptions reach it, and its {@code sendError} is answered by the
     * error controller.
     */
    @Nested
    @Import({SecurityFilters.class, SecuredController.class})
    class Security {

        @Autowired
        private RestTestClient nestedClient;

        @Test
        void accessDeniedPropagatesToSecurity() {
            Result denied = call(nestedClient.get().uri("/secured/denied"));

            assertThat(denied.status()).isEqualTo(403);
            assertThat(denied.contentType()).startsWith("application/problem+json");
            assertThat(denied.json().get("code").asString()).isEqualTo("FORBIDDEN");
            assertThat(denied.json().has("correlationId")).isTrue();

            Result unauthenticated = call(nestedClient.get().uri("/secured/unauthenticated"));
            assertThat(unauthenticated.status()).isEqualTo(401);
            assertThat(unauthenticated.contentType()).startsWith("application/problem+json");
            assertThat(unauthenticated.json().get("code").asString()).isEqualTo("UNAUTHENTICATED");
            assertThat(unauthenticated.headers().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                    .isEqualTo("Bearer realm=\"books\"");
        }

        @Test
        void wrappedAccessDeniedPropagatesToSecurity() {
            Result denied = call(nestedClient.get().uri("/secured/wrapped"));

            // Security's ThrowableAnalyzer finds it in the cause chain, so it is Security's 403, not Synapse's 500.
            assertThat(denied.status()).isEqualTo(403);
            assertThat(denied.contentType()).startsWith("application/problem+json");
            assertThat(denied.json().get("code").asString()).isEqualTo("FORBIDDEN");
            assertThat(denied.body()).doesNotContain("secret");
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({ThingsController.class, StatusController.class})
    static class TestApplication {

        @Bean
        Calls calls() {
            return new Calls();
        }

        @Bean
        GetThingOperation getThingOperation(Calls calls) {
            return new GetThingOperation(calls);
        }

        @Bean
        GetSlugOperation getSlugOperation(Calls calls) {
            return new GetSlugOperation(calls);
        }

        @Bean
        PutSlugOperation putSlugOperation(Calls calls) {
            return new PutSlugOperation(calls);
        }

        @Bean
        RenameThingOperation renameThingOperation(Calls calls) {
            return new RenameThingOperation(calls);
        }

        @Bean
        RestBindings things(GetThingOperation getThing, GetSlugOperation getSlug, PutSlugOperation putSlug) {
            return RestBindings
                    .builder()
                    .get("/things/{id}", getThing)
                    .get("/widgets/{id}", getThing)
                    // Both templates match /error, the path of the container's error dispatch.
                    .get("/{slug}", getSlug)
                    .put("/{slug}", putSlug)
                    .build();
        }

        /** Runs after the edge filters and fails, so the failure reaches the container's error dispatch. */
        @Bean
        FilterRegistrationBean<Filter> failingFilter() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) -> {
                throw new IllegalStateException("filter failed");
            });
            registration.addUrlPatterns("/filter-fail");
            registration.setDispatcherTypes(DispatcherType.REQUEST);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 7);
            return registration;
        }
    }

    /** Counts the operation calls. */
    static class Calls {

        final AtomicInteger things = new AtomicInteger();

        final AtomicInteger slugs = new AtomicInteger();

        final AtomicInteger renames = new AtomicInteger();
    }

    record ThingId(String id) {
    }

    record Slug(String slug) {
    }

    record ThingName(String id, @NotBlank String name) {
    }

    record Author(@NotBlank String countryCode) {
    }

    record Edition(@Size(max = 3) String publishedYear, @Valid Author author) {
    }

    static class GetThingOperation implements QueryOperation<ThingId, ThingId> {

        private final Calls calls;

        GetThingOperation(Calls calls) {
            this.calls = calls;
        }

        @Override
        public ThingId execute(ThingId input) {
            calls.things.incrementAndGet();
            return input;
        }
    }

    static class GetSlugOperation implements QueryOperation<Slug, Slug> {

        private final Calls calls;

        GetSlugOperation(Calls calls) {
            this.calls = calls;
        }

        @Override
        public Slug execute(Slug input) {
            calls.slugs.incrementAndGet();
            return input;
        }
    }

    static class PutSlugOperation implements CommandOperation<Slug, Slug> {

        private final Calls calls;

        PutSlugOperation(Calls calls) {
            this.calls = calls;
        }

        @Override
        public Slug execute(Slug input) {
            calls.slugs.incrementAndGet();
            return input;
        }
    }

    /** An unbound operation that only the controller calls. */
    static class RenameThingOperation implements CommandOperation<ThingName, String> {

        private final Calls calls;

        RenameThingOperation(Calls calls) {
            this.calls = calls;
        }

        @Override
        public String execute(ThingName input) {
            calls.renames.incrementAndGet();
            return "renamed " + input.id() + " to " + input.name();
        }
    }

    /**
     * An escape-hatch controller that adds PATCH to a path a Synapse route serves with GET, and calls an operation on
     * another thread with the input it bound.
     */
    @RestController
    static class ThingsController {

        private final RenameThingOperation renameThing;

        ThingsController(RenameThingOperation renameThing) {
            this.renameThing = renameThing;
        }

        @PatchMapping("/things/{id}")
        String patch(@PathVariable String id) {
            return "patched " + id;
        }

        @PatchMapping("/things/{id}/name")
        String rename(@PathVariable String id, @RequestParam(defaultValue = "") String name) {
            try {
                return CompletableFuture.supplyAsync(() -> renameThing.execute(new ThingName(id, name))).join();
            } catch (CompletionException wrapped) {
                if (wrapped.getCause() instanceof SynapseException synapse) {
                    throw synapse;
                }
                throw wrapped;
            }
        }
    }

    /** A name already in use: {@code @ResponseStatus(CONFLICT)} with a reason. */
    @ResponseStatus(code = HttpStatus.CONFLICT, reason = "name taken")
    static class NameTakenException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        NameTakenException() {
            super("secret-taken");
        }
    }

    /** A resource that is gone for good: {@code @ResponseStatus(GONE)}. */
    @ResponseStatus(HttpStatus.GONE)
    static class RetiredException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        RetiredException() {
            super("secret-retired");
        }
    }

    /** A 503 with {@code Retry-After: 5}. */
    static class BusyException extends ResponseStatusException {

        private static final long serialVersionUID = 1L;

        BusyException() {
            super(HttpStatus.SERVICE_UNAVAILABLE, "busy");
        }

        @Override
        public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, "5");
            return headers;
        }
    }

    /** A controller that raises Spring statuses, inside and outside Synapse's table. */
    @RestController
    static class StatusController {

        @GetMapping("/statuses/unprocessable")
        String unprocessable() {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "secret-reason");
        }

        @GetMapping("/statuses/gone")
        String gone() {
            throw new ResponseStatusException(HttpStatus.GONE);
        }

        @GetMapping("/statuses/bad-gateway")
        String badGateway() {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "secret-upstream");
        }

        @GetMapping("/statuses/taken")
        String taken() {
            throw new NameTakenException();
        }

        @GetMapping("/statuses/wrapped")
        String wrapped() {
            throw new IllegalStateException("secret-wrapper", new RetiredException());
        }

        @GetMapping("/statuses/busy")
        String busy() {
            throw new BusyException();
        }

        @GetMapping("/statuses/quota")
        String quota() {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "quota");
        }
    }

    /** A controller that validates its JSON body with Spring MVC, not an operation. */
    @RestController
    static class EditionsController {

        @PostMapping("/editions")
        String create(@Valid @RequestBody Edition edition) {
            return edition.publishedYear();
        }
    }

    /** A controller on paths no Synapse route serves, with a handler of its own for one exception. */
    @RestController
    static class GadgetsController {

        @GetMapping("/gadgets/{id}")
        String gadget(@PathVariable String id) {
            if ("broken".equals(id)) {
                throw new IllegalStateException("broken gadget");
            }
            return "gadget " + id;
        }

        @GetMapping("/gadgets/{id}/parts")
        String parts(@PathVariable String id) {
            throw new UnsupportedOperationException("no parts");
        }

        @GetMapping("/gadgets/{id}/wheels")
        String wheels(@PathVariable String id) {
            throw new ArithmeticException("no wheels");
        }

        @ExceptionHandler(UnsupportedOperationException.class)
        ResponseEntity<String> unsupported(UnsupportedOperationException ex) {
            return ResponseEntity.status(409).body("handled locally");
        }
    }

    /** Answers an {@link IllegalStateException} from any controller. */
    @ControllerAdvice
    static class IllegalStateAdvice {

        @ExceptionHandler(IllegalStateException.class)
        ResponseEntity<String> illegalState(IllegalStateException ex) {
            return ResponseEntity.status(418).body("advised");
        }
    }

    /**
     * A catch-all advice on Spring's {@code ResponseEntityExceptionHandler}, as applications often write it. Every
     * answer carries {@value #ADVICE_HEADER}, so a test can tell it from Synapse's.
     */
    @ControllerAdvice
    static class CatchAllAdvice extends ResponseEntityExceptionHandler {

        @ExceptionHandler(Exception.class)
        ResponseEntity<String> any(Exception ex) {
            return ResponseEntity.status(418).header(ADVICE_HEADER, "catch-all").body("advised");
        }

        @Override
        protected @Nullable ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
                HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
            HttpHeaders marked = new HttpHeaders();
            marked.addAll(headers);
            marked.set(ADVICE_HEADER, "catch-all");
            return super.handleExceptionInternal(ex, body, marked, statusCode, request);
        }
    }

    record BookQuery(@Nullable String author) {
    }

    record BookList(List<String> titles) {
    }

    record BookKey(long id) {
    }

    record Gizmo(String id, String name) {
    }

    static class ListBooksOperation implements QueryOperation<BookQuery, BookList> {
        @Override
        public BookList execute(BookQuery input) {
            return new BookList(List.of("Dune"));
        }
    }

    static class FindBookOperation implements QueryOperation<BookKey, BookKey> {
        @Override
        public BookKey execute(BookKey input) {
            throw new SynapseException(BookErrors.BOOK_NOT_FOUND, input.id());
        }
    }

    static class PutGizmoOperation implements CommandOperation<Gizmo, Gizmo> {
        @Override
        public Gizmo execute(Gizmo input) {
            return input;
        }
    }

    /** Synapse routes on {@code /books}, which {@link BooksController} shares, and {@code /books/{id}}. */
    @Configuration(proxyBeanMethods = false)
    static class BookRoutes {

        @Bean
        ListBooksOperation listBooksOperation() {
            return new ListBooksOperation();
        }

        @Bean
        FindBookOperation findBookOperation() {
            return new FindBookOperation();
        }

        @Bean
        RestBindings books(ListBooksOperation listBooks, FindBookOperation findBook) {
            return RestBindings.builder().get("/books", listBooks).get("/books/{id}", findBook).build();
        }
    }

    /** A controller that adds a JSON {@code POST} to the Synapse path {@code /books}, and one on its own path. */
    @RestController
    static class BooksController {

        @PostMapping(path = "/books", consumes = MediaType.APPLICATION_JSON_VALUE)
        String create(@RequestBody String title) {
            return "created";
        }

        @PostMapping(path = "/catalog/books", consumes = MediaType.APPLICATION_JSON_VALUE)
        String catalog(@RequestBody String title) {
            return "catalogued";
        }
    }

    static class FindShelvedBookOperation implements QueryOperation<BookKey, BookKey> {
        @Override
        public BookKey execute(BookKey input) {
            return input;
        }
    }

    /**
     * Synapse routes on a two-segment literal prefix, {@code /api/books/{id}}, and on {@code /books/{id}} at versions
     * 1 and 3 only.
     */
    @Configuration(proxyBeanMethods = false)
    static class PrefixRoutes {

        @Bean
        FindShelvedBookOperation findShelvedBookOperation() {
            return new FindShelvedBookOperation();
        }

        @Bean
        RestBindings prefixed(FindShelvedBookOperation find) {
            return RestBindings
                    .builder()
                    .get("/api/books/{id}", find)
                    .get("/books/{id}", find)
                    .version("1")
                    .get("/books/{id}", find)
                    .version("3")
                    .build();
        }
    }

    /** A versioned route, so Spring's API versioning is on: {@code PUT /v1/gizmos/{id}} with a JSON body. */
    @Configuration(proxyBeanMethods = false)
    static class GizmoRoutes {

        @Bean
        PutGizmoOperation putGizmoOperation() {
            return new PutGizmoOperation();
        }

        @Bean
        RestBindings gizmos(PutGizmoOperation putGizmo) {
            return RestBindings.builder().put("/gizmos/{id}", putGizmo).version("1").build();
        }
    }

    /**
     * Spring Security's {@code ExceptionTranslationFilter} on {@code /secured/*}, where Security's filter chain would
     * be ({@code -100}), as an authenticated user. Its entry point answers 401 with its own {@code WWW-Authenticate},
     * and Spring's {@code AccessDeniedHandlerImpl} answers 403; both call {@code sendError}.
     */
    @Configuration(proxyBeanMethods = false)
    static class SecurityFilters {

        @Bean
        FilterRegistrationBean<Filter> securityFilter() {
            AuthenticationEntryPoint entryPoint = (request, response, failure) -> {
                response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"books\"");
                response.sendError(401);
            };
            ExceptionTranslationFilter translation = new ExceptionTranslationFilter(entryPoint, new NullRequestCache());
            translation.setAccessDeniedHandler(new AccessDeniedHandlerImpl());
            Filter authenticated = (request, response, chain) -> {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context
                        .setAuthentication(UsernamePasswordAuthenticationToken
                                .authenticated("reader", "n/a", AuthorityUtils.createAuthorityList("ROLE_READER")));
                SecurityContextHolder.setContext(context);
                try {
                    translation.doFilter(request, response, chain);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            };
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(authenticated);
            registration.addUrlPatterns("/secured/*");
            registration.setDispatcherTypes(DispatcherType.REQUEST);
            registration.setOrder(-100);
            return registration;
        }
    }

    /** A controller that raises Spring Security's two exceptions, and one wrapped in another exception. */
    @RestController
    static class SecuredController {

        @GetMapping("/secured/denied")
        String denied() {
            throw new AccessDeniedException("denied");
        }

        @GetMapping("/secured/unauthenticated")
        String unauthenticated() {
            throw new BadCredentialsException("bad credentials");
        }

        @GetMapping("/secured/wrapped")
        String wrapped() {
            throw new IllegalStateException("secret wrapper", new AccessDeniedException("denied"));
        }
    }
}
