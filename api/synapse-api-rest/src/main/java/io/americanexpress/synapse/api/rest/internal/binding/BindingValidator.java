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

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpMethod;
import org.springframework.util.ClassUtils;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.web.util.pattern.PatternParseException;

import io.americanexpress.synapse.api.rest.RestConventions;
import io.americanexpress.synapse.api.rest.internal.binding.ConventionRules.HeaderRule;
import io.americanexpress.synapse.api.rest.internal.binding.ResponseRules.OutputHeader;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.binding.ComponentRef;
import io.americanexpress.synapse.core.internal.binding.ComponentRefs;
import io.americanexpress.synapse.core.internal.binding.ResolvedComponent;
import io.americanexpress.synapse.core.operation.OperationKind;
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
 */
public final class BindingValidator {

    private static final List<String> ENTITY_ANNOTATIONS = List
            .of("jakarta.persistence.Entity", "org.springframework.data.mongodb.core.mapping.Document",
                    "org.springframework.data.relational.core.mapping.Table");

    private static final String DATA_PACKAGE = "io.americanexpress.synapse.data";

    /** Response headers Synapse writes itself, in lower case: an output component can't be mapped to them. */
    private static final Set<String> RESERVED_HEADERS = Set.of("location", "link", "content-type", "content-length");

    private static final Pattern VARIABLE = Pattern.compile("\\{\\*?([A-Za-z_$][\\w$]*)");

    private static final Pattern ANY_VARIABLE = Pattern.compile("\\{[^/}]*}");

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
        List<RouteDefinition> resolved = new ArrayList<>(routes.size());
        for (RouteDefinition route : routes) {
            RouteDefinition previous = seen
                    .putIfAbsent(route.method().name() + " " + ANY_VARIABLE.matcher(route.path()).replaceAll("{}"),
                            route);
            if (previous != null) {
                throw fail(route,
                        "another route has the same method and path: " + previous.method().name() + " "
                                + previous.path() + " (" + previous.operationName() + ")",
                        "Remove one of the routes, or change its method or path.");
            }
            resolved.add(new RouteResolver(route, merged).resolve());
        }
        return List.copyOf(resolved);
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
        };
    }

    private static boolean isRead(HttpMethod method) {
        return HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method);
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

    private static boolean isSimpleType(Class<?> type) {
        Class<?> boxed = ClassUtils.resolvePrimitiveIfNecessary(type);
        return boxed == String.class || Number.class.isAssignableFrom(boxed) || boxed == Boolean.class
                || boxed.isEnum() || "java.time".equals(boxed.getPackageName());
    }

    /**
     * Returns why a type is a persistence entity, or {@code null}. Annotations are matched by name, so none of the
     * persistence libraries has to be on the class path.
     */
    private static @Nullable String persistenceReason(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Annotation annotation : current.getDeclaredAnnotations()) {
                String name = annotation.annotationType().getName();
                if (ENTITY_ANNOTATIONS.contains(name)) {
                    return "annotated @" + name;
                }
            }
            if (current != type && isDataType(current)) {
                return "extends " + current.getName();
            }
        }
        for (Class<?> implemented : ClassUtils.getAllInterfacesForClassAsSet(type)) {
            if (isDataType(implemented)) {
                return "implements " + implemented.getName();
            }
        }
        return null;
    }

    private static boolean isDataType(Class<?> type) {
        String name = type.getPackageName();
        return name.equals(DATA_PACKAGE) || name.startsWith(DATA_PACKAGE + ".");
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

        RouteResolver(RouteDefinition route, Conventions conventions) {
            this.route = route;
            this.conventions = conventions;
            this.outputValue = route.outputValueType();
        }

        RouteDefinition resolve() {
            checkTypes();
            checkResponseOptions();
            List<String> variables = templateVariables();
            for (RouteDeclaration declaration : route.declarations()) {
                declare(declaration, variables);
            }
            List<InputSource> sources = inputSources(variables);
            applyResponseHeaderConventions();
            return route.resolved(sources, route.response().withOutput(outputHeaders, responseBody));
        }

        private void checkTypes() {
            checkNotEntity("input", route.inputType());
            ResolvableType output = RouteDefinition.generic(RouteDefinition.operationClass(route.operation()), 1);
            Class<?> raw = output.resolve(Object.class);
            if (raw == Optional.class || raw == List.class || raw == PageResult.class) {
                output = output.getGeneric(0);
            }
            checkNotEntity("output", output.resolve(Object.class));
            if (!route.inputType().isRecord() && route.inputType() != Void.class) {
                throw fail(route, "the input type " + route.inputType().getName() + " is not a record or Void",
                        "Declare the operation's input as a record, or as Void if it takes none.");
            }
            boolean read = isRead(route.method());
            if (read && OperationKind.of(RouteDefinition.operationClass(route.operation())) == OperationKind.COMMAND) {
                throw fail(route, "a CommandOperation cannot be bound to " + route.method().name(),
                        "Bind it to POST, PUT or DELETE, or make it a QueryOperation if it only reads.");
            }
        }

        private void checkNotEntity(String role, Class<?> type) {
            String reason = persistenceReason(type);
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
            List<String> variables = new ArrayList<>();
            Matcher matcher = VARIABLE.matcher(route.path());
            while (matcher.find()) {
                variables.add(matcher.group(1));
            }
            return variables;
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
        }

        private void addOutputHeader(String name, ResolvedComponent component) {
            if (RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                throw fail(route, "the output header " + name + " is reserved: Synapse sets it",
                        "Use another header name; Location comes from .created(...) or .accepted(...).");
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
         * maps itself. A {@code List} output has no single value to read a header from, so they don't apply to it.
         */
        private void applyResponseHeaderConventions() {
            if (route.outputType() == List.class) {
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
            if (!route.inputType().isRecord()) {
                checkVariablesBound(variables, List.of());
                return List.of();
            }
            boolean read = isRead(route.method());
            boolean write = isWrite(route.method());
            List<InputSource> sources = new ArrayList<>();
            List<ResolvedComponent> unsourced = new ArrayList<>();
            for (ResolvedComponent component : ComponentRefs.components(route.inputType())) {
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

        private void addInput(InputSource source) {
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

        private ResolvedComponent resolve(ComponentRef<?, ?> ref) {
            ResolvedComponent component;
            try {
                component = ComponentRefs.resolve(ref);
            } catch (SynapseConfigurationException ex) {
                SynapseConfigurationException failure = fail(route, ex.description(), ex.action());
                failure.initCause(ex);
                throw failure;
            }
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
