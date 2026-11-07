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

import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.util.ClassUtils;
import org.springframework.web.accept.SemanticApiVersionParser;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.web.util.pattern.PatternParseException;

import io.americanexpress.synapse.api.rest.RestConventions;
import io.americanexpress.synapse.api.rest.internal.binding.ConventionRules.HeaderRule;
import io.americanexpress.synapse.api.rest.internal.binding.ResponseRules.OutputHeader;
import io.americanexpress.synapse.api.rest.internal.paging.PagingSettings;
import io.americanexpress.synapse.api.rest.internal.paging.SortKeys;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.binding.ComponentRef;
import io.americanexpress.synapse.core.conditional.VersionCondition;
import io.americanexpress.synapse.core.internal.binding.ComponentRefs;
import io.americanexpress.synapse.core.internal.binding.OperationTypes;
import io.americanexpress.synapse.core.internal.binding.PersistenceTypes;
import io.americanexpress.synapse.core.internal.binding.ResolvedComponent;
import io.americanexpress.synapse.core.operation.OperationKind;
import io.americanexpress.synapse.core.paging.PageQuery;
import io.americanexpress.synapse.core.paging.PageResult;

/**
 * Resolves and checks REST routes at startup. For every route it resolves the component references, applies the
 * conventions and the defaults, and returns the route with one {@link InputSource} per input component that has one
 * and the response rules complete. Any binding mistake throws a {@link SynapseConfigurationException} whose
 * description starts with {@code REST route <METHOD> <path> (<operation>): }.
 *
 * <p>
 * An input component's source is, in this precedence: declared on the route; a {@link RestConventions} rule for its
 * type; the defaults (the path variable of the same name; on {@code GET}/{@code HEAD}, the query parameter of the same
 * name for strings, numbers, booleans, enums, {@code java.time} types and lists of these; on {@code POST}/{@code PUT}
 * without a {@code .body} component, the JSON body property).
 *
 * <p>
 * A {@code PageQuery} component always binds from the paging parameters, on every method, and nothing else may bind
 * it. On a route that binds {@code PageQuery}, no other component may read a query parameter named like a paging
 * parameter. A {@code PageResult} output is the operation's direct output, and is written as a page, without output
 * headers or a body component; a {@code PageResult} anywhere else in an output (a record component, a collection, a
 * map, an {@code Optional} or a page's items) fails. On a route with a {@code PageResult} output or that binds {@code PageQuery}, every
 * input component but the
 * {@code PageQuery} and those bound from {@code If-Match} or {@code If-None-Match} must fit the cursor's input
 * digest: a simple type or UUID, a record of them, or a {@code List}, {@code Set}, {@code Map} with {@code String}
 * keys or {@code Optional} of them.
 *
 * <p>
 * {@code If-Match} and {@code If-None-Match} bind to one component each. On a write (every method but {@code GET} and
 * {@code HEAD}) that component is a {@link VersionCondition} with a declared version type, so {@code *} and an absent
 * header stay apart: a {@code @Nullable} one makes the header optional, and a non-null one makes it required (the
 * operation's contract; a request without it is {@code PRECONDITION_REQUIRED}). On a read the component must be
 * {@code @Nullable}, and may be a plain type too. A {@code VersionCondition} component binds only from those two
 * headers, {@code .context} or {@code .from}.
 *
 * <p>
 * A route's {@code .version(...)} is declared once and is {@code major[.minor[.patch]]}; routes are checked on their
 * served paths ({@code /v<version>} and the declared path). Two routes with the same method and declared path whose
 * versions parse equal ({@code 2} and {@code 2.0}) fail, and a versioned route can't map an output header to
 * {@code Deprecation} or {@code Sunset}, which Spring's API versioning sets.
 */
public final class BindingValidator {

    /** Response headers Synapse writes itself, in lower case: an output component can't be mapped to them. */
    private static final Set<String> RESERVED_HEADERS = Set
            .of("location", "link", "content-type", "content-length", "etag");

    /** Response headers Synapse writes on a route with {@code .etag}, in lower case. */
    private static final Set<String> ETAG_ROUTE_HEADERS = Set.of("cache-control", "vary", "last-modified");

    /** Response headers Spring's API versioning writes on a versioned route, in lower case. */
    private static final Set<String> VERSIONED_ROUTE_HEADERS = Set.of("deprecation", "sunset");

    /** A route version: {@code major[.minor[.patch]]}, numbers only. */
    private static final Pattern VERSION = Pattern.compile("\\d+(\\.\\d+){0,2}");

    private static final SemanticApiVersionParser VERSION_PARSER = new SemanticApiVersionParser();

    private static final Pattern ANY_VARIABLE = Pattern.compile("\\{[^/}]*}");

    private final PagingSettings paging;

    /**
     * Creates the validator.
     *
     * @param paging the paging settings, for the paging parameter names
     */
    public BindingValidator(PagingSettings paging) {
        this.paging = paging;
    }

    /**
     * Resolves and checks the routes.
     *
     * @param routes the routes as declared, from every {@code RestBindings} bean
     * @param conventions the conventions, from every {@code RestConventions} bean
     * @return the resolved routes, in the same order
     * @throws SynapseConfigurationException on the first binding mistake
     */
    public List<RouteDefinition> validate(List<RouteDefinition> routes, List<RestConventions> conventions) {
        Conventions merged = Conventions.of(conventions);
        Map<String, RouteDefinition> seen = new HashMap<>();
        Map<String, Map<SemanticApiVersionParser.Version, RouteDefinition>> versions = new HashMap<>();
        List<RouteDefinition> resolved = new ArrayList<>(routes.size());
        for (RouteDefinition route : routes) {
            RouteDefinition previous = seen.putIfAbsent(routeKey(route.method(), route.path()), route);
            if (previous != null) {
                throw fail(route,
                        "another route has the same method and path: " + previous.method().name() + " "
                                + previous.path() + " (" + previous.operationName() + ")",
                        "Remove one of the routes, or change its method or path.");
            }
            // Resolving checks the version's format first, so only well-formed versions are compared below.
            resolved.add(new RouteResolver(route, merged, paging.names()).resolve());
            checkVersionUnique(route, versions);
        }
        return List.copyOf(resolved);
    }

    /** The duplicate key of a route: the method and the path with its variables blanked. */
    private static String routeKey(HttpMethod method, String path) {
        return method.name() + " " + ANY_VARIABLE.matcher(path).replaceAll("{}");
    }

