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
import java.util.function.Function;
import java.util.function.ToIntFunction;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.util.unit.DataSize;
import org.springframework.web.servlet.function.ServerRequest;

import io.americanexpress.synapse.api.rest.internal.binding.ResponseRules;
import io.americanexpress.synapse.api.rest.internal.binding.RouteDeclaration;
import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.core.binding.ComponentRef;
import io.americanexpress.synapse.core.context.RequestContext;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.operation.ServiceOperation;

/**
 * One route being declared on {@link RestBindings}: its input bindings and response options. Each call only records
 * the declaration; the bindings are resolved and checked when the routes are created, so a mistake fails startup with
 * the route named.
 *
 * <p>
 * Input components with no declaration get, in this order: a {@link RestConventions} rule for their type; the path
 * variable of the same name; on {@code GET}/{@code HEAD}, the query parameter of the same name for strings, numbers,
 * booleans, enums, {@code java.time} types and lists of these; on {@code POST}/{@code PUT}, the JSON body property.
 * A {@code PageQuery} component, or a {@code PageQuery} input, always binds from the paging query parameters
 * ({@code synapse.api.rest.paging.parameters}) on every method, and can't be declared or bound by a convention.
 *
 * @param <I> the operation's input type
 * @param <O> the operation's output type
 */
public final class RouteSpec<I, O> {

    private final RestBindings.Builder bindings;

    private final HttpMethod method;

    private final String path;

    private final ServiceOperation<I, O> operation;

    private final List<RouteDeclaration> declarations = new ArrayList<>();

    private @Nullable Integer status;

    private @Nullable ToIntFunction<Object> statusFunction;

    private @Nullable Function<Object, String> created;

    private @Nullable Function<Object, String> accepted;

    private @Nullable ErrorDetails notFound;

    private @Nullable Integer emptyAs;

    private @Nullable DataSize maxRequestSize;

    RouteSpec(RestBindings.Builder bindings, HttpMethod method, String path, ServiceOperation<I, O> operation) {
        this.bindings = bindings;
        this.method = method;
        this.path = path;
        this.operation = operation;
    }

    /**
     * Declares the next route, a {@code GET}.
     *
     * @param path the path template
     * @param operation the operation
     * @param <I2> the input type
     * @param <O2> the output type
     * @return the next route
     */
    public <I2, O2> RouteSpec<I2, O2> get(String path, ServiceOperation<I2, O2> operation) {
        return bindings.get(path, operation);
    }

    /**
     * Declares the next route, a {@code HEAD}.
     *
     * @param path the path template
     * @param operation the operation
     * @param <I2> the input type
     * @param <O2> the output type
     * @return the next route
     */
    public <I2, O2> RouteSpec<I2, O2> head(String path, ServiceOperation<I2, O2> operation) {
        return bindings.head(path, operation);
    }

    /**
     * Declares the next route, a {@code POST}.
     *
     * @param path the path template
     * @param operation the operation
     * @param <I2> the input type
     * @param <O2> the output type
     * @return the next route
     */
    public <I2, O2> RouteSpec<I2, O2> post(String path, ServiceOperation<I2, O2> operation) {
        return bindings.post(path, operation);
    }

    /**
     * Declares the next route, a {@code PUT}.
     *
     * @param path the path template
     * @param operation the operation
     * @param <I2> the input type
     * @param <O2> the output type
     * @return the next route
     */
    public <I2, O2> RouteSpec<I2, O2> put(String path, ServiceOperation<I2, O2> operation) {
        return bindings.put(path, operation);
    }

    /**
     * Declares the next route, a {@code DELETE}.
     *
     * @param path the path template
     * @param operation the operation
     * @param <I2> the input type
     * @param <O2> the output type
     * @return the next route
     */
    public <I2, O2> RouteSpec<I2, O2> delete(String path, ServiceOperation<I2, O2> operation) {
        return bindings.delete(path, operation);
    }

