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

import java.util.concurrent.atomic.AtomicInteger;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.RestBindings;
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
class EscapeHatchRoutesIT {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private RestTestClient client;

    @Autowired
    private Calls calls;

    /** A response: its status, content type, {@code Allow} header and body. */
    record Result(int status, String contentType, @Nullable String allow, String body) {

        JsonNode json() {
            return MAPPER.readTree(body);
        }
    }

    static Result call(RestTestClient.RequestHeadersSpec<?> spec) {
        var result = spec.exchange().returnResult(String.class);
        return new Result(result.getStatus().value(), String.valueOf(result.getResponseHeaders().getContentType()),
                result.getResponseHeaders().getFirst("Allow"), String.valueOf(result.getResponseBody()));
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

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(ThingsController.class)
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
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 6);
            return registration;
        }
    }

    /** Counts the operation calls. */
    static class Calls {

        final AtomicInteger things = new AtomicInteger();

        final AtomicInteger slugs = new AtomicInteger();
    }

    record ThingId(String id) {
    }

    record Slug(String slug) {
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

    /** An escape-hatch controller that adds PATCH to a path a Synapse route serves with GET. */
    @RestController
    static class ThingsController {

        @PatchMapping("/things/{id}")
        String patch(@PathVariable String id) {
            return "patched " + id;
        }
    }
}
