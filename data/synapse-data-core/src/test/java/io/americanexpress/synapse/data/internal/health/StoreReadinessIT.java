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
package io.americanexpress.synapse.data.internal.health;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.actuate.endpoint.web.AdditionalPathsMapper;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.health.actuate.endpoint.AdditionalHealthEndpointPath;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import io.americanexpress.synapse.data.fixture.H2TestVendor;
import io.americanexpress.synapse.data.fixture.books.BooksStore;
import io.americanexpress.synapse.data.fixture.legacy.LegacyStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots an application with Boot's health endpoint and probes, a reachable {@code books} store and an unreachable
 * {@code legacy} store. Readiness reflects a store only when the store opts in, and Boot asks the readiness group about
 * a store by its path in the {@code db} contributor ({@code db/legacy}).
 */
@SpringBootTest(classes = StoreReadinessIT.TestApplication.class, properties = {
        "management.endpoint.health.probes.enabled=true", "management.endpoint.health.probes.add-additional-paths=true",
        "synapse.data.stores.books.url=jdbc:h2:mem:readiness-books;DB_CLOSE_DELAY=-1",
        "synapse.data.stores.books.username=sa", "synapse.data.stores.books.password=",
        "synapse.data.stores.legacy.url=jdbc:h2:tcp://127.0.0.1:1/none", "synapse.data.stores.legacy.username=sa",
        "synapse.data.stores.legacy.password=", "synapse.data.stores.legacy.hikari.connection-timeout=250",
        "synapse.data.stores.legacy.migrations.enabled=false"})
@ActiveProfiles("test")
class StoreReadinessIT {

    @Autowired
    private HealthEndpoint health;

    @Autowired
    private HealthEndpointGroups groups;

    @Test
    void readinessIgnoresAStoreThatDidNotOptIn() {
        assertThat(status(health, "db", "legacy")).isEqualTo(Status.DOWN);
        assertThat(status(health, "db", "books")).isEqualTo(Status.UP);
        assertThat(status(health, "readiness")).isEqualTo(Status.UP);
        assertThat(components(health, "readiness")).containsOnlyKeys("readinessState");
        assertThat(Objects.requireNonNull(groups.get("readiness")).isMember("db/legacy")).isFalse();
    }

    @Nested
    @TestPropertySource(properties = "synapse.data.stores.legacy.health.readiness=true")
    class OptedIn {

        @Autowired
        private HealthEndpoint health;

        @Autowired
        private HealthEndpointGroups groups;

        @Autowired
        private ApplicationContext context;

        @Test
        void readinessIncludesAnOptedInStore() {
            assertThat(status(health, "readiness")).isEqualTo(Status.DOWN);
            assertThat(components(health, "readiness")).containsOnlyKeys("db", "readinessState");
            assertThat(components(health, "readiness", "db")).containsOnlyKeys("legacy");
            assertThat(status(health, "liveness")).isEqualTo(Status.UP);
        }

        /** Boot's probe groups are built first, and Synapse adds the store to the readiness group they built. */
        @Test
        void theStoreJoinsBootsReadinessGroupByItsPath() {
            assertThat(Objects.requireNonNull(groups.get("readiness")).isMember("db/legacy")).isTrue();
            assertThat(Objects.requireNonNull(groups.get("readiness")).isMember("db/books")).isFalse();
            assertThat(Objects.requireNonNull(groups.get("liveness")).isMember("db/legacy")).isFalse();
            assertThat(groups.getPrimary().isMember("db/legacy")).isTrue();
        }

        /** The probes' additional paths stay mapped, and {@code /readyz} serves the readiness group with the store. */
        @Test
        void readyzKeepsTheStore() {
            List<List<String>> paths = new ArrayList<>();
            for (AdditionalPathsMapper mapper : context.getBeanProvider(AdditionalPathsMapper.class)) {
                List<String> mapped = mapper.getAdditionalPaths(HealthEndpoint.ID, WebServerNamespace.SERVER);
                if (mapped != null) {
                    paths.add(mapped);
                }
            }
            assertThat(paths).containsExactly(List.of("/livez", "/readyz"));
            assertThat(Objects
                    .requireNonNull(groups.get(AdditionalHealthEndpointPath.of(WebServerNamespace.SERVER, "/readyz")))
                    .isMember("db/legacy")).isTrue();
            assertThat(Objects
                    .requireNonNull(groups.get(AdditionalHealthEndpointPath.of(WebServerNamespace.SERVER, "/livez")))
                    .isMember("db/legacy")).isFalse();
        }
    }

    private static Status status(HealthEndpoint health, String... path) {
        return Objects.requireNonNull(health.healthForPath(path), "health at " + String.join("/", path)).getStatus();
    }

    private static Map<String, HealthDescriptor> components(HealthEndpoint health, String... path) {
        String name = String.join("/", path);
        HealthDescriptor descriptor = Objects.requireNonNull(health.healthForPath(path), "health at " + name);
        return Objects
                .requireNonNull(((CompositeHealthDescriptor) descriptor).getComponents(), "components of " + name);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({H2TestVendor.class, BooksStore.class, LegacyStore.class})
    static class TestApplication {
    }
}