    /**
     * Fails when another route has the same method and declared path in a version that parses equal to this route's
     * ({@code 2} and {@code 2.0}): their served paths differ, so the duplicate check doesn't see them.
     */
    private static void checkVersionUnique(RouteDefinition route,
            Map<String, Map<SemanticApiVersionParser.Version, RouteDefinition>> versions) {
        String version = route.version();
        if (version == null) {
            return;
        }
        RouteDefinition previous = versions
                .computeIfAbsent(routeKey(route.method(), route.declaredPath()), key -> new HashMap<>())
                .putIfAbsent(VERSION_PARSER.parseVersion(version), route);
        if (previous != null) {
            throw fail(route,
                    "versions " + previous.version() + " and " + version + " are equal, so this route and "
                            + previous.method().name() + " " + previous.path() + " (" + previous.operationName()
                            + ") are the same method and path in one version",
                    "Declare each version of a route once, in one spelling, such as .version(\"2\").");
        }
    }

    /**
     * Checks a declared route's {@code .version(...)}: it is declared at most once, and is
     * {@code major[.minor[.patch]]}. {@link #validate} runs it on every route before anything else on that route; the
     * API versioning configuration runs it too, since it may read the routes before they are validated.
     *
     * @param route a declared or resolved route
     * @throws SynapseConfigurationException when the route declares {@code .version} more than once, or a version
     *         that is not {@code major[.minor[.patch]]}
     */
    public static void checkVersion(RouteDefinition route) {
        @Nullable
        String first = null;
        for (RouteDeclaration declaration : route.declarations()) {
            if (!(declaration instanceof RouteDeclaration.Version(String version))) {
                continue;
            }
            if (first != null) {
                throw fail(route,
                        ".version(\"" + version + "\") follows .version(\"" + first + "\"), but a route's version "
                                + "is declared once",
                        "Keep one .version(...) on this route; to serve the route in another version too, declare "
                                + "it again with that version.");
            }
            if (!isVersion(version)) {
                throw fail(route,
                        ".version(\"" + version + "\") is not a version: a version is major[.minor[.patch]], such "
                                + "as 2 or 1.2",
                        "Declare the version as numbers only, such as .version(\"2\") or .version(\"1.2\"), with "
                                + "no v prefix, + or blank: the route is served at /v<version> followed by its "
                                + "path.");
            }
            first = version;
        }
    }