    /**
     * Builds the bindings.
     *
     * @return the bindings
     */
    public RestBindings build() {
        return bindings.build();
    }

    /**
     * Binds an input component from a path variable with a different name.
     *
     * @param variable the template variable
     * @param component the input component
     * @return this route
     */
    public RouteSpec<I, O> path(String variable, ComponentRef<I, ?> component) {
        return declare(new RouteDeclaration.Path(variable, component));
    }

    /**
     * Binds an input component from the query parameter of the same name, on any method. A record-typed component is
     * built from the parameters named after its components.
     *
     * @param component the input component
     * @return this route
     */
    public RouteSpec<I, O> query(ComponentRef<I, ?> component) {
        return declare(new RouteDeclaration.Query(null, component));
    }

    /**
     * Binds an input component from a query parameter with a different name, on any method.
     *
     * @param parameter the parameter name
     * @param component the input component
     * @return this route
     */
    public RouteSpec<I, O> query(String parameter, ComponentRef<I, ?> component) {
        return declare(new RouteDeclaration.Query(parameter, component));
    }

    /**
     * Binds an input component from a cookie.
     *
     * @param name the cookie name
     * @param component the input component
     * @return this route
     */
    public RouteSpec<I, O> cookie(String name, ComponentRef<I, ?> component) {
        return declare(new RouteDeclaration.Cookie(name, component));
    }

    /**
     * Binds an input component from the Synapse request context.
     *
     * @param component the input component
     * @param value reads the value from the context
     * @param <P> the component type
     * @return this route
     */
    public <P extends @Nullable Object> RouteSpec<I, O> context(ComponentRef<I, P> component,
            Function<RequestContext, ? extends @Nullable P> value) {
        return declare(new RouteDeclaration.Context(component, value));
    }

    /**
     * Binds an input component with application code that reads the request.
     *
     * @param component the input component
     * @param value reads the value from the request
     * @param <P> the component type
     * @return this route
     */
    public <P extends @Nullable Object> RouteSpec<I, O> from(ComponentRef<I, P> component,
            Function<ServerRequest, ? extends @Nullable P> value) {
        return declare(new RouteDeclaration.Custom(component, value));
    }

    /**
     * Binds an input component from a request header, or writes an output component to a response header, depending
     * on which record declares the component. An output component written to a header is removed from the body
     * unless {@link #alsoInBody()} follows. An output header needs a single value, so a route whose output is a
     * {@code List} or a {@code PageResult} fails startup. The component must be a string, number, boolean, enum or
     * {@code java.time} value, or a {@code List} or {@code Optional} of one, formatted with Spring MVC's conversion
     * service; {@code Location}, {@code Link}, {@code Content-Type}, {@code Content-Length} and {@code ETag} are
     * reserved, and so are {@code Cache-Control} and {@code Vary} on a route with {@link #etag}.
     *
     * <p>
     * An input component bound from {@code If-Match} or {@code If-None-Match} must be {@code @Nullable}, and one
     * component at most binds each of them. On a write (every method but {@code GET} and {@code HEAD}) it is a
     * {@code @Nullable VersionCondition<V>} with a declared version type {@code V}: one entity tag binds
     * {@code Version(v)}, its opaque value converted to {@code V} (strong or weak; a weak {@code If-Match} is then
     * answered 412), {@code *} binds {@code AnyVersion}, and an absent header binds {@code null}. A read may also bind a
     * plain type, which receives the tag's converted value, and {@code null} for {@code *}. A {@code VersionCondition}
     * component can't bind from any other header, a path variable, the query string, a cookie or the body. Anything
     * else fails startup.
     *
     * @param name the header name
     * @param component a component of the input or the output record
     * @param <T> the record type
     * @return this route
     */
    public <T> RouteSpec<I, O> header(String name, ComponentRef<T, ?> component) {
        return declare(new RouteDeclaration.Header(name, component));
    }

