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

    private static List<String> paths(String... properties) {
        MockEnvironment environment = new MockEnvironment();
        for (int i = 0; i < properties.length; i += 2) {
            environment.setProperty(properties[i], properties[i + 1]);
        }
        return ManagementPaths.excludedPrefixes(environment, true);
    }
}
