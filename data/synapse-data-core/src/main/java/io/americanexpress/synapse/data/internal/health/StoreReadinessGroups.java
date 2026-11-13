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

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.EndpointId;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.endpoint.web.AdditionalPathsMapper;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.health.actuate.endpoint.AdditionalHealthEndpointPath;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroupsPostProcessor;
import org.springframework.boot.health.actuate.endpoint.HttpCodeStatusMapper;
import org.springframework.boot.health.actuate.endpoint.StatusAggregator;

/**
 * Adds the stores that set {@code synapse.data.stores.<name>.health.readiness=true} to the {@code readiness} group,
 * by their path in the {@code db} contributor ({@code db/<name>}), which is how Boot asks a group about a composite's
 * child. Every other setting of the group, and every other group, stays as it is. Without a {@code readiness} group
 * (probes turned off) there is nothing to add to.
 * <p>
 * It has no order, so it runs after Boot's probe post-processor. That post-processor adds Boot's {@code readiness}
 * group (member {@code readinessState}) only when the application doesn't define one, and otherwise keeps the
 * application's group as it is. Setting any {@code management.endpoint.health.group.readiness.*} key, such as an
 * {@code additional-path} of {@code server:/health}, defines the group, and a group without {@code include} has every
 * indicator, every store's included. So that setup needs {@code include: readinessState} too; the stores that opt in
 * are then added to it here. The groups it returns keep the additional paths, such as {@code /readyz} or
 * {@code /health}.
 */
public final class StoreReadinessGroups implements HealthEndpointGroupsPostProcessor {

    /** The bean name of the post-processor. */
    public static final String BEAN_NAME = "synapseStoreReadinessGroups";

    /** The group the stores join. */
    static final String READINESS = "readiness";

    private final Set<String> members;

    /**
     * Creates the post-processor.
     *
     * @param members the paths of the stores that join the readiness group, such as {@code db/books}
     */
    public StoreReadinessGroups(Collection<String> members) {
        this.members = Collections.unmodifiableSet(new LinkedHashSet<>(members));
    }

    /**
     * Returns the paths of the stores that join the readiness group.
     *
     * @return the paths, such as {@code db/books}
     */
    public Set<String> members() {
        return members;
    }

    @Override
    public HealthEndpointGroups postProcessHealthEndpointGroups(HealthEndpointGroups groups) {
        return members.isEmpty() ? groups : new Groups(groups, members);
    }

    /** The groups, with the readiness group extended. */
    private static final class Groups implements HealthEndpointGroups, AdditionalPathsMapper {

        private final HealthEndpointGroups groups;

        private final Set<String> members;

        Groups(HealthEndpointGroups groups, Set<String> members) {
            this.groups = groups;
            this.members = members;
        }

        @Override
        public HealthEndpointGroup getPrimary() {
            return groups.getPrimary();
        }

        @Override
        public Set<String> getNames() {
            return groups.getNames();
        }

        @Override
        public @Nullable HealthEndpointGroup get(String name) {
            HealthEndpointGroup group = groups.get(name);
            return group != null && READINESS.equals(name) ? new Readiness(group, members) : group;
        }

        @Override
        public @Nullable List<String> getAdditionalPaths(EndpointId endpointId,
                WebServerNamespace webServerNamespace) {
            return groups instanceof AdditionalPathsMapper mapper
                    ? mapper.getAdditionalPaths(endpointId, webServerNamespace)
                    : null;
        }
    }

    /** The readiness group, with the stores as members too. */
    private static final class Readiness implements HealthEndpointGroup {

        private final HealthEndpointGroup group;

        private final Set<String> members;

        Readiness(HealthEndpointGroup group, Set<String> members) {
            this.group = group;
            this.members = members;
        }

        @Override
        public boolean isMember(String name) {
            return members.contains(name) || group.isMember(name);
        }

        @Override
        public boolean showComponents(SecurityContext securityContext) {
            return group.showComponents(securityContext);
        }

        @Override
        public boolean showDetails(SecurityContext securityContext) {
            return group.showDetails(securityContext);
        }

        @Override
        public StatusAggregator getStatusAggregator() {
            return group.getStatusAggregator();
        }

        @Override
        public HttpCodeStatusMapper getHttpCodeStatusMapper() {
            return group.getHttpCodeStatusMapper();
        }

        @Override
        public @Nullable AdditionalHealthEndpointPath getAdditionalPath() {
            return group.getAdditionalPath();
        }
    }
}
