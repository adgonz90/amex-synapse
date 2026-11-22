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
package io.americanexpress.synapse.api.rest.internal.binding;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import io.americanexpress.synapse.api.rest.RestBindings;
import io.americanexpress.synapse.api.rest.RestConventions;

/**
 * Synapse's access to the members of {@link RestBindings} and {@link RestConventions} that are not public API: the
 * routes and the rules as declared. Each class registers its access when it is initialized, which is before any
 * instance exists, so every call that passes bindings or conventions finds it.
 */
public final class RestBindingsAccess {

    private static volatile @Nullable Function<RestBindings, List<RouteDefinition>> routes;

    private static volatile @Nullable Function<RestConventions, ConventionRules> rules;

    private RestBindingsAccess() {
    }

    /**
     * Registers the access to the routes. {@code RestBindings} calls it once, when its class is initialized.
     *
     * @param access returns the routes of bindings as declared
     * @throws IllegalStateException when the access is already registered
     */
    public static synchronized void registerRoutes(Function<RestBindings, List<RouteDefinition>> access) {
        if (routes != null) {
            throw new IllegalStateException("The RestBindings access is already registered");
        }
        routes = access;
    }

    /**
     * Registers the access to the rules. {@code RestConventions} calls it once, when its class is initialized.
     *
     * @param access returns the rules of conventions as declared
     * @throws IllegalStateException when the access is already registered
     */
    public static synchronized void registerRules(Function<RestConventions, ConventionRules> access) {
        if (rules != null) {
            throw new IllegalStateException("The RestConventions access is already registered");
        }
        rules = access;
    }

    /**
     * Returns the routes as declared, in declaration order.
     *
     * @param bindings the bindings
     * @return the routes
     */
    public static List<RouteDefinition> routes(RestBindings bindings) {
        return Objects
                .requireNonNull(routes, "RestBindings registers its access when its class is initialized")
                .apply(bindings);
    }

    /**
     * Returns the rules as declared.
     *
     * @param conventions the conventions
     * @return the rules
     */
    public static ConventionRules rules(RestConventions conventions) {
        return Objects
                .requireNonNull(rules, "RestConventions registers its access when its class is initialized")
                .apply(conventions);
    }
}