    /**
     * Binds the whole request body to one input component, or writes one output component as the whole response
     * body, depending on which record declares the component. An output body component needs a single value, so a
     * route whose output is a {@code List} or a {@code PageResult} fails startup.
     *
     * @param component a component of the input or the output record
     * @param <T> the record type
     * @return this route
     */
    public <T> RouteSpec<I, O> body(ComponentRef<T, ?> component) {
        return declare(new RouteDeclaration.Body(component));
    }

    /**
     * Keeps the output component of the preceding {@link #header(String, ComponentRef)} in the body as well.
     *
     * @return this route
     */
    public RouteSpec<I, O> alsoInBody() {
        return declare(new RouteDeclaration.AlsoInBody());
    }

    /**
     * Lets callers sort this route's pages by the constants of an enum. A constant's wire name, as callers send it in
     * the sort parameter, is the value of its {@code @JsonProperty} when it has one, otherwise its name in lowerCamel
     * ({@code PUBLISHED_AT} is {@code publishedAt}); a leading {@code -} sorts descending. The operation receives the
     * constant's {@link Enum#name() name} as {@code SortOrder.property()}, so {@code SortKey.valueOf(order.property())}
     * reads it back. Without {@code .sortable}, a sort parameter is rejected.
     *
     * <p>
     * The route must bind {@code PageQuery}, and {@code .sortable} is declared once; wire names are made of letters,
     * digits, {@code _} and {@code .} and must be distinct. Anything else fails startup.
     *
     * @param keys the enum of sort keys
     * @param <E> the enum type
     * @return this route
     */
    public <E extends Enum<E>> RouteSpec<I, O> sortable(Class<E> keys) {
        return declare(new RouteDeclaration.Sortable(keys, List.of()));
    }

    /**
     * Lets callers sort this route's pages by these keys. Each key is both the wire name callers send in the sort
     * parameter (a leading {@code -} sorts descending) and the {@code SortOrder.property()} the operation receives.
     * Without {@code .sortable}, a sort parameter is rejected.
     *
     * <p>
     * The route must bind {@code PageQuery}, and {@code .sortable} is declared once with at least one key; keys are
     * made of letters, digits, {@code _} and {@code .} and must be distinct. Anything else fails startup.
     *
     * @param keys the sort keys
     * @return this route
     */
    public RouteSpec<I, O> sortable(String... keys) {
        return declare(new RouteDeclaration.Sortable(null, List.of(keys)));
    }

    /**
     * Sends an output component as the response's strong {@code ETag}, formatted as a version token (see below).
     * The component stays in the body. A {@code null} value sends no {@code ETag}. Responses with an {@code ETag} also
     * carry {@code Cache-Control: no-cache} and {@code Vary: Accept}, and a {@code GET} or {@code HEAD} whose
     * {@code If-None-Match} matches is answered 304.
     *
     * <p>
     * {@code .etag} is declared once, on a {@code GET} or {@code HEAD} route, or on a {@code POST} route with
     * {@link #created}: a {@code PUT} or {@code DELETE} response carries no {@code ETag} (RFC 9110 §9.3.4). The output
     * must be a single value or an {@code Optional}, not a {@code List} or a {@code PageResult}, and the component a
     * string, an integral number or a {@code java.time} value of the output record (a version type of core's version
     * token codec, which formats it: plain decimal or ISO-8601, never Spring MVC's conversion service, so gRPC sends
     * the same token). {@code ETag} is reserved on every
     * route, and {@code Cache-Control} and {@code Vary} on a route with {@code .etag}, so no output header may use
     * them. Anything else fails startup.
     *
     * @param component a component of the output record
     * @param <T> the record type
     * @return this route
     */
    public <T> RouteSpec<I, O> etag(ComponentRef<T, ?> component) {
        return declare(new RouteDeclaration.ETag(component));
    }

