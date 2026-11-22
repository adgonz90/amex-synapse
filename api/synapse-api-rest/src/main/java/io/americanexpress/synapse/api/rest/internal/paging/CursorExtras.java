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
package io.americanexpress.synapse.api.rest.internal.paging;

import java.util.Map;

import org.springframework.web.accept.SemanticApiVersionParser;

import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;

/**
 * The entries a REST route adds to its cursor scope, beside the input's filters (core's {@code InputDigest}): on a
 * versioned route, {@code @version} with the route's version parsed by Spring's {@link SemanticApiVersionParser}, so a
 * cursor issued in one version is rejected in another (versions may differ in their sort keys and item shape); none on
 * an unversioned route. Versions that parse equal ({@code 2} and {@code 2.0}) are written alike, as
 * {@code major.minor.patch}, so routes of one operation in those versions share their cursors.
 */
public final class CursorExtras {

    /** The key of a versioned route's version; it starts with '@', which no record component name can. */
    static final String VERSION_KEY = "@version";

    private static final SemanticApiVersionParser VERSION_PARSER = new SemanticApiVersionParser();

    private CursorExtras() {
    }

    /**
     * Returns the route's extra cursor scope entries.
     *
     * @param route the resolved route
     * @return {@code {"@version": "<major.minor.patch>"}} on a versioned route, otherwise empty
     */
    public static Map<String, String> of(RouteDefinition route) {
        String version = route.version();
        return version == null ? Map.of() : Map.of(VERSION_KEY, VERSION_PARSER.parseVersion(version).toString());
    }
}
