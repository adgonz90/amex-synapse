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
package io.americanexpress.synapse.api.rest.internal.cors;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.americanexpress.synapse.api.rest.autoconfigure.SynapseRestProperties;
import io.americanexpress.synapse.api.rest.internal.edge.ConcurrencyLimitFilter;
import io.americanexpress.synapse.api.rest.internal.edge.RequestSizeLimitFilter;
import io.americanexpress.synapse.api.rest.internal.edge.RestRequestAttributes;
import io.americanexpress.synapse.api.rest.internal.error.ClientErrorFilter;
import io.americanexpress.synapse.api.rest.internal.error.ProblemTitles;
import io.americanexpress.synapse.api.rest.internal.error.ProblemWriter;
import io.americanexpress.synapse.api.rest.internal.error.RestErrorStatuses;
import io.americanexpress.synapse.core.autoconfigure.SynapseErrorProperties;
import io.americanexpress.synapse.core.error.ErrorMessages;

import static org.assertj.core.api.Assertions.assertThat;

class SynapseCorsFilterTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String APP = "https://app.example.com";

    private static final String EVIL = "https://evil.example";

    private static final String ALLOW_ORIGIN = "Access-Control-Allow-Origin";

    private final SynapseCorsFilter filter = new SynapseCorsFilter(source(List.of(APP)), writer(false));

    private static SynapseCorsConfigurationSource source(List<String> origins) {
        return source(origins, List.of());
    }

    private static SynapseCorsConfigurationSource source(List<String> origins, List<String> managementPrefixes) {
        return SynapseCorsConfigurationSource
                .of(new SynapseRestProperties.Cors(origins, List.of("GET", "HEAD", "POST", "PUT", "DELETE"),
                        List.of("*"), List.of(), false, Duration.ofMinutes(30)), "X-Correlation-Id",
                        managementPrefixes);
    }

    private static ProblemWriter writer(boolean includeDetail) {
        StaticMessageSource messages = new StaticMessageSource();
        return new ProblemWriter(new RestErrorStatuses(Map.of(), Map.of()), new ErrorMessages(messages),
                new ProblemTitles(messages), new SynapseErrorProperties(includeDetail, null), MAPPER,
                "X-Correlation-Id");
    }

    private static MockHttpServletRequest request(String method, String path, @Nullable String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setAttribute(RestRequestAttributes.CORRELATION_ID, "c-cors");
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        return request;
    }

    private static MockHttpServletRequest preflight(String path, String origin, String method) {
        MockHttpServletRequest request = request("OPTIONS", path, origin);
        request.addHeader("Access-Control-Request-Method", method);
        return request;
    }

    /** A chain that records the response headers it saw, and then answers 200 with a body. */
    private static final class RecordingChain implements FilterChain {

        private final List<@Nullable String> allowOrigins = new ArrayList<>();

        private final List<@Nullable String> exposed = new ArrayList<>();

        int calls() {
            return allowOrigins.size();
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) throws IOException {
            HttpServletResponse http = (HttpServletResponse) response;
            allowOrigins.add(http.getHeader(ALLOW_ORIGIN));
            exposed.add(http.getHeader("Access-Control-Expose-Headers"));
            http.getWriter().write("ok");
        }
    }

    @Test
    void withoutConfigurationTheChainRuns() throws Exception {
        SynapseCorsFilter off = new SynapseCorsFilter(source(List.of()), writer(false));
        RecordingChain chain = new RecordingChain();

        MockHttpServletResponse actual = new MockHttpServletResponse();
        off.doFilter(request("GET", "/books/1", EVIL), actual, chain);
        // A pre-flight too: without a configuration, what is behind the filter answers it.
        MockHttpServletResponse options = new MockHttpServletResponse();
        off.doFilter(preflight("/books/1", EVIL, "PUT"), options, chain);

        assertThat(chain.calls()).isEqualTo(2);
        assertThat(actual.getStatus()).isEqualTo(200);
        assertThat(actual.getContentAsString()).isEqualTo("ok");
        assertThat(actual.getHeader(ALLOW_ORIGIN)).isNull();
        assertThat(options.getStatus()).isEqualTo(200);
        assertThat(options.getContentAsString()).isEqualTo("ok");
        assertThat(options.getHeader(ALLOW_ORIGIN)).isNull();
    }

    @Test
    void rejectionWritesACorsRejectedProblem() throws Exception {
        RecordingChain chain = new RecordingChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("GET", "/books/1", EVIL), response, chain);

        assertThat(chain.calls()).isZero();
        assertRejected(response);
        assertThat(MAPPER.readTree(response.getContentAsByteArray()).has("detail")).isFalse();
        // A pre-flight for a method that isn't allowed, and a malformed origin, are rejected the same way.
        MockHttpServletResponse method = new MockHttpServletResponse();
        filter.doFilter(preflight("/books/1", APP, "PATCH"), method, chain);
        assertRejected(method);
        MockHttpServletResponse malformed = new MockHttpServletResponse();
        filter.doFilter(request("GET", "/books/1", "https://app.example.com:bad"), malformed, chain);
        assertRejected(malformed);
        assertThat(chain.calls()).isZero();
    }

    @Test
    void rejectionHasDetailOnlyWhenIncluded() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SynapseCorsFilter(source(List.of(APP)), writer(true))
                .doFilter(request("GET", "/books/1", EVIL), response, new RecordingChain());

        assertRejected(response);
        assertThat(MAPPER.readTree(response.getContentAsByteArray()).get("detail").asString())
                .isEqualTo("The cross-origin request is not allowed");
    }

    private static void assertRejected(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getHeader(ALLOW_ORIGIN)).isNull();
        assertThat(response.getHeaders("Vary")).contains("Origin");
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("c-cors");
        JsonNode body = MAPPER.readTree(response.getContentAsByteArray());
        assertThat(body.get("code").asString()).isEqualTo("CORS_REJECTED");
        assertThat(body.get("status").asInt()).isEqualTo(403);
        assertThat(body.get("correlationId").asString()).isEqualTo("c-cors");
        assertThat(body.toString()).doesNotContain("Invalid CORS request").doesNotContain("Exception");
    }

    @Test
    void validPreflightIsAnsweredHere() throws Exception {
        RecordingChain chain = new RecordingChain();
        MockHttpServletRequest request = preflight("/books/1", APP, "PUT");
        request.addHeader("Access-Control-Request-Headers", "If-Match");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(chain.calls()).isZero();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getHeader(ALLOW_ORIGIN)).isEqualTo(APP);
        assertThat(response.getHeader("Access-Control-Allow-Methods")).contains("PUT");
        assertThat(response.getHeader("Access-Control-Allow-Headers")).isEqualToIgnoringCase("If-Match");
        assertThat(response.getHeader("Access-Control-Max-Age")).isEqualTo("1800");
        assertThat(response.getHeaders("Vary"))
                .contains("Origin", "Access-Control-Request-Method", "Access-Control-Request-Headers");
    }

    @Test
    void actualRequestGetsHeadersBeforeTheChain() throws Exception {
        RecordingChain chain = new RecordingChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("GET", "/books/1", APP), response, chain);

        assertThat(chain.allowOrigins).containsExactly(APP);
        assertThat(chain.exposed)
                .singleElement()
                .satisfies(exposed -> assertThat(exposed)
                        .isEqualTo("X-Correlation-Id, ETag, Link, Location, Retry-After, Deprecation, Sunset"));
        assertThat(response.getContentAsString()).isEqualTo("ok");
        assertThat(response.getHeaders("Vary")).contains("Origin");
    }

    @Test
    void errorDispatchKeepsHeadersAlreadySet() throws Exception {
        RecordingChain chain = new RecordingChain();
        MockHttpServletRequest request = request("GET", "/error", APP);
        request.setDispatcherType(DispatcherType.ERROR);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader(ALLOW_ORIGIN, APP);

        filter.doFilter(request, response, chain);

        assertThat(chain.calls()).isEqualTo(1);
        assertThat(response.getHeaders(ALLOW_ORIGIN)).containsExactly(APP);
    }

    @Test
    void errorDispatchIsProcessed() throws Exception {
        // An error dispatch of its own, after the request dispatch ended: the response lost its headers.
        RecordingChain chain = new RecordingChain();
        MockHttpServletRequest allowed = request("GET", "/error", APP);
        allowed.setDispatcherType(DispatcherType.ERROR);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(allowed, response, chain);
        assertThat(chain.allowOrigins).containsExactly(APP);

        MockHttpServletRequest rejected = request("GET", "/error", EVIL);
        rejected.setDispatcherType(DispatcherType.ERROR);
        MockHttpServletResponse forbidden = new MockHttpServletResponse();
        filter.doFilter(rejected, forbidden, chain);
        assertRejected(forbidden);
        assertThat(chain.calls()).isEqualTo(1);
    }

    @Test
    void errorDispatchNestedInTheRequestDispatchIsProcessed() throws Exception {
        RecordingChain inner = new RecordingChain();
        MockHttpServletRequest request = request("GET", "/books/1", APP);
        MockHttpServletResponse response = new MockHttpServletResponse();

        // As an error page filter does: the request dispatch's chain resets the response and dispatches to the error
        // page, through this filter again, before the request dispatch returns.
        filter.doFilter(request, response, (in, out) -> {
            ((HttpServletResponse) out).reset();
            ((MockHttpServletRequest) in).setDispatcherType(DispatcherType.ERROR);
            filter.doFilter(in, out, inner);
        });

        assertThat(inner.allowOrigins).containsExactly(APP);
        assertThat(response.getHeaders(ALLOW_ORIGIN)).containsExactly(APP);
    }

    @Test
    void requestsWithoutACrossOriginAreLeftAlone() throws Exception {
        RecordingChain chain = new RecordingChain();
        MockHttpServletRequest optionsWithoutOrigin = request("OPTIONS", "/books/1", null);
        optionsWithoutOrigin.addHeader("Access-Control-Request-Method", "PUT");
        // MockHttpServletRequest is http://localhost:80, so this Origin is the request's own.
        List<MockHttpServletRequest> requests = List
                .of(request("GET", "/books/1", null), optionsWithoutOrigin,
                        request("GET", "/books/1", "http://localhost"));

        for (MockHttpServletRequest request : requests) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            String name = request.getMethod() + " " + request.getHeader("Origin");
            assertThat(response.getStatus()).as(name).isEqualTo(200);
            assertThat(response.getContentAsString()).as(name).isEqualTo("ok");
            assertThat(response.getHeader(ALLOW_ORIGIN)).as(name).isNull();
            assertThat(response.getHeader("Access-Control-Expose-Headers")).as(name).isNull();
        }
        assertThat(chain.calls()).isEqualTo(3);
        assertThat(chain.allowOrigins).containsOnlyNulls();
        assertThat(chain.exposed).containsOnlyNulls();

        // A same-origin OPTIONS with Access-Control-Request-Method has a pre-flight's shape, and, as in Spring's
        // CorsFilter, the filter answers it: 200, no body, no CORS headers, and the chain doesn't run.
        MockHttpServletResponse sameOrigin = new MockHttpServletResponse();
        filter.doFilter(preflight("/books/1", "http://localhost", "PUT"), sameOrigin, chain);
        assertThat(chain.calls()).isEqualTo(3);
        assertThat(sameOrigin.getStatus()).isEqualTo(200);
        assertThat(sameOrigin.getContentAsByteArray()).isEmpty();
        assertThat(sameOrigin.getHeader(ALLOW_ORIGIN)).isNull();
        assertThat(sameOrigin.getHeader("Access-Control-Allow-Methods")).isNull();
    }

    @Test
    void errorDispatchOfAManagementRequestIsLeftToBoot() throws Exception {
        SynapseCorsFilter withActuator = new SynapseCorsFilter(source(List.of(APP), List.of("/actuator")),
                writer(false));
        for (String origin : List.of(EVIL, APP)) {
            RecordingChain chain = new RecordingChain();
            MockHttpServletRequest request = SynapseCorsConfigurationSourceTest.errorDispatch("", "/actuator/health");
            request.addHeader("Origin", origin);
            MockHttpServletResponse response = new MockHttpServletResponse();

            withActuator.doFilter(request, response, chain);

            assertThat(chain.calls()).as(origin).isEqualTo(1);
            assertThat(response.getStatus()).as(origin).isEqualTo(200);
            assertThat(response.getHeader(ALLOW_ORIGIN)).as(origin).isNull();
        }
    }

    @Test
    void nestedErrorDispatchOfAManagementRequestIsLeftToBoot() throws Exception {
        SynapseCorsFilter withActuator = new SynapseCorsFilter(source(List.of(APP), List.of("/actuator")),
                writer(false));
        for (String origin : List.of(EVIL, APP)) {
            RecordingChain inner = new RecordingChain();
            MockHttpServletRequest request = request("GET", "/actuator/health", origin);
            MockHttpServletResponse response = new MockHttpServletResponse();

            // As an error page filter does: the management request fails, and its error page is dispatched to.
            withActuator.doFilter(request, response, (in, out) -> {
                MockHttpServletRequest error = (MockHttpServletRequest) in;
                error.setDispatcherType(DispatcherType.ERROR);
                error.setRequestURI("/error");
                error.setServletPath("/error");
                error.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/actuator/health");
                withActuator.doFilter(error, out, inner);
            });

            assertThat(inner.calls()).as(origin).isEqualTo(1);
            assertThat(response.getStatus()).as(origin).isEqualTo(200);
            assertThat(response.getHeader(ALLOW_ORIGIN)).as(origin).isNull();
        }
    }

    @Test
    void ordersAreRenumbered() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 3);
        assertThat(new ConcurrencyLimitFilter(1, List.of(), writer(false)).getOrder())
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE + 4);
        assertThat(new RequestSizeLimitFilter(DataSize.ofMegabytes(1)).getOrder())
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE + 5);
        assertThat(new ClientErrorFilter(writer(false)).getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 6);
    }
}
