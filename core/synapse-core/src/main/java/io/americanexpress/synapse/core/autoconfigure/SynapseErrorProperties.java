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
package io.americanexpress.synapse.core.autoconfigure;

import java.net.URI;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

import io.americanexpress.synapse.core.environment.SynapseEnvironment;

/**
 * Error-response settings shared by every Synapse API plugin.
 *
 * @param includeDetail Whether error responses include the formatted detail message. When unset, it is on outside
 *     production and off in production (see synapse.environment.non-production-profiles); an explicit value always
 *     wins. Detail messages can contain sensitive data; the full detail is always logged server-side, and stack
 *     traces are never returned.
 * @param typeBaseUri Base URI that error type URIs are built from. Unset by default.
 */
@ConfigurationProperties("synapse.errors")
public record SynapseErrorProperties(@Nullable Boolean includeDetail, @Nullable URI typeBaseUri) {

    /**
     * Returns whether error responses include the detail message: the explicit {@code include-detail} value when set,
     * otherwise {@code true} outside production and {@code false} in production.
     *
     * @param environment the environment, to tell production from non-production runs
     * @return whether to include the detail
     */
    public boolean includeDetail(SynapseEnvironment environment) {
        return includeDetail != null ? includeDetail : !environment.isProduction();
    }
}
