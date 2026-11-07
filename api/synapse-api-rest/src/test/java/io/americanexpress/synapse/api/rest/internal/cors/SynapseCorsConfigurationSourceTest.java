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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

import io.americanexpress.synapse.api.rest.autoconfigure.SynapseRestProperties;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class SynapseCorsConfigurationSourceTest {

    private static final String WILDCARD_WARNING = "synapse.api.rest.cors.allowed-origins contains *: any website can"
            + " call this API from a visitor's browser. List the exact origins instead (for example"
            + " https://app.example.com).";

    @Test
    void noOriginsMeansNoConfiguration() {
        SynapseCorsConfigurationSource source = SynapseCorsConfigurationSource
                .of(cors(List.of(), List.of(), false), "X-Correlation-Id", List.of("/actuator"));

        assertThat(source.getCorsConfiguration(request("/books"))).isNull();
        assertThat(source.getCorsConfiguration(request("/"))).isNull();
        assertThat(source.getCorsConfigurations()).isEmpty();
    }

    @Test
    void configurationCarriesTheSettings() {
        SynapseRestProperties.Cors cors = new SynapseRestProperties.Cors(
                List.of("https://app.example.com", "http://localhost:3000"), List.of("GET", "POST"),
                List.of("X-Tenant", "Content-Type"), List.of(), true, Duration.ofMinutes(30));

        SynapseCorsConfigurationSource source = SynapseCorsConfigurationSource.of(cors, "X-Correlation-Id", List.of());
        CorsConfiguration configuration = configurationFor(source, "/books");

        assertThat(configuration.getAllowedOrigins())
                .containsExactly("https://app.example.com", "http://localhost:3000");
        assertThat(configuration.getAllowedMethods()).containsExactly("GET", "POST");
        assertThat(configuration.getAllowedHeaders()).containsExactly("X-Tenant", "Content-Type");
        assertThat(configuration.getAllowCredentials()).isTrue();
        assertThat(configuration.getMaxAge()).isEqualTo(1800L);
        assertThat(configuration.checkOrigin("https://app.example.com")).isEqualTo("https://app.example.com");
        assertThat(configuration.checkOrigin("https://evil.example.com")).isNull();
        // One configuration, on every path.
        assertThat(configurationFor(source, "/v2/books/7/reviews")).isSameAs(configuration);
    }

    @Test
    void synapseHeadersAreAlwaysExposed() {
        SynapseRestProperties.Cors cors = cors(List.of("https://app.example.com"), List.of("X-Books-Total", "etag"),
                false);

        assertThat(configurationFor(SynapseCorsConfigurationSource.of(cors, "X-Correlation-Id", List.of()), "/books")
                .getExposedHeaders())
                .containsExactly("X-Correlation-Id", "ETag", "Link", "Location", "Retry-After", "Deprecation",
                        "Sunset", "X-Books-Total");
        // The configured correlation header, and no duplicate whatever its case.
        assertThat(configurationFor(SynapseCorsConfigurationSource
                .of(cors(List.of("https://app.example.com"), List.of("x-request-id", "LINK"), false), "X-Request-Id",
                        List.of()),
                "/books").getExposedHeaders())
                .containsExactly("X-Request-Id", "ETag", "Link", "Location", "Retry-After", "Deprecation", "Sunset");
    }

    @Test
    void managementPathsAreLeftToBoot() {
        SynapseCorsConfigurationSource source = SynapseCorsConfigurationSource
                .of(cors(List.of("https://app.example.com"), List.of(), false), "X-Correlation-Id",
                        List.of("/actuator"));

        assertThat(source.getCorsConfiguration(request("/actuator/health"))).isNull();
        assertThat(source.getCorsConfiguration(request("/actuator"))).isNull();
        assertThat(source.getCorsConfiguration(request("/books"))).isNotNull();
        assertThat(source.getCorsConfiguration(request("/actuatorx"))).isNotNull();
        assertThat(source.getCorsConfiguration(request("/files/actuator/health"))).isNotNull();
        assertThat(source.getCorsConfiguration(request("/books;v=1/7"))).isNotNull();
    }

    @Test
    void rootBasePathLeavesOnlyServerHealthGroupPathsToBoot() {
        // With management.endpoints.web.base-path=/ and a health group's additional-path server:/healthz, the
        // management prefixes are only /healthz (ManagementPathsTest.serverAdditionalPathsStayWithARootBasePath).
        SynapseCorsConfigurationSource source = SynapseCorsConfigurationSource
                .of(cors(List.of("https://app.example.com"), List.of(), false), "X-Correlation-Id",
                        List.of("/healthz"));

        // The endpoints at the root can't be told apart from the application's paths: Synapse's settings apply.
        assertThat(source.getCorsConfiguration(request("/health"))).isNotNull();
        assertThat(source.getCorsConfiguration(request("/env"))).isNotNull();
        assertThat(source.getCorsConfiguration(request("/books"))).isNotNull();
        // The health group's path on the server's port is still Actuator's, left to Boot.
        assertThat(source.getCorsConfiguration(request("/healthz"))).isNull();
        assertThat(source.getCorsConfiguration(request("/healthz/db"))).isNull();
        assertThat(source.getCorsConfiguration(request("/%68ealthz"))).isNull();
    }

    @Test
    void managementPathsFailClosed() {
        SynapseCorsConfigurationSource source = SynapseCorsConfigurationSource
                .of(cors(List.of("https://app.example.com"), List.of(), false), "X-Correlation-Id",
                        List.of("/actuator"));

        // A raw URI that normalizes to a management path is one, whether the container mapped it there or not.
        MockHttpServletRequest traversal = request("/files/../actuator/health");
        traversal.setServletPath("/actuator/health");
        assertThat(source.getCorsConfiguration(traversal)).isNull();
        assertThat(source.getCorsConfiguration(request("/files/../actuator/health"))).isNull();
        // Percent-encoded and path-parameter spellings of a management path.
        assertThat(source.getCorsConfiguration(request("/%61ctuator/env"))).isNull();
        assertThat(source.getCorsConfiguration(request("/actuator;x/env"))).isNull();
        assertThat(source.getCorsConfiguration(request("//actuator/env"))).isNull();
        // The container's mapped path alone is enough.
        MockHttpServletRequest mapped = request("/books/1");
        mapped.setServletPath("/actuator/env");
        assertThat(source.getCorsConfiguration(mapped)).isNull();
        // The raw URI alone is enough too.
        MockHttpServletRequest raw = request("/%61ctuator/env");
        raw.setServletPath("/books/1");
        assertThat(source.getCorsConfiguration(raw)).isNull();
        // Spring MVC matches the raw segments, so a raw URI that only normalizes away from a management path still
        // reaches Actuator (/actuator/health/{*path}): Tomcat's mapped path is set as Tomcat maps it.
        assertThat(source.getCorsConfiguration(request("/actuator/../books/1"))).isNull();
        Map<String, String> escapes = Map
                .of("/actuator/health/../..", "/", "/actuator/health/%2e%2e/%2e%2e", "/",
                        "/actuator/health/x/../../../books", "/books", "/actuator/health/..;/..;/books", "/books");
        for (Map.Entry<String, String> escape : escapes.entrySet()) {
            for (String contextPath : List.of("", "/app")) {
                MockHttpServletRequest request = request(contextPath + escape.getKey());
                request.setContextPath(contextPath);
                request.setServletPath(escape.getValue());
                assertThat(source.getCorsConfiguration(request)).as(contextPath + escape.getKey()).isNull();
            }
        }
        // A normal path keeps the policy.
        assertThat(source.getCorsConfiguration(request("/books/1"))).isNotNull();
        MockHttpServletRequest books = request("/app/books/1");
        books.setContextPath("/app");
        books.setServletPath("/books/1");
        assertThat(source.getCorsConfiguration(books)).isNotNull();
    }

    @Test
    void errorDispatchOfAManagementRequestIsLeftToBoot() {
        SynapseCorsConfigurationSource source = SynapseCorsConfigurationSource
                .of(cors(List.of("https://app.example.com"), List.of(), false), "X-Correlation-Id",
                        List.of("/actuator"));

        // The error dispatch is to /error; the container reports the original URI, which decides.
        for (String original : List.of("/actuator/health", "/files/../actuator/env", "/actuator;x=1/env")) {
            assertThat(source.getCorsConfiguration(errorDispatch("", original))).as(original).isNull();
        }
        assertThat(source.getCorsConfiguration(errorDispatch("/ctx", "/ctx/actuator/health"))).isNull();
        // The error dispatch of an application path keeps the policy.
        assertThat(source.getCorsConfiguration(errorDispatch("", "/books/1"))).isNotNull();
        assertThat(source.getCorsConfiguration(errorDispatch("/ctx", "/ctx/books/1"))).isNotNull();
        // Only an error dispatch reads the attribute, and only a String.
        MockHttpServletRequest notAnErrorDispatch = request("/books/1");
        notAnErrorDispatch.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/actuator/health");
        assertThat(source.getCorsConfiguration(notAnErrorDispatch)).isNotNull();
        MockHttpServletRequest notAString = errorDispatch("", "/books/1");
        notAString.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, new StringBuilder("/actuator/health"));
        assertThat(source.getCorsConfiguration(notAString)).isNotNull();
        MockHttpServletRequest noAttribute = errorDispatch("", "/books/1");
        noAttribute.removeAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        assertThat(source.getCorsConfiguration(noAttribute)).isNotNull();
    }

    /**
     * Tomcat's spellings with {@code server.servlet.context-path=/ctx}: each original URI reaches Actuator. On the
     * request dispatch the context path is spelled as sent; on the error dispatch it is the canonical {@code /ctx}
     * while the original URI is the raw one.
     */
    private static final Map<String, String> CONTEXT_SPELLINGS = Map
            .of("/%63tx/actuator/env", "/%63tx", "/c%74x/actuator/env", "/c%74x", "//ctx/actuator/env", "/ctx",
                    "/foo/../ctx/actuator/env", "/foo/../ctx", "/ctx;x=1/actuator/env", "/ctx;x=1");

    @Test
    void errorDispatchOfAManagementRequestUnderAContextPathIsLeftToBoot() {
        SynapseCorsConfigurationSource source = SynapseCorsConfigurationSource
                .of(cors(List.of("https://app.example.com"), List.of(), false), "X-Correlation-Id",
                        List.of("/actuator"));

        for (Map.Entry<String, String> spelling : CONTEXT_SPELLINGS.entrySet()) {
            String original = spelling.getKey();
            // Without the request dispatch's decision (it failed before the CORS filter): the original URI decides.
            assertThat(source.getCorsConfiguration(errorDispatch("/ctx", original))).as(original).isNull();
            // After the request dispatch through the source.
            MockHttpServletRequest request = requestDispatch(spelling.getValue(), original, "/actuator/env");
            assertThat(source.getCorsConfiguration(request)).as(original).isNull();
            assertThat(request.getAttribute(SynapseCorsConfigurationSource.MANAGEMENT_ATTRIBUTE))
                    .as(original)
                    .isEqualTo(Boolean.TRUE);
            assertThat(source.getCorsConfiguration(toErrorDispatch(request, "/ctx", original))).as(original).isNull();
        }
        // Application paths, and a context path that only begins the same way, keep the policy.
        for (String original : List.of("/ctx/books/1", "/ctxfoo/actuator/env")) {
            assertThat(source.getCorsConfiguration(errorDispatch("/ctx", original))).as(original).isNotNull();
        }
        MockHttpServletRequest books = requestDispatch("/ctx", "/ctx/books/1", "/books/1");
        assertThat(source.getCorsConfiguration(books)).isNotNull();
        assertThat(books.getAttribute(SynapseCorsConfigurationSource.MANAGEMENT_ATTRIBUTE)).isNotEqualTo(Boolean.TRUE);
        assertThat(source.getCorsConfiguration(toErrorDispatch(books, "/ctx", "/ctx/books/1"))).isNotNull();
    }

    @Test
    void requestDispatchDecisionIsKeptForTheErrorDispatch() {
        SynapseCorsConfigurationSource source = SynapseCorsConfigurationSource
                .of(cors(List.of("https://app.example.com"), List.of(), false), "X-Correlation-Id",
                        List.of("/actuator"));
        // The container mapped the request to Actuator, whatever the original URI looks like.
        MockHttpServletRequest request = requestDispatch("/ctx", "/ctx/actuator/env", "/actuator/env");
        assertThat(source.getCorsConfiguration(request)).isNull();

        assertThat(source.getCorsConfiguration(toErrorDispatch(request, "/ctx", "/ctx/books/1"))).isNull();
        // A later dispatch to an application path doesn't undo it.
        request.setDispatcherType(DispatcherType.FORWARD);
        request.setRequestURI("/ctx/books/1");
        request.setServletPath("/books/1");
        assertThat(source.getCorsConfiguration(request)).isNotNull();
        assertThat(request.getAttribute(SynapseCorsConfigurationSource.MANAGEMENT_ATTRIBUTE)).isEqualTo(Boolean.TRUE);
    }

    /** Tomcat's request dispatch of the URI, with the context path spelled as sent and the mapped servlet path. */
    private static MockHttpServletRequest requestDispatch(String rawContextPath, String uri, String servletPath) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setContextPath(rawContextPath);
        request.setServletPath(servletPath);
        return request;
    }

    /** The same request, now on the container's error dispatch to {@code /error}. */
    private static MockHttpServletRequest toErrorDispatch(MockHttpServletRequest request, String contextPath,
            String originalUri) {
        request.setRequestURI(contextPath + "/error");
        request.setContextPath(contextPath);
        request.setServletPath("/error");
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, originalUri);
        return request;
    }

    /**
     * The container's error dispatch to {@code /error} of a request to the original URI. The context path is the
     * canonical one, as Tomcat gives it on the error dispatch; the original URI is as the caller sent it.
     */
    static MockHttpServletRequest errorDispatch(String contextPath, String originalUri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", contextPath + "/error");
        request.setContextPath(contextPath);
        request.setServletPath("/error");
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, originalUri);
        return request;
    }

    @Test
    void wildcardOriginWarnsOnce(CapturedOutput output) {
        SynapseCorsConfigurationSource source = SynapseCorsConfigurationSource
                .of(cors(List.of("*"), List.of(), false), "X-Correlation-Id", List.of());
        source.getCorsConfiguration(request("/books"));
        source.getCorsConfiguration(request("/books/1"));

        assertThat(output.getAll().lines().filter(line -> line.contains(WILDCARD_WARNING)))
                .singleElement()
                .satisfies(line -> assertThat(line).contains("WARN").contains("io.americanexpress.synapse.api.rest"));
        assertThat(configurationFor(source, "/books").getAllowedOrigins()).containsExactly("*");
    }

    @Test
    void exactOriginsDoNotWarn(CapturedOutput output) {
        SynapseCorsConfigurationSource
                .of(cors(List.of("https://app.example.com"), List.of(), false),
                        "X-Correlation-Id", List.of());

        assertThat(output.getAll()).doesNotContain("synapse.api.rest.cors.allowed-origins");
    }

    private static SynapseRestProperties.Cors cors(List<String> origins, List<String> exposed,
            boolean allowCredentials) {
        return new SynapseRestProperties.Cors(origins, List.of("GET", "HEAD", "POST", "PUT", "DELETE"), List.of("*"),
                exposed, allowCredentials, Duration.ofMinutes(30));
    }

    private static CorsConfiguration configurationFor(SynapseCorsConfigurationSource source, String path) {
        return Objects.requireNonNull(source.getCorsConfiguration(request(path)), path);
    }

    private static MockHttpServletRequest request(String path) {
        return new MockHttpServletRequest("GET", path);
    }
}
