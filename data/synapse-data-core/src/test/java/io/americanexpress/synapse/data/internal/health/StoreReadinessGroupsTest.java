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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.EndpointId;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.web.AdditionalPathsMapper;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.health.actuate.endpoint.AdditionalHealthEndpointPath;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.actuate.endpoint.HttpCodeStatusMapper;
import org.springframework.boot.health.actuate.endpoint.StatusAggregator;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;

class StoreReadinessGroupsTest {

    private static final AdditionalHealthEndpointPath READYZ = AdditionalHealthEndpointPath
            .of(WebServerNamespace.SERVER, "/readyz");

    private final Group primary = new Group(Set.of("db/books", "db/legacy", "readinessState"), null);

    private final Group readiness = new Group(Set.of("readinessState"), READYZ);

    private final Group liveness = new Group(Set.of("livenessState"), null);

    private final Groups groups = new Groups(primary, Map.of("readiness", readiness, "liveness", liveness));

    @Test
    void anOptedInStoreJoinsTheReadinessGroup() {
        HealthEndpointGroups processed = new StoreReadinessGroups(List.of("db/legacy"))
                .postProcessHealthEndpointGroups(groups);

        HealthEndpointGroup group = Objects.requireNonNull(processed.get("readiness"));
        assertThat(group.isMember("db/legacy")).isTrue();
        assertThat(group.isMember("readinessState")).isTrue();
        assertThat(group.isMember("db/books")).isFalse();
        assertThat(group.isMember("db")).isFalse();
    }

    @Test
    void theReadinessGroupKeepsItsSettings() {
        HealthEndpointGroup group = Objects
                .requireNonNull(new StoreReadinessGroups(List.of("db/legacy"))
                        .postProcessHealthEndpointGroups(groups)
                        .get("readiness"));

        assertThat(group.showComponents(SecurityContext.NONE)).isTrue();
        assertThat(group.showDetails(SecurityContext.NONE)).isFalse();
        assertThat(group.getStatusAggregator()).isSameAs(readiness.aggregator);
        assertThat(group.getHttpCodeStatusMapper()).isSameAs(readiness.mapper);
        assertThat(group.getAdditionalPath()).isEqualTo(READYZ);
    }

    @Test
    void otherGroupsAreUnchanged() {
        HealthEndpointGroups processed = new StoreReadinessGroups(List.of("db/legacy"))
                .postProcessHealthEndpointGroups(groups);

        assertThat(processed.getPrimary()).isSameAs(primary);
        assertThat(processed.get("liveness")).isSameAs(liveness);
        assertThat(processed.get("other")).isNull();
        assertThat(processed.getNames()).containsExactlyInAnyOrder("readiness", "liveness");
        assertThat(processed.get(READYZ)).isNotNull();
    }

    @Test
    void theAdditionalPathsAreKept() {
        HealthEndpointGroups processed = new StoreReadinessGroups(List.of("db/legacy"))
                .postProcessHealthEndpointGroups(groups);

        assertThat(processed).isInstanceOf(AdditionalPathsMapper.class);
        AdditionalPathsMapper mapper = (AdditionalPathsMapper) processed;
        assertThat(mapper.getAdditionalPaths(EndpointId.of("health"), WebServerNamespace.SERVER))
                .containsExactly("/readyz");
        HealthEndpointGroups plain = HealthEndpointGroups.of(primary, Map.of("readiness", readiness));
        assertThat(((AdditionalPathsMapper) new StoreReadinessGroups(List.of("db/legacy"))
                .postProcessHealthEndpointGroups(plain))
                .getAdditionalPaths(EndpointId.of("health"), WebServerNamespace.SERVER)).isNull();
    }

    @Test
    void withoutAReadinessGroupThereIsNothingToJoin() {
        HealthEndpointGroups withoutProbes = HealthEndpointGroups.of(primary, Map.of());

        HealthEndpointGroups processed = new StoreReadinessGroups(List.of("db/legacy"))
                .postProcessHealthEndpointGroups(withoutProbes);

        assertThat(processed.get("readiness")).isNull();
        assertThat(processed.getNames()).isEmpty();
    }

    @Test
    void withoutAnOptedInStoreTheGroupsAreReturnedAsTheyAre() {
        StoreReadinessGroups none = new StoreReadinessGroups(List.of());

        assertThat(none.members()).isEmpty();
        assertThat(none.postProcessHealthEndpointGroups(groups)).isSameAs(groups);
    }

    /** A group with fixed members. */
    static final class Group implements HealthEndpointGroup {

        final StatusAggregator aggregator = statuses -> Status.UP;

        final HttpCodeStatusMapper mapper = status -> 200;

        private final Set<String> members;

        private final @Nullable AdditionalHealthEndpointPath path;

        Group(Set<String> members, @Nullable AdditionalHealthEndpointPath path) {
            this.members = members;
            this.path = path;
        }

        @Override
        public boolean isMember(String name) {
            return members.contains(name);
        }

        @Override
        public boolean showComponents(SecurityContext securityContext) {
            return true;
        }

        @Override
        public boolean showDetails(SecurityContext securityContext) {
            return false;
        }

        @Override
        public StatusAggregator getStatusAggregator() {
            return aggregator;
        }

        @Override
        public HttpCodeStatusMapper getHttpCodeStatusMapper() {
            return mapper;
        }

        @Override
        public @Nullable AdditionalHealthEndpointPath getAdditionalPath() {
            return path;
        }
    }

    /** Groups that map their additional paths, as Boot's do. */
    static final class Groups implements HealthEndpointGroups, AdditionalPathsMapper {

        private final HealthEndpointGroup primary;

        private final Map<String, HealthEndpointGroup> groups;

        Groups(HealthEndpointGroup primary, Map<String, HealthEndpointGroup> groups) {
            this.primary = primary;
            this.groups = groups;
        }

        @Override
        public HealthEndpointGroup getPrimary() {
            return primary;
        }

        @Override
        public Set<String> getNames() {
            return groups.keySet();
        }

        @Override
        public @Nullable HealthEndpointGroup get(String name) {
            return groups.get(name);
        }

        @Override
        public @Nullable List<String> getAdditionalPaths(EndpointId endpointId,
                WebServerNamespace webServerNamespace) {
            return List.of("/readyz");
        }
    }
}
