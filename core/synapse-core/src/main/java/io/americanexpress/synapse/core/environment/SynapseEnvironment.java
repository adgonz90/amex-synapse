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
package io.americanexpress.synapse.core.environment;

import java.util.Collection;

/**
 * Tells whether the application runs in production. Non-production behaviour ("dev/test only") keys off this. The
 * check is fail-safe: a run counts as production unless one of its active profiles is listed in
 * {@code synapse.environment.non-production-profiles}, so a run with no active profile is production.
 */
@FunctionalInterface
public interface SynapseEnvironment {

    /**
     * Returns whether the application runs in production.
     *
     * @return {@code true} unless a non-production profile is active
     */
    boolean isProduction();

    /**
     * Returns an environment that is production unless one of the active profiles equals (case-sensitive) one of the
     * non-production profiles.
     *
     * @param activeProfiles the active profiles of the application
     * @param nonProductionProfiles the profiles that mark a run as non-production
     * @return the environment
     */
    static SynapseEnvironment fromProfiles(Collection<String> activeProfiles,
            Collection<String> nonProductionProfiles) {
        boolean production = activeProfiles.stream().noneMatch(nonProductionProfiles::contains);
        return () -> production;
    }
}