    /**
     * Tells whether a declared version is {@code major[.minor[.patch]]} with numbers Spring's
     * {@link SemanticApiVersionParser} can read (each fits an {@code int}).
     */
    private static boolean isVersion(String version) {
        if (!VERSION.matcher(version).matches()) {
            return false;
        }
        try {
            VERSION_PARSER.parseVersion(version);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    static SynapseConfigurationException fail(RouteDefinition route, String problem, String action) {
        return new SynapseConfigurationException("REST route " + route.method().name() + " " + route.path() + " ("
                + route.operationName() + "): " + problem, action);
    }

    private static String describe(ResolvedComponent component) {
        return component.recordType().getSimpleName() + "." + component.name();
    }

    private static String describe(InputSource source) {
        return switch (source) {
            case InputSource.Path path -> "path variable {" + path.variable() + "}";
            case InputSource.Query query -> "query parameter " + query.parameter();
            case InputSource.QueryRecord query -> "the query string";
            case InputSource.Body body -> "the request body";
            case InputSource.BodyComponent body -> "the whole request body";
            case InputSource.Header header -> "header " + header.name();
            case InputSource.Cookie cookie -> "cookie " + cookie.name();
            case InputSource.Context context -> "the request context";
            case InputSource.Custom custom -> ".from(...)";
            case InputSource.Page page -> "the paging parameters";
        };
    }

    private static boolean isRead(HttpMethod method) {
        return HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method);
    }

    private static boolean isConditionalHeader(String name) {
        return HttpHeaders.IF_MATCH.equalsIgnoreCase(name) || HttpHeaders.IF_NONE_MATCH.equalsIgnoreCase(name);
    }

    /**
     * Tells whether a component is declared as a {@link VersionCondition}, which binds from an entity tag.
     *
     * @param component the component
     * @return whether its type is {@code VersionCondition}
     */
    static boolean isVersionCondition(ResolvedComponent component) {
        return component.type() == VersionCondition.class;
    }

    /**
     * Returns the version type of a {@code VersionCondition} component, such as {@code Long} for
     * {@code VersionCondition<Long>}.
     *
     * @param component a {@code VersionCondition} component
     * @return the type argument, or {@code null} when the component is raw, a wildcard or a type variable
     */
    static @Nullable ResolvableType versionType(ResolvedComponent component) {
        if (!(component.genericType() instanceof ParameterizedType parameterized)) {
            return null;
        }
        Type argument = parameterized.getActualTypeArguments()[0];
        return argument instanceof Class<?> || argument instanceof ParameterizedType
                ? ResolvableType.forType(argument)
                : null;
    }

    /**
     * Tells whether a version type is {@code Object}, or an interface or abstract class other than {@code CharSequence}
     * that {@code String} is assignable to ({@code Serializable}, {@code Comparable}). The binder then keeps the header's
     * text as a {@code String}, which never equals a version of another type.
     */
    private static boolean isStringSupertype(Class<?> versionType) {
        boolean abstractType = versionType == Object.class || versionType.isInterface()
                || Modifier.isAbstract(versionType.getModifiers());
        return abstractType && versionType != CharSequence.class && versionType.isAssignableFrom(String.class);
    }

    /**
     * Tells whether a {@code VersionCondition} appears among the type's arguments or array component, at any depth
     * (up to {@value #MAX_TYPE_DEPTH}), such as in {@code Optional<VersionCondition<Long>>}. Record components are not
     * walked.
     */
    private static boolean wrapsVersionCondition(ResolvableType type, int depth) {
        if (depth > MAX_TYPE_DEPTH) {
            return false;
        }
        ResolvableType[] nested = type.isArray() ? new ResolvableType[]{type.getComponentType()} : type.getGenerics();
        for (ResolvableType argument : nested) {
            if (argument.resolve() == VersionCondition.class || wrapsVersionCondition(argument, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWrite(HttpMethod method) {
        return HttpMethod.POST.equals(method) || HttpMethod.PUT.equals(method);
    }

    /**
     * Tells whether a component binds from a query parameter by default on {@code GET} and {@code HEAD}.
     */
    private static boolean isSimple(ResolvedComponent component) {
        if (component.type() == List.class) {
            Class<?> element = ResolvableType.forType(component.genericType()).getGeneric(0).resolve();
            return element != null && isSimpleType(element);
        }
        return isSimpleType(component.type());
    }

    /**
     * Tells whether an output component can be written to a header: a type that binds from a query parameter by
     * default, or an {@code Optional} of a simple type.
     */
    private static boolean isSimpleHeaderValue(ResolvedComponent component) {
        if (component.type() == Optional.class) {
            Class<?> element = ResolvableType.forType(component.genericType()).getGeneric(0).resolve();
            return element != null && isSimpleType(element);
        }
        return isSimple(component);
    }

    /** How deep the startup checks walk nested records; deeper types are refused (or left to the writer's guard). */
    private static final int MAX_TYPE_DEPTH = 32;

    /** How many types one startup walk visits at most; beyond it, the walk stops (see {@link TypeWalk}). */
    static final int MAX_TYPE_VISITS = 10_000;

    /**
     * One startup walk over a type: the record parameterizations on the current path, and a budget of
     * {@value #MAX_TYPE_VISITS} type visits. The depth cap alone doesn't bound a generic record that branches and
     * grows, such as {@code Grow<T>(T value, Optional<Grow<List<T>>> a, Optional<Grow<Set<T>>> b)}: no
     * parameterization repeats, so the walk would visit about 2<sup>{@value #MAX_TYPE_DEPTH}</sup> of them and hang
     * startup. Once the budget is spent, every further visit is refused, so the walk ends at once.
     */
    static final class TypeWalk {

        private final Set<String> path = new HashSet<>();

        private int remaining;

        /** Creates a walk with the standard budget. */
        TypeWalk() {
            this(MAX_TYPE_VISITS);
        }

        /**
         * Creates a walk with a budget.
         *
         * @param budget how many types the walk may visit
         */
        TypeWalk(int budget) {
            this.remaining = budget;
        }

        /** Spends one visit; {@code false} once the budget is spent, or when the path is deeper than the cap. */
        boolean visit() {
            if (remaining <= 0 || path.size() > MAX_TYPE_DEPTH) {
                return false;
            }
            remaining--;
            return true;
        }
    }

    /**
     * Returns where a {@code PageResult} appears in the type: a record component, or a collection, map or
     * {@code Optional} whose type argument is one; {@code null} when none does. Each parameterization of a generic
     * record is walked on its own; a record type already on the current path (a recursive record) is not walked again.
     * Types nested deeper than {@value #MAX_TYPE_DEPTH} records, and whatever is left when the walk's budget is spent,
     * are left to the response writer's guard, which refuses to write a {@code PageResult} it meets.
     */
    static @Nullable String nestedPage(ResolvableType type, TypeWalk walk) {
        Class<?> raw = type.resolve();
        if (raw == null || !walk.visit()) {
            return null;
        }
        if (raw.isRecord()) {
            String key = type.toString();
            if (walk.path.contains(key)) {
                return null;
            }
            walk.path.add(key);
            try {
                for (RecordComponent component : raw.getRecordComponents()) {
                    ResolvableType componentType = ResolvableType.forType(component.getGenericType(), type);
                    if (componentType.resolve(Object.class) == PageResult.class) {
                        return raw.getSimpleName() + "." + component.getName();
                    }
                    String where = nestedPage(componentType, walk);
                    if (where != null) {
                        return where;
                    }
                }
                return null;
            } finally {
                walk.path.remove(key);
            }
        }
        if (raw == Optional.class || Collection.class.isAssignableFrom(raw) || Map.class.isAssignableFrom(raw)) {
            for (ResolvableType argument : type.getGenerics()) {
                if (argument.resolve(Object.class) == PageResult.class) {
                    return type.toString();
                }
                String where = nestedPage(argument, walk);
                if (where != null) {
                    return where;
                }
            }
        }
        return null;
    }

    /**
     * Tells whether the input digest of a cursor can write the type: a simple type or a UUID, a record of digestible
     * types, or a {@code List}, {@code Set}, {@code Map} with {@code String} keys or {@code Optional} of them. Each
     * parameterization of a generic record is checked on its own; a record type already on the current path (a
     * recursive record) is not walked again. A type nested deeper than {@value #MAX_TYPE_DEPTH} records, or one the
     * walk's budget doesn't cover, is not digestible: the check fails closed.
     */
    static boolean digestible(ResolvableType type, TypeWalk walk) {
        Class<?> raw = type.resolve();
        if (raw == null || !walk.visit()) {
            return false;
        }
        if (isSimpleType(raw) || raw == UUID.class) {
            return true;
        }
        if (raw.isRecord()) {
            String key = type.toString();
            if (walk.path.contains(key)) {
                return true;
            }
            walk.path.add(key);
            try {
                for (RecordComponent component : raw.getRecordComponents()) {
                    if (!digestible(ResolvableType.forType(component.getGenericType(), type), walk)) {
                        return false;
                    }
                }
                return true;
            } finally {
                walk.path.remove(key);
            }
        }
        if (raw == Optional.class || List.class.isAssignableFrom(raw) || Set.class.isAssignableFrom(raw)) {
            return digestible(type.getGeneric(0), walk);
        }
        if (Map.class.isAssignableFrom(raw)) {
            return type.getGeneric(0).resolve() == String.class && digestible(type.getGeneric(1), walk);
        }
        return false;
    }

    private static boolean isSimpleType(Class<?> type) {
        Class<?> boxed = ClassUtils.resolvePrimitiveIfNecessary(type);
        return boxed == String.class || Number.class.isAssignableFrom(boxed) || boxed == Boolean.class
                || boxed.isEnum() || "java.time".equals(boxed.getPackageName());
    }

    /**
     * The rules of every conventions bean, merged, with the response header components resolved.
     */
    private record Conventions(Set<Class<?>> queryRecords, Set<HeaderRule> headers,
            List<ResponseHeader> responseHeaders) {

        static Conventions of(List<RestConventions> beans) {
            Set<Class<?>> queryRecords = new LinkedHashSet<>();
            Set<HeaderRule> headers = new LinkedHashSet<>();
            List<ResponseHeader> responseHeaders = new ArrayList<>();
            for (RestConventions bean : beans) {
                ConventionRules rules = bean.rules();
                for (Class<?> recordType : rules.queryRecords()) {
                    if (!recordType.isRecord()) {
                        throw new SynapseConfigurationException(
                                "REST conventions: query(" + recordType.getName() + ") names a type that is not a "
                                        + "record.",
                                "Pass a record type to RestConventions.query, or bind the component on each route "
                                        + "with .query.");
                    }
                    queryRecords.add(recordType);
                }
                headers.addAll(rules.headers());
                for (ConventionRules.ResponseHeaderRule rule : rules.responseHeaders()) {
                    responseHeaders
                            .add(new ResponseHeader(rule.name(), rule.outputType(), resolveConvention(rule)));
                }
            }
            return new Conventions(queryRecords, headers, responseHeaders);
        }

        private static ResolvedComponent resolveConvention(ConventionRules.ResponseHeaderRule rule) {
            try {
                return ComponentRefs.resolve(rule.component());
            } catch (SynapseConfigurationException ex) {
                SynapseConfigurationException failure = new SynapseConfigurationException(
                        "REST conventions: responseHeader(\"" + rule.name() + "\", "
                                + rule.outputType().getSimpleName() + ", ...): " + ex.description(),
                        ex.action());
                failure.initCause(ex);
                throw failure;
            }
        }
    }

    private record ResponseHeader(String name, Class<?> outputType, ResolvedComponent component) {
    }

    /**
     * Resolves one route.
     */
    private static final class RouteResolver {

        private final RouteDefinition route;

        private final Conventions conventions;

        private final Class<?> outputValue;

        private final Map<String, InputSource> declared = new LinkedHashMap<>();

        private final Set<String> declaredVariables = new HashSet<>();

        private final List<OutputHeader> outputHeaders = new ArrayList<>();

        /** Output component name to where it is written, such as {@code header X-Rate}. */
        private final Map<String, String> outputTargets = new HashMap<>();

        /** Lower-case response header name to the header. */
        private final Map<String, OutputHeader> headerNames = new HashMap<>();

        private final Set<String> conventionSourced = new HashSet<>();

        private @Nullable ResolvedComponent responseBody;

        private boolean requestBody;

        /** The paging parameter names, which no other query source may use on a route that binds PageQuery. */
        private final Set<String> pagingNames;

        /** The input's PageQuery component, set by {@link #resolve()}. */
        private @Nullable ResolvedComponent page;

        private @Nullable SortKeys sortKeys;

        /** Whether the route declares {@code .etag}, which reserves {@code Cache-Control}, {@code Vary} and {@code Last-Modified}. */
        private final boolean etagRoute;

        private @Nullable ResolvedComponent etag;

        RouteResolver(RouteDefinition route, Conventions conventions, Set<String> pagingNames) {
            this.route = route;
            this.conventions = conventions;
            this.pagingNames = pagingNames;
            this.outputValue = route.outputValueType();
            this.etagRoute = route.declarations().stream().anyMatch(RouteDeclaration.ETag.class::isInstance);
        }

        RouteDefinition resolve() {
            // First, so a malformed version isn't reported as a bad path template.
            checkVersion(route);
            checkTypes();
            checkResponseOptions();
            page = pageComponent();
            List<String> variables = templateVariables();
            for (RouteDeclaration declaration : route.declarations()) {
                declare(declaration, variables);
            }
            List<InputSource> sources = inputSources(variables);
            applyResponseHeaderConventions();
            PagingRules paging = pagingRules(sources);
            checkNestedVersionConditions(sources);
            ConditionalRules conditional = conditionalRules(sources);
            checkVersionConditions(sources);
            if (route.pagedOutput() || paging != null) {
                // A route that binds PageQuery unwraps cursors, so it digests its input even without a page output.
                checkDigestible(sources, conditional);
            }
            return route
                    .resolved(sources, route.response().withOutput(outputHeaders, responseBody), paging, conditional);
        }

        private void checkTypes() {
            checkNotEntity("input", route.inputType());
            ResolvableType output = RouteDefinition.generic(OperationTypes.userClassOf(route.operation()), 1);
            Class<?> raw = output.resolve(Object.class);
            if ((raw == Optional.class || raw == List.class)
                    && output.getGeneric(0).resolve(Object.class) == PageResult.class) {
                throw fail(route,
                        "the output type " + raw.getSimpleName() + "<PageResult> wraps a PageResult, which must be the "
                                + "operation's direct output",
                        "Return the PageResult itself from the operation; a page with no items is an empty page.");
            }
            checkNoNestedPage(output);
            checkNotEntity("output", outputValue);
            if (!route.inputType().isRecord() && route.inputType() != Void.class) {
                throw fail(route, "the input type " + route.inputType().getName() + " is not a record or Void",
                        "Declare the operation's input as a record, or as Void if it takes none.");
            }
            boolean read = isRead(route.method());
            if (read && OperationKind.of(OperationTypes.userClassOf(route.operation())) == OperationKind.COMMAND) {
                throw fail(route, "a CommandOperation cannot be bound to " + route.method().name(),
                        "Bind it to POST, PUT or DELETE, or make it a QueryOperation if it only reads.");
            }
        }

        /**
         * Fails when a {@code PageResult} appears anywhere in the output other than as the output itself: in a record
         * component, a collection, a map or an {@code Optional}, at any depth, or as the item type of a page
         * ({@code PageResult<PageResult<T>>}). Such a page would be written by the application's mapper with the
         * operation's raw cursor.
         */
        private void checkNoNestedPage(ResolvableType output) {
            ResolvableType start = output.resolve(Object.class) == PageResult.class ? output.getGeneric(0) : output;
            String where = start.resolve(Object.class) == PageResult.class
                    ? "PageResult.items"
                    : nestedPage(start, new TypeWalk());
            if (where != null) {
                throw fail(route,
                        "the output has a PageResult in " + where + ", but a PageResult must be the operation's "
                                + "direct output",
                        "Return the PageResult itself from the operation, so Synapse writes it as the page body and "
                                + "wraps its cursor; keep other values out of a paged route's output.");
            }
        }

        /**
         * Fails when an input component of a route with a {@code PageResult} output, or of a route that binds
         * {@code PageQuery} (which unwraps cursors), can't be put into the cursor's input digest. A garbage cursor
         * is then always a 400, never a 500. The {@code PageQuery} and the components bound from {@code If-Match} or
         * {@code If-None-Match} are not digested.
         */
        private void checkDigestible(List<InputSource> sources, ConditionalRules conditional) {
            for (InputSource source : sources) {
                if (source instanceof InputSource.Page || source.equals(conditional.ifMatch())
                        || source.equals(conditional.ifNoneMatch())) {
                    continue;
                }
                ResolvedComponent component = source.component();
                if (!digestible(ResolvableType.forType(component.genericType()), new TypeWalk())) {
                    throw fail(route,
                            "component " + describe(component) + " (type " + component.genericType().getTypeName()
                                    + ") can't be bound into a paging cursor; use a record or simple type",
                            "Make the component a record, an enum, or a string, number, boolean, UUID or java.time "
                                    + "value (or a List, a Set, a Map with String keys or an Optional of them), or "
                                    + "remove it from the input of this paged route.");
                }
            }
        }

        private void checkNotEntity(String role, Class<?> type) {
            String reason = PersistenceTypes.persistenceReason(type);
            if (reason != null) {
                throw fail(route,
                        "the " + role + " type " + type.getName() + " is a persistence entity (" + reason + ")",
                        "Expose a model record instead, and map the entity to it in the operation.");
            }
        }

        private void checkResponseOptions() {
            ResponseRules response = route.response();
            checkStatus(".status", response.status());
            checkStatus(".emptyAs", response.emptyAs());
            if (response.status() != null || response.statusFunction() != null) {
                String location = response.created() != null
                        ? "created(...)"
                        : response.accepted() != null ? "accepted(...)" : null;
                if (location != null) {
                    throw fail(route, "status(...) conflicts with " + location + ", which sets the status",
                            "Remove .status(...), or remove ." + location + ".");
                }
            }
            if (route.outputType() == Optional.class) {
                return;
            }
            String option = route.response().notFound() != null
                    ? ".notFound"
                    : route.response().emptyAs() != null ? ".emptyAs" : null;
            if (option != null) {
                throw fail(route,
                        option + " needs an Optional output, but the output type is "
                                + route.outputType().getSimpleName(),
                        "Return an Optional from the operation, or remove " + option + ".");
            }
        }

        private void checkStatus(String option, @Nullable Integer status) {
            if (status != null && (status < 100 || status > 599)) {
                throw fail(route, option + "(" + status + ") is not an HTTP status (100-599)",
                        "Pass a status from 100 to 599 to " + option + ".");
            }
        }

        private List<String> templateVariables() {
            try {
                PathPatternParser.defaultInstance.parse(route.path());
            } catch (PatternParseException ex) {
                throw fail(route, "'" + route.path() + "' is not a valid path template (" + ex.getMessage() + ")",
                        "Fix the path template; variables are written {name}.");
            }
            return route.pathVariables();
        }

        private void declare(RouteDeclaration declaration, List<String> variables) {
            switch (declaration) {
                case RouteDeclaration.Path path -> {
                    ResolvedComponent component = inputComponent(path.component());
                    if (!variables.contains(path.variable())) {
                        throw fail(route,
                                ".path(\"" + path.variable() + "\", ...) names the variable {" + path.variable()
                                        + "}, which is not in the path template",
                                "Use a variable of the template, or add {" + path.variable() + "} to the path.");
                    }
                    declaredVariables.add(path.variable());
                    addInput(new InputSource.Path(component, path.variable()));
                }
                case RouteDeclaration.Query query -> {
                    ResolvedComponent component = inputComponent(query.component());
                    String parameter = query.parameter();
                    if (parameter == null && component.type().isRecord()) {
                        addInput(new InputSource.QueryRecord(component, ComponentRefs.components(component.type())));
                    } else {
                        addInput(new InputSource.Query(component, parameter != null ? parameter : component.name()));
                    }
                }
                case RouteDeclaration.Cookie cookie ->
                    addInput(new InputSource.Cookie(inputComponent(cookie.component()), cookie.name()));
                case RouteDeclaration.Context context ->
                    addInput(new InputSource.Context(inputComponent(context.component()), context.value()));
                case RouteDeclaration.Custom custom ->
                    addInput(new InputSource.Custom(inputComponent(custom.component()), custom.value()));
                case RouteDeclaration.Header header -> {
                    ResolvedComponent component = resolve(header.component());
                    if (isInput(component)) {
                        addInput(new InputSource.Header(component, header.name()));
                    } else {
                        requireSingleOutput("an output header", ".header(\"" + header.name() + "\", ...)");
                        addOutputHeader(header.name(), component);
                    }
                }
                case RouteDeclaration.Body body -> declareBody(resolve(body.component()));
                case RouteDeclaration.AlsoInBody alsoInBody -> keepLastHeaderInBody();
                case RouteDeclaration.Sortable sortable -> declareSortable(sortable);
                case RouteDeclaration.ETag declared -> declareETag(declared);
                case RouteDeclaration.Version version -> {
                    // Checked by checkVersion, before the path template is parsed.
                }
            }
        }

        private void declareSortable(RouteDeclaration.Sortable sortable) {
            if (sortKeys != null) {
                throw fail(route, ".sortable(...) is declared twice",
                        "Declare .sortable once per route, with every key callers may sort by.");
            }
            Class<? extends Enum<?>> keys = sortable.keys();
            try {
                sortKeys = keys != null ? SortKeys.of(keys) : SortKeys.of(sortable.names());
            } catch (SynapseConfigurationException ex) {
                throw onRoute(ex);
            }
        }

        /**
         * Records the {@code ETag} component: one simple component of the output record, on {@code GET} and
         * {@code HEAD}, or on a {@code POST} with {@code .created}, whose output is a single value.
         */
        private void declareETag(RouteDeclaration.ETag declaration) {
            if (etag != null) {
                throw fail(route, ".etag(...) is declared twice",
                        "Declare .etag once per route, with the output component that versions the resource.");
            }
            HttpMethod method = route.method();
            if (HttpMethod.POST.equals(method)) {
                if (route.response().created() == null) {
                    throw fail(route,
                            ".etag(...) on a POST needs .created(...): the ETag is sent only with the 201 of a created "
                                    + "resource",
                            "Add .created(...) to the route, or remove .etag(...).");
                }
            } else if (!isRead(method)) {
                throw fail(route,
                        ".etag(...) can't be used on " + method.name() + ": Synapse sends no ETag in a PUT or DELETE "
                                + "response (RFC 9110 §9.3.4)",
                        "Remove .etag(...) from this route, and declare it on the GET route clients read the "
                                + "resource from.");
            }
            requireSingleOutput(".etag(...)", ".etag(...)");
            ResolvedComponent component = component(declaration.component());
            if (component.recordType() != outputValue) {
                throw fail(route,
                        "the component " + describe(component) + " of .etag(...) is not part of the output type "
                                + outputValue.getSimpleName(),
                        "Reference a component of " + outputValue.getSimpleName() + ", such as its version.");
            }
            if (!isSimpleType(component.type())) {
                throw fail(route,
                        "the ETag needs a simple type, but " + describe(component) + " is "
                                + component.genericType().getTypeName(),
                        "Use a string, number, boolean, enum or java.time component, such as a version number, for "
                                + "the ETag.");
            }
            etag = component;
        }

        /**
         * Returns the route's conditional rules, after checking the components bound from {@code If-Match} and
         * {@code If-None-Match}.
         */
        private ConditionalRules conditionalRules(List<InputSource> sources) {
            InputSource.Header ifMatch = conditionalHeader(sources, HttpHeaders.IF_MATCH);
            InputSource.Header ifNoneMatch = conditionalHeader(sources, HttpHeaders.IF_NONE_MATCH);
            ResolvedComponent tag = etag;
            if (tag == null && ifMatch == null && ifNoneMatch == null) {
                return ConditionalRules.NONE;
            }
            return new ConditionalRules(tag, ifMatch, ifNoneMatch);
        }

        /**
         * Returns the source bound from the header {@code name} (ignoring case), declared or by convention, checking
         * that it is the only one, that a write binds it to a {@code VersionCondition}, and that a read's component is
         * nullable. On a write, a {@code VersionCondition} that isn't {@code @Nullable} is the operation's way of
         * saying the header is required, the same over every protocol; a {@code @Nullable} one is optional.
         */
        private InputSource.@Nullable Header conditionalHeader(List<InputSource> sources, String name) {
            InputSource.Header found = null;
            for (InputSource source : sources) {
                if (!(source instanceof InputSource.Header header) || !name.equalsIgnoreCase(header.name())) {
                    continue;
                }
                ResolvedComponent component = header.component();
                if (found != null) {
                    throw fail(route,
                            "the header " + name + " is bound to two components: " + describe(found.component())
                                    + " and " + describe(component),
                            "Bind " + name + " to one component: the version the request is conditional on.");
                }
                boolean condition = isVersionCondition(component);
                boolean read = isRead(route.method());
                if (!condition && !read) {
                    String version = ClassUtils.resolvePrimitiveIfNecessary(component.type()).getSimpleName();
                    throw fail(route,
                            "the component " + describe(component) + " is bound from " + name + " on "
                                    + route.method().name() + ", but its type is " + component.type().getSimpleName()
                                    + ": a write needs a VersionCondition, so that * and an absent header stay apart",
                            "Declare it as VersionCondition<" + version + "> to require the header, or @Nullable "
                                    + "VersionCondition<" + version + "> to make it optional (null without it): "
                                    + "Version(v) for one entity tag, AnyVersion for *.");
                }
                if (condition && read && !component.nullable()) {
                    throw fail(route,
                            "the component " + describe(component) + " is bound from " + name + " on "
                                    + route.method().name() + " but isn't @Nullable: a read can't require a "
                                    + "precondition, and an absent " + name + " binds null",
                            "Declare " + describe(component) + " @Nullable.");
                }
                if (!condition && !component.nullable()) {
                    throw fail(route,
                            "the component " + describe(component) + " is bound from " + name
                                    + " but isn't @Nullable: * binds null; declare " + describe(component)
                                    + " @Nullable",
                            "Declare " + describe(component) + " @Nullable (a number as its boxed type, such as "
                                    + "Long); an absent " + name + " binds null too.");
                }
                found = header;
            }
            return found;
        }

        /**
         * Fails when a {@code VersionCondition} component binds from anything but {@code If-Match},
         * {@code If-None-Match}, {@code .context} or {@code .from} (it has no wire form other than an entity tag),
         * doesn't declare its version type, or declares {@code Object} or another abstract supertype of {@code String}
         * other than {@code CharSequence}.
         */
        private void checkVersionConditions(List<InputSource> sources) {
            for (InputSource source : sources) {
                ResolvedComponent component = source.component();
                if (!isVersionCondition(component)) {
                    continue;
                }
                boolean allowed = switch (source) {
                    case InputSource.Header header -> isConditionalHeader(header.name());
                    case InputSource.Context context -> true;
                    case InputSource.Custom custom -> true;
                    default -> false;
                };
                if (!allowed) {
                    throw wrongVersionConditionSource(component, "", source);
                }
                ResolvableType version = versionType(component);
                if (version == null) {
                    throw fail(route,
                            "the component " + describe(component) + " is a VersionCondition without a version type",
                            "Declare the version type, for example VersionCondition<Long>.");
                }
                Class<?> versionClass = version.toClass();
                if (isStringSupertype(versionClass)) {
                    // The text binds as a String (String to Object, Serializable or Comparable is no conversion), and
                    // Version("5") never equals the Long the operation reads, so every conditional write would fail.
                    throw fail(route,
                            "the component " + describe(component) + " is bound as VersionCondition<"
                                    + versionClass.getSimpleName() + ">; the version type must be a concrete type "
                                    + "such as Long or String",
                            "Declare the type of the resource's version, for example VersionCondition<Long>: the "
                                    + "bound value is converted to it and compared with the version the operation "
                                    + "reads.");
                }
            }
        }

        /**
         * Fails when a {@code VersionCondition} is inside another type ({@code Optional}, a collection, a map, an
         * array) or a component of a record bound from the query string: neither has a wire form for it. Runs before
         * the {@code If-Match}/{@code If-None-Match} checks, so a wrapped condition isn't reported as a plain type.
         * Values from {@code .context} and {@code .from} are the application's, and are not checked.
         */
        private void checkNestedVersionConditions(List<InputSource> sources) {
            for (InputSource source : sources) {
                if (source instanceof InputSource.Context || source instanceof InputSource.Custom
                        || source instanceof InputSource.Page) {
                    continue;
                }
                checkNotWrapped(source.component(), source);
                if (source instanceof InputSource.QueryRecord query) {
                    for (ResolvedComponent component : query.components()) {
                        if (isVersionCondition(component)) {
                            throw wrongVersionConditionSource(component, "", source);
                        }
                        checkNotWrapped(component, source);
                    }
                }
            }
        }

        private void checkNotWrapped(ResolvedComponent component, InputSource source) {
            if (!isVersionCondition(component)
                    && wrapsVersionCondition(ResolvableType.forType(component.genericType()), 0)) {
                throw wrongVersionConditionSource(component, " inside " + component.type().getSimpleName() + ",",
                        source);
            }
        }

        private SynapseConfigurationException wrongVersionConditionSource(ResolvedComponent component, String where,
                InputSource source) {
            return fail(route,
                    "the component " + describe(component) + " is a VersionCondition" + where + " bound from "
                            + describe(source),
                    "Bind it with .header(\"If-Match\", ...) or .header(\"If-None-Match\", ...), or supply it with "
                            + ".context or .from.");
        }

        /**
         * Returns the input's {@code PageQuery} component, or {@code null} when it has none or is itself a
         * {@code PageQuery}.
         */
        private @Nullable ResolvedComponent pageComponent() {
            Class<?> inputType = route.inputType();
            if (!inputType.isRecord() || inputType == PageQuery.class) {
                return null;
            }
            List<ResolvedComponent> pages = ComponentRefs
                    .components(inputType)
                    .stream()
                    .filter(component -> component.type() == PageQuery.class)
                    .toList();
            if (pages.size() > 1) {
                throw fail(route,
                        "the input type " + inputType.getSimpleName() + " has more than one PageQuery component ("
                                + String.join(", ", pages.stream().map(BindingValidator::describe).toList()) + ")",
                        "Keep one PageQuery component: the paging parameters fill it.");
            }
            return pages.isEmpty() ? null : pages.getFirst();
        }

        private boolean bindsPageQuery() {
            return page != null || route.inputType() == PageQuery.class;
        }

        /**
         * Returns the route's paging rules, after checking that {@code .sortable} has a {@code PageQuery} to sort and
         * that no other query source uses a paging parameter name.
         */
        private @Nullable PagingRules pagingRules(List<InputSource> sources) {
            if (!bindsPageQuery()) {
                if (sortKeys != null) {
                    throw fail(route,
                            ".sortable(...) needs a route that binds PageQuery, but the input type "
                                    + route.inputType().getSimpleName() + " has no PageQuery component",
                            "Add a PageQuery component to the input, or remove .sortable(...) from this route.");
                }
                return null;
            }
            for (InputSource source : sources) {
                if (source instanceof InputSource.Query query) {
                    checkNotPagingName(query.parameter(), query.component());
                } else if (source instanceof InputSource.QueryRecord query) {
                    for (ResolvedComponent component : query.components()) {
                        checkNotPagingName(component.name(), component);
                    }
                }
            }
            return new PagingRules(page, sortKeys);
        }

        private void checkNotPagingName(String parameter, ResolvedComponent component) {
            if (pagingNames.contains(parameter)) {
                throw fail(route,
                        "the query parameter " + parameter + " of " + describe(component) + " is a paging parameter "
                                + "name, and the route binds PageQuery from the paging parameters "
                                + String.join(", ", pagingNames),
                        "Bind " + describe(component) + " from another query parameter (for a component of the "
                                + "input, .query(\"name\", ...)), or rename the paging parameter with "
                                + "synapse.api.rest.paging.parameters.");
            }
        }

        private void declareBody(ResolvedComponent component) {
            if (isInput(component)) {
                if (!isWrite(route.method())) {
                    throw fail(route,
                            ".body(" + describe(component) + ") binds the request body, which is only read on POST "
                                    + "and PUT",
                            "Bind the component from another source, or use POST or PUT.");
                }
                requestBody = true;
                addInput(new InputSource.BodyComponent(component));
                return;
            }
            requireSingleOutput("a body component", ".body(" + describe(component) + ")");
            ResolvedComponent previous = responseBody;
            if (previous != null) {
                throw fail(route,
                        "the response body is declared twice (" + describe(previous) + " and " + describe(component)
                                + ")",
                        "Declare .body once per route.");
            }
            mapOutput(component, "the response body");
            responseBody = component;
        }

        private void requireSingleOutput(String what, String call) {
            if (route.outputType() == List.class) {
                throw fail(route, what + " needs a single value, but the output is a List",
                        "Remove " + call + " from this route, or return a single value or an Optional.");
            }
            if (route.pagedOutput()) {
                throw fail(route, what + " needs a single value, but the output is a PageResult",
                        "Remove " + call + " from this route: a PageResult is written as the page body.");
            }
        }

        private void addOutputHeader(String name, ResolvedComponent component) {
            String lowerCase = name.toLowerCase(Locale.ROOT);
            if (RESERVED_HEADERS.contains(lowerCase)) {
                throw fail(route, "the output header " + name + " is reserved: Synapse sets it",
                        "Use another header name; Location comes from .created(...) or .accepted(...), and ETag "
                                + "from .etag(...).");
            }
            if (etagRoute && ETAG_ROUTE_HEADERS.contains(lowerCase)) {
                throw fail(route, "the output header " + name + " is reserved on a route with .etag(...): Synapse sets "
                        + "it", "Use another header name, or remove .etag(...) from this route.");
            }
            if (route.version() != null && VERSIONED_ROUTE_HEADERS.contains(lowerCase)) {
                throw fail(route,
                        "the output header " + name + " is reserved on a versioned route: Spring's API versioning "
                                + "sets it",
                        "Use another header name; to deprecate a version, declare an ApiVersionDeprecationHandler "
                                + "bean, which sends Deprecation and Sunset.");
            }
            if (!isSimpleHeaderValue(component)) {
                throw fail(route,
                        "the output header " + name + " needs a simple type, but " + describe(component) + " is "
                                + component.genericType().getTypeName(),
                        "Map a component that is a string, number, boolean, enum or java.time value (or a List or "
                                + "Optional of one) to the header, or keep the value in the body.");
            }
            mapOutput(component, "header " + name);
            OutputHeader header = new OutputHeader(name, component, false);
            OutputHeader previous = headerNames.putIfAbsent(name.toLowerCase(Locale.ROOT), header);
            if (previous != null) {
                throw fail(route,
                        "the response header " + name + " is mapped twice: " + describe(previous.component())
                                + " and " + describe(component),
                        "Give each output header its own name (header names are case-insensitive).");
            }
            outputHeaders.add(header);
        }

        /**
         * Adds the conventions' response headers for this route's output type, except for components the route
         * maps itself. A {@code List} or {@code PageResult} output has no single value to read a header from, so they
         * don't apply to it.
         */
        private void applyResponseHeaderConventions() {
            if (route.outputType() == List.class || route.pagedOutput()) {
                return;
            }
            Set<String> mappedOnTheRoute = Set.copyOf(outputTargets.keySet());
            for (ResponseHeader header : conventions.responseHeaders()) {
                if (header.outputType() == outputValue && !mappedOnTheRoute.contains(header.component().name())) {
                    addOutputHeader(header.name(), header.component());
                }
            }
        }

        private void keepLastHeaderInBody() {
            if (outputHeaders.isEmpty()) {
                throw fail(route, ".alsoInBody() must follow an output .header(...)",
                        "Declare .header(name, Output::component) before .alsoInBody(), or remove .alsoInBody().");
            }
            int last = outputHeaders.size() - 1;
            OutputHeader header = outputHeaders.get(last);
            outputHeaders.set(last, new OutputHeader(header.name(), header.component(), true));
        }

        private List<InputSource> inputSources(List<String> variables) {
            if (!route.inputType().isRecord() || route.inputType() == PageQuery.class) {
                // A PageQuery input is read whole from the paging parameters.
                checkVariablesBound(variables, List.of());
                return List.of();
            }
            boolean read = isRead(route.method());
            boolean write = isWrite(route.method());
            List<InputSource> sources = new ArrayList<>();
            List<ResolvedComponent> unsourced = new ArrayList<>();
            for (ResolvedComponent component : ComponentRefs.components(route.inputType())) {
                ResolvedComponent pageQuery = page;
                if (pageQuery != null && pageQuery.name().equals(component.name())) {
                    sources.add(pageSource(pageQuery));
                    continue;
                }
                InputSource source = declared.get(component.name());
                if (source == null) {
                    source = conventionSource(component);
                    if (source != null) {
                        conventionSourced.add(component.name());
                    }
                }
                if (source == null) {
                    if (variables.contains(component.name()) && !declaredVariables.contains(component.name())) {
                        source = new InputSource.Path(component, component.name());
                    } else if (read && isSimple(component)) {
                        source = new InputSource.Query(component, component.name());
                    } else if (write && !requestBody) {
                        source = new InputSource.Body(component);
                    }
                }
                if (source != null) {
                    sources.add(source);
                } else if (!component.nullable()) {
                    unsourced.add(component);
                }
            }
            checkVariablesBound(variables, sources);
            if (!unsourced.isEmpty()) {
                ResolvedComponent component = unsourced.getFirst();
                throw fail(route, "the input component " + describe(component) + " has no source",
                        "Bind it on the route (.path, .query, .header, .cookie, .context or .from), add a "
                                + "RestConventions rule for its type, or declare it @Nullable.");
            }
            return sources;
        }

        private void checkVariablesBound(List<String> variables, List<InputSource> sources) {
            for (String variable : variables) {
                boolean bound = sources
                        .stream()
                        .anyMatch(source -> source instanceof InputSource.Path path
                                && path.variable().equals(variable));
                if (bound) {
                    continue;
                }
                InputSource same = sources
                        .stream()
                        .filter(source -> source.component().name().equals(variable))
                        .findFirst()
                        .orElse(null);
                if (same != null) {
                    String reference = route.inputType().getSimpleName() + "::" + variable;
                    boolean byConvention = conventionSourced.contains(variable);
                    throw fail(route,
                            "the path variable {" + variable + "} is not bound: the component "
                                    + describe(same.component()) + " is bound "
                                    + (byConvention ? "by a convention" : "on the route") + " to " + describe(same),
                            "Bind it with .path(\"" + variable + "\", " + reference + ") on the route"
                                    + (byConvention ? ", which beats the convention," : " instead,")
                                    + " or rename the variable.");
                }
                throw fail(route, "the path variable {" + variable + "} is not bound to an input component",
                        "Name the variable after a component of " + route.inputType().getSimpleName()
                                + ", or bind it with .path(\"" + variable + "\", "
                                + route.inputType().getSimpleName() + "::component).");
            }
        }

        private @Nullable InputSource conventionSource(ResolvedComponent component) {
            List<InputSource> found = new ArrayList<>();
            if (conventions.queryRecords().contains(component.type())) {
                found.add(new InputSource.QueryRecord(component, ComponentRefs.components(component.type())));
            }
            for (HeaderRule rule : conventions.headers()) {
                if (ClassUtils.resolvePrimitiveIfNecessary(rule.componentType()) == ClassUtils
                        .resolvePrimitiveIfNecessary(component.type())) {
                    found.add(new InputSource.Header(component, rule.name()));
                }
            }
            if (found.size() > 1) {
                throw twoSources(component, found.get(0), found.get(1), "Keep one RestConventions rule for "
                        + component.type().getSimpleName() + ", or declare the source on the route.");
            }
            return found.isEmpty() ? null : found.getFirst();
        }

        /**
         * Returns the paging parameters source of the PageQuery component, which no convention may bind (a declared
         * source has already failed in {@link #addInput}).
         */
        private InputSource pageSource(ResolvedComponent component) {
            InputSource convention = conventionSource(component);
            if (convention != null) {
                throw fail(route,
                        "the component " + describe(component) + " is a PageQuery, which binds from the paging "
                                + "parameters, but a convention binds it to " + describe(convention),
                        "Remove the RestConventions rule for PageQuery; set the parameter names with "
                                + "synapse.api.rest.paging.parameters.");
            }
            return new InputSource.Page(component);
        }

        private void addInput(InputSource source) {
            ResolvedComponent component = source.component();
            if (route.inputType() == PageQuery.class || component.type() == PageQuery.class) {
                String bound = route.inputType() == PageQuery.class
                        ? "the input is a PageQuery"
                        : "the component " + describe(component) + " is a PageQuery";
                throw fail(route,
                        bound + ", which binds from the paging parameters, so it can't be bound from "
                                + describe(source),
                        "Remove the declaration; set the parameter names with synapse.api.rest.paging.parameters.");
            }
            InputSource previous = declared.putIfAbsent(source.component().name(), source);
            if (previous != null) {
                throw twoSources(source.component(), previous, source, "Bind each component from one place.");
            }
        }

        private SynapseConfigurationException twoSources(ResolvedComponent component, InputSource first,
                InputSource second, String action) {
            return fail(route,
                    "the component " + describe(component) + " has two sources: " + describe(first) + " and "
                            + describe(second),
                    action);
        }

        private void mapOutput(ResolvedComponent component, String target) {
            String previous = outputTargets.putIfAbsent(component.name(), target);
            if (previous != null) {
                throw fail(route,
                        "the output component " + describe(component) + " is mapped twice: " + previous + " and "
                                + target,
                        "Map each output component to one header or to the body.");
            }
        }

        private ResolvedComponent inputComponent(ComponentRef<?, ?> ref) {
            ResolvedComponent component = resolve(ref);
            if (!isInput(component)) {
                throw fail(route,
                        "the component " + describe(component) + " is not part of the input type "
                                + route.inputType().getName(),
                        "Reference a component of " + route.inputType().getSimpleName() + ".");
            }
            return component;
        }

        /**
         * Returns a failure of a route-independent check with this route named.
         */
        private SynapseConfigurationException onRoute(SynapseConfigurationException ex) {
            SynapseConfigurationException failure = fail(route, ex.description(), ex.action());
            failure.initCause(ex);
            return failure;
        }

        private ResolvedComponent component(ComponentRef<?, ?> ref) {
            try {
                return ComponentRefs.resolve(ref);
            } catch (SynapseConfigurationException ex) {
                throw onRoute(ex);
            }
        }

        private ResolvedComponent resolve(ComponentRef<?, ?> ref) {
            ResolvedComponent component = component(ref);
            if (!isInput(component) && component.recordType() != outputValue) {
                throw fail(route,
                        "the component " + describe(component) + " is not part of the input or output type ("
                                + route.inputType().getSimpleName() + " or " + outputValue.getSimpleName() + ")",
                        "Reference a component of the operation's input record or of its output record.");
            }
            return component;
        }

        private boolean isInput(ResolvedComponent component) {
            return component.recordType() == route.inputType();
        }
    }
}
