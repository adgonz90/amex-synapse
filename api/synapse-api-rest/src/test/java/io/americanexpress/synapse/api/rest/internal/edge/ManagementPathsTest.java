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
package io.americanexpress.synapse.api.rest.internal.edge;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ManagementPathsTest {

    @Test
    void defaultsAndAdditionalPaths() {
        assertThat(ManagementPaths.excludedPrefixes(new MockEnvironment(), true)).containsExactly("/actuator");
        assertThat(paths("management.endpoints.web.base-path", "/manage")).containsExactly("/manage");
        assertThat(paths("management.server.port", "9000", "server.port", "8080")).isEmpty();
        assertThat(paths("management.endpoint.health.group.readiness.additional-path", "server:/health"))
                .contains("/health");
    }

    @Test
    void managementPortFollowsBootRules() {
        // Same port as the server: the endpoints are served by the application's server.
        assertThat(paths("management.server.port", "8080", "server.port", "8080")).containsExactly("/actuator");
        // Without server.port, 8080 is the server's port.
        assertThat(paths("management.server.port", "8080")).containsExactly("/actuator");
        assertThat(paths("management.server.port", "9000")).isEmpty();
        // Port 0 is a random port of its own, even when the server's is random too.
        assertThat(paths("management.server.port", "0", "server.port", "0")).isEmpty();
        // A negative port disables the management endpoints over HTTP.
        assertThat(paths("management.server.port", "-1")).isEmpty();
    }

    @Test
    void basePathIsCleanedAsBootDoes() {
        assertThat(paths("management.endpoints.web.base-path", "/manage/")).containsExactly("/manage");
        // The root base path maps every endpoint at the root: there is no prefix that holds only them.
        assertThat(paths("management.endpoints.web.base-path", "/")).isEmpty();
        assertThat(paths("management.endpoints.web.base-path", "")).isEmpty();
    }

    @Test
    void serverAdditionalPathsOnly() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("management.endpoint.health.group.readiness.additional-path", "server:/ready")
                .withProperty("management.endpoint.health.group.liveness.additional-path", "server:live/")
                .withProperty("management.endpoint.health.group.custom.additional-path", "management:/custom")
                .withProperty("management.endpoint.health.group.other.include", "db");

        assertThat(ManagementPaths.excludedPrefixes(environment, true))
                .containsExactlyInAnyOrder("/actuator", "/ready", "/live");
    }

    @Test
    void serverAdditionalPathsStayWithARootBasePath() {
        // The endpoints are at the root and have no prefix, but a health group's server: path is still Actuator's.
        assertThat(paths("management.endpoints.web.base-path", "/",
                "management.endpoint.health.group.readiness.additional-path", "server:/healthz"))
                .containsExactly("/healthz");
    }

    @Test
    void serverAdditionalPathsStayWhenManagementHasItsOwnPort() {
        assertThat(paths("management.server.port", "9000", "management.endpoint.health.group.readiness.additional-path",
                "server:/health")).containsExactly("/health");
    }

    @Test
    void dispatcherServletPathPrefixesEveryPath() {
        assertThat(
                paths("spring.mvc.servlet.path", "/api", "management.endpoint.health.group.readiness.additional-path",
                        "server:/health"))
                .containsExactlyInAnyOrder("/api/actuator", "/api/health");
        assertThat(paths("spring.mvc.servlet.path", "/api/")).containsExactly("/api/actuator");
        assertThat(paths("spring.mvc.servlet.path", "/")).containsExactly("/actuator");
    }

    @Test
    void nothingIsExcludedWithoutActuator() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("management.endpoint.health.group.readiness.additional-path", "server:/health");

        assertThat(ManagementPaths.excludedPrefixes(environment, false)).isEmpty();
        // Actuator is not on this module's classpath.
        assertThat(ManagementPaths.excludedPrefixes(environment, getClass().getClassLoader())).isEmpty();
        assertThat(ManagementPaths
                .excludedPrefixes(environment,
                        new FilteredClassLoader("org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier")))
                .isEmpty();
    }

    @Test
    void managementRequestNeedsTheRawAndTheMappedPathUnderOnePrefix() {
        List<String> prefixes = List.of("/actuator", "/health");

        assertThat(ManagementPaths.isManagementRequest(new MockHttpServletRequest("GET", "/actuator/health"), prefixes))
                .isTrue();
        assertThat(ManagementPaths.isManagementRequest(new MockHttpServletRequest("GET", "/health"), prefixes))
                .isTrue();
        assertThat(ManagementPaths.isManagementRequest(new MockHttpServletRequest("GET", "/healthy"), prefixes))
                .isFalse();
        assertThat(ManagementPaths.isManagementRequest(new MockHttpServletRequest("GET", "/actuator"), List.of()))
                .isFalse();
        MockHttpServletRequest traversal = new MockHttpServletRequest("GET", "/app/files/../actuator/health");
        traversal.setContextPath("/app");
        traversal.setServletPath("/actuator/health");
        assertThat(ManagementPaths.isManagementRequest(traversal, prefixes)).isFalse();
    }

    @Test
    void eitherPathUnderAPrefixIsEnough() {
        List<String> prefixes = List.of("/actuator");

        assertThat(ManagementPaths.isUnderEitherPath(new MockHttpServletRequest("GET", "/actuator/env"), List.of()))
                .isFalse();
        for (String uri : List
                .of("/actuator", "/actuator/", "/files/../actuator/health", "/%61ctuator/env",
                        "/actuator;x/env", "/actuator;jsessionid=1", "//actuator/env", "/./actuator/env",
                        "/../actuator/env",
                        "/files/%2e%2e/actuator/env", "/app/actuator/env",
                        // Spring MVC matches these raw segments against /actuator/health/{*path}.
                        "/actuator/../books/1", "/actuator/health/../..", "/actuator/health/%2e%2e/%2e%2e",
                        "/actuator/health/x/../../../books", "/actuator/health/..;/..;/books",
                        "/app/actuator/health/../..", "/app/actuator/health/%2e%2e/%2e%2e",
                        "/app/actuator/health/x/../../../books", "/app/actuator/health/..;/..;/books")) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
            if (uri.startsWith("/app")) {
                request.setContextPath("/app");
            }
            assertThat(ManagementPaths.isUnderEitherPath(request, prefixes)).as(uri).isTrue();
        }
        // As Tomcat maps them: the mapped path and the normalized raw URI are both outside the prefix.
        MockHttpServletRequest escaped = new MockHttpServletRequest("GET", "/app/actuator/health/x/../../../books");
        escaped.setContextPath("/app");
        escaped.setServletPath("/books");
        assertThat(ManagementPaths.isUnderEitherPath(escaped, prefixes)).isTrue();
        for (String uri : List.of("/", "/books/1", "/actuatorx", "/files/actuator/env", "/books;v=1/7")) {
            assertThat(ManagementPaths.isUnderEitherPath(new MockHttpServletRequest("GET", uri), prefixes))
                    .as(uri)
                    .isFalse();
        }
        // The mapped path alone, the raw URI alone.
        MockHttpServletRequest mapped = new MockHttpServletRequest("GET", "/books/1");
        mapped.setServletPath("/actuator");
        mapped.setPathInfo("/env");
        assertThat(ManagementPaths.isUnderEitherPath(mapped, prefixes)).isTrue();
        MockHttpServletRequest raw = new MockHttpServletRequest("GET", "/files/../actuator/env");
        raw.setServletPath("/books/1");
        assertThat(ManagementPaths.isUnderEitherPath(raw, prefixes)).isTrue();
        // An invalid escape is matched undecoded.
        assertThat(ManagementPaths.isUnderEitherPath(new MockHttpServletRequest("GET", "/actuator/%zz"), prefixes))
                .isTrue();
        assertThat(ManagementPaths.isUnderEitherPath(new MockHttpServletRequest("GET", "/books/%zz"), prefixes))
                .isFalse();
    }

    @Test
    void rawPathUnderAPrefixInAnyViewIsEnough() {
        List<String> prefixes = List.of("/actuator");

        for (String raw : List
                .of("/actuator", "/actuator/health", "/files/../actuator/env", "/actuator;x=1/env", "/%61ctuator/env",
                        "//actuator/env", "/actuator/health/../..")) {
            assertThat(ManagementPaths.isUnderEitherPath(raw, prefixes)).as(raw).isTrue();
        }
        for (String raw : List.of("/books/1", "/actuatorx", "/files/actuator/health", "/books/%zz", "/")) {
            assertThat(ManagementPaths.isUnderEitherPath(raw, prefixes)).as(raw).isFalse();
        }
        assertThat(ManagementPaths.isUnderEitherPath("/actuator/health", List.of())).isFalse();
    }

    @Test
    void originalUriIsCanonicalizedWithTheContextPath() {
        List<String> prefixes = List.of("/actuator");

        // Tomcat routes each of these to the /ctx context, and its error dispatch reports the context path as /ctx.
        for (String original : List
                .of("/ctx/actuator/env", "/%63tx/actuator/env", "/c%74x/actuator/env", "//ctx/actuator/env",
                        "/foo/../ctx/actuator/env", "/ctx;x=1/actuator/env", "/ctx/./actuator/env",
                        "/ctx//actuator/env", "/ctx/%61ctuator/env", "/ctx/books/../actuator/env", "/ctx/actuator")) {
            assertThat(ManagementPaths.isOriginalUnderEitherPath(original, "/ctx", prefixes)).as(original).isTrue();
        }
        for (String original : List.of("/ctx/books/1", "/ctxfoo/actuator/env", "/ctx/actuatorx", "/ctx")) {
            assertThat(ManagementPaths.isOriginalUnderEitherPath(original, "/ctx", prefixes)).as(original).isFalse();
        }
        // A context path spelled like the original is canonicalized too.
        assertThat(ManagementPaths.isOriginalUnderEitherPath("/c%74x/actuator/env", "/%63tx", prefixes)).isTrue();
        // At the root, the original is a path within the application.
        assertThat(ManagementPaths.isOriginalUnderEitherPath("/files/../actuator/env", "", prefixes)).isTrue();
        assertThat(ManagementPaths.isOriginalUnderEitherPath("/books/1", "", prefixes)).isFalse();
        // Failing closed: a view under a prefix counts with or without the context path.
        assertThat(ManagementPaths.isOriginalUnderEitherPath("/actuator/env", "/ctx", prefixes)).isTrue();
        assertThat(ManagementPaths.isOriginalUnderEitherPath("/ctx/actuator/env", "/ctx", List.of())).isFalse();
    }

    private static List<String> paths(String... properties) {
        MockEnvironment environment = new MockEnvironment();
        for (int i = 0; i < properties.length; i += 2) {
            environment.setProperty(properties[i], properties[i + 1]);
        }
        return ManagementPaths.excludedPrefixes(environment, true);
    }
}