    /**
     * Serves this route in an API version, at {@code /v<version>} followed by its path: {@code .get("/books/{id}", op)
     * .version("2")} answers {@code /v2/books/{id}}, and a route on {@code /} answers {@code /v<version>}. A version
     * is {@code major[.minor[.patch]]} ({@code 1}, {@code 1.2} or {@code 1.2.3}), without a {@code v} prefix, and is
     * exact: the route answers only its own version, and a version that no route declares is 404.
     *
     * <p>
     * {@code .version} is declared at most once per route; to serve a route in several versions, declare it once per
     * version. Two versions of the same method and path that are equal ({@code 2} and {@code 2.0}) fail startup, and
     * so does an output header named {@code Deprecation} or {@code Sunset}, which Spring's API versioning sets on a
     * versioned route. API versioning is turned on by the first versioned route; routes without a version answer as
     * before.
     *
     * @param version the version, such as {@code "2"} or {@code "1.2"}
     * @return this route
     */
    public RouteSpec<I, O> version(String version) {
        return declare(new RouteDeclaration.Version(version));
    }

    /**
     * Sets the success status. It can't be combined with {@link #created} or {@link #accepted}, which set it.
     *
     * @param status the status, from 100 to 599 (anything else fails startup)
     * @return this route
     */
    public RouteSpec<I, O> status(int status) {
        this.status = status;
        this.statusFunction = null;
        return this;
    }

    /**
     * Sets the success status from the output. It can't be combined with {@link #created} or {@link #accepted}.
     *
     * @param status computes the status
     * @return this route
     */
    @SuppressWarnings("unchecked")
    public RouteSpec<I, O> status(ToIntFunction<? super O> status) {
        this.statusFunction = (ToIntFunction<Object>) status;
        this.status = null;
        return this;
    }

    /**
     * Answers 201 with the {@code Location} of the created resource.
     *
     * @param location computes the location from the output
     * @return this route
     */
    @SuppressWarnings("unchecked")
    public RouteSpec<I, O> created(Function<? super O, String> location) {
        this.created = (Function<Object, String>) location;
        return this;
    }

    /**
     * Answers 202 with the {@code Location} of the status resource.
     *
     * @param statusLocation computes the status resource location from the output
     * @return this route
     */
    @SuppressWarnings("unchecked")
    public RouteSpec<I, O> accepted(Function<? super O, String> statusLocation) {
        this.accepted = (Function<Object, String>) statusLocation;
        return this;
    }

    /**
     * Sets the error for an empty {@code Optional} output (by default {@code NOT_FOUND}). Its message arguments are
     * the values of the route's path variables as the request carries them, in template order: on
     * {@code /books/{id}}, {@code "Book {0} was not found"} reads {@code Book 42 was not found}. On a route without
     * path variables there are no arguments, so use a code whose message has no placeholders.
     *
     * @param code the error
     * @return this route
     */
    public RouteSpec<I, O> notFound(ErrorDetails code) {
        this.notFound = code;
        return this;
    }

    /**
     * Answers an empty {@code Optional} output with this status and no body.
     *
     * @param status the status, from 100 to 599 (anything else fails startup)
     * @return this route
     */
    public RouteSpec<I, O> emptyAs(int status) {
        this.emptyAs = status;
        return this;
    }

    /**
     * Sets this route's request body limit.
     *
     * @param size the limit; must be positive
     * @return this route
     * @throws IllegalArgumentException when the size is not positive
     */
    public RouteSpec<I, O> maxRequestSize(DataSize size) {
        if (size.toBytes() < 1) {
            throw new IllegalArgumentException("maxRequestSize must be positive but was " + size);
        }
        this.maxRequestSize = size;
        return this;
    }

    RouteDefinition definition() {
        ResponseRules response = new ResponseRules(status, statusFunction, created, accepted, notFound, emptyAs,
                List.of(), null, maxRequestSize);
        return RouteDefinition.declared(method, path, operation, declarations, response);
    }

    private RouteSpec<I, O> declare(RouteDeclaration declaration) {
        declarations.add(declaration);
        return this;
    }
}
