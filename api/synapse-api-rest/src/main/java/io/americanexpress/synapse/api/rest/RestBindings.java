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
package io.americanexpress.synapse.api.rest;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpMethod;

import io.americanexpress.synapse.api.rest.internal.binding.RestBindingsAccess;
import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.core.operation.ServiceOperation;

/**
 * The REST routes of a service, declared as a bean:
 *
 * <pre class="code">
 * &#64;Bean
 * RestBindings books(GetBookOperation getBook, CreateBookOperation createBook) {
 *     return RestBindings.builder()
 *         .get("/books/{id}", getBook).notFound(BookErrors.BOOK_NOT_FOUND)
 *         .post("/books", createBook).created(book -&gt; "/books/" + book.id())
 *         .build();
 * }
 * </pre>
 *
 * The verb is always explicit. Building the bindings only records the declarations; they are resolved and checked
 * when the routes are created, and a binding problem fails startup.
 */
public final class RestBindings {

    static {
        // Synapse reads the routes through an internal class, so that they are not public API.
        RestBindingsAccess.registerRoutes(bindings -> bindings.routes);
    }

    private final List<RouteDefinition> routes;

    private RestBindings(List<RouteDefinition> routes) {
        this.routes = List.copyOf(routes);
    }

    /**
     * Starts declaring routes.
     *
     * @return a builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Declares routes, each started by its HTTP method.
     */
    public static final class Builder {

        private final List<RouteSpec<?, ?>> specs = new ArrayList<>();

        private Builder() {
        }

        /**
         * Declares a {@code GET} route.
         *
         * @param path the path template, for example {@code /books/{id}}
         * @param operation the operation
         * @param <I> the input type
         * @param <O> the output type
         * @return the route, to add options or declare the next route
         */
        public <I, O> RouteSpec<I, O> get(String path, ServiceOperation<I, O> operation) {
            return route(HttpMethod.GET, path, operation);
        }

        /**
         * Declares a {@code HEAD} route.
         *
         * @param path the path template
         * @param operation the operation
         * @param <I> the input type
         * @param <O> the output type
         * @return the route
         */
        public <I, O> RouteSpec<I, O> head(String path, ServiceOperation<I, O> operation) {
            return route(HttpMethod.HEAD, path, operation);
        }

        /**
         * Declares a {@code POST} route.
         *
         * @param path the path template
         * @param operation the operation
         * @param <I> the input type
         * @param <O> the output type
         * @return the route
         */
        public <I, O> RouteSpec<I, O> post(String path, ServiceOperation<I, O> operation) {
            return route(HttpMethod.POST, path, operation);
        }

        /**
         * Declares a {@code PUT} route.
         *
         * @param path the path template
         * @param operation the operation
         * @param <I> the input type
         * @param <O> the output type
         * @return the route
         */
        public <I, O> RouteSpec<I, O> put(String path, ServiceOperation<I, O> operation) {
            return route(HttpMethod.PUT, path, operation);
        }

        /**
         * Declares a {@code DELETE} route.
         *
         * @param path the path template
         * @param operation the operation
         * @param <I> the input type
         * @param <O> the output type
         * @return the route
         */
        public <I, O> RouteSpec<I, O> delete(String path, ServiceOperation<I, O> operation) {
            return route(HttpMethod.DELETE, path, operation);
        }

        /**
         * Builds the bindings.
         *
         * @return the bindings
         */
        public RestBindings build() {
            return new RestBindings(specs.stream().map(RouteSpec::definition).toList());
        }

        private <I, O> RouteSpec<I, O> route(HttpMethod method, String path, ServiceOperation<I, O> operation) {
            RouteSpec<I, O> spec = new RouteSpec<>(this, method, path, operation);
            specs.add(spec);
            return spec;
        }
    }
}
