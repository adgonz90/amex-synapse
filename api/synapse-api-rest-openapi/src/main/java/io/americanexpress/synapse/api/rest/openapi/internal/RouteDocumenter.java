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
package io.americanexpress.synapse.api.rest.openapi.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;

import io.americanexpress.synapse.api.rest.RestErrors;
import io.americanexpress.synapse.api.rest.internal.binding.ConditionalRules;
import io.americanexpress.synapse.api.rest.internal.binding.InputSource;
import io.americanexpress.synapse.api.rest.internal.binding.PagingRules;
import io.americanexpress.synapse.api.rest.internal.binding.RequestBinder;
import io.americanexpress.synapse.api.rest.internal.binding.ResponseRules;
import io.americanexpress.synapse.api.rest.internal.binding.ResponseRules.OutputHeader;
import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.api.rest.internal.error.RestErrorStatuses;
import io.americanexpress.synapse.api.rest.internal.paging.PagingSettings;
import io.americanexpress.synapse.api.rest.internal.paging.SortKeys;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.internal.binding.ResolvedComponent;
import io.americanexpress.synapse.core.operation.OperationNames;
import io.americanexpress.synapse.core.operation.ServiceOperation;

/**
 * Documents resolved REST routes as OpenAPI 3.1 operations: one operation per declared route (the implicit
 * {@code HEAD} of a {@code GET} and the fallback {@code OPTIONS} are not documented), with what a client sends and
 * every response the edge sends.
 *
 * <ul>
 * <li>Parameters: path variables, query parameters (a {@code QueryRecord} gives one per component), cookies and
 * headers. {@code If-Match} and {@code If-None-Match} are entity tags, documented as strings here, so a
 * {@code VersionCondition} never reaches the schema generator; {@code If-None-Match} is also documented on an
 * {@code .etag} read whose success status is 200 or {@code 2XX}, the only reads where it decides a 304. The paging
 * parameters follow {@link PagingSettings}, and every operation takes the correlation and deadline headers.
 * {@code .context} and {@code .from} components are not documented.</li>
 * <li>The request body: the input record when every component comes from the body, an inline object of the body
 * components otherwise, or the component of {@code .body(...)}.</li>
 * <li>Responses: the success status, its body (none for a {@code HEAD}) and headers; the 304 of an {@code .etag} read
 * whose success status is 200 or {@code 2XX}; one problem response per status the route's errors map to, through
 * {@link RestErrorStatuses} so overrides show, describing its codes; {@code default} for any other error. Every
 * response carries the correlation header.</li>
 * </ul>
 *
 * <p>
 * Not thread-safe, like the {@link SchemaGenerator} it uses.
 */
public final class RouteDocumenter {

    /** The component name of the problem body, which the generator reserves. */
    static final String PROBLEM = SchemaGenerator.PROBLEM;

    /** The component name of a problem's violation, which the generator reserves. */
    static final String VIOLATION = SchemaGenerator.VIOLATION;

    private static final String JSON = "application/json";

    private static final String PROBLEM_JSON = "application/problem+json";

    private static final String SCHEMAS = "#/components/schemas/";

    private static final String HEADERS = "#/components/headers/";

    private static final String ENTITY_TAG = "An entity tag (\"5\"), or *.";

    private static final String STRING = "string";

    private static final String INTEGER = "integer";

    /** A template variable with an optional catch-all marker and pattern: group 1 is the name. */
    private static final Pattern VARIABLE = Pattern.compile("\\{\\*?([A-Za-z_$][\\w$]*)(?::[^}]*)?}");

    private final SchemaGenerator schemas;

    private final PagingSettings paging;

    private final RestErrorStatuses statuses;

    private final String correlationHeader;

    private final String deadlineHeader;

    /** The types documented as {@code {}}, with the first route that used each, across every call. */
    private final Map<String, String> undescribed = new TreeMap<>();

    /**
     * Creates the documenter.
     *
     * @param schemas the schema generator, which holds the components of every route documented
     * @param paging the paging settings: parameter names and bounds
     * @param statuses the error statuses, overrides included
     * @param correlationHeader the correlation header ({@code synapse.correlation.header})
     * @param deadlineHeader the deadline header ({@code synapse.api.rest.deadline.header})
     */
    public RouteDocumenter(SchemaGenerator schemas, PagingSettings paging, RestErrorStatuses statuses,
            String correlationHeader, String deadlineHeader) {
        this.schemas = schemas;
        this.paging = paging;
        this.statuses = statuses;
        this.correlationHeader = correlationHeader;
        this.deadlineHeader = deadlineHeader;
    }

    /**
     * Returns the routes springdoc's path filters keep, with springdoc's own rule for its handlers
     * ({@code AbstractOpenApiResource.isPathToMatch} in springdoc 3.2.0, which is not accessible): a route is kept when
     * no pattern to match is set or one of them matches it, and no pattern to exclude matches it. Patterns are
     * Ant-style, matched with {@link AntPathMatcher} against the route's template as it is documented
     * ({@code /v2/books/{id}}).
     *
     * @param routes the routes
     * @param pathsToMatch {@code springdoc.paths-to-match}; {@code null} or empty matches every route
     * @param pathsToExclude {@code springdoc.paths-to-exclude}; {@code null} or empty excludes none
     * @return the routes kept, in their order
     */
    public static List<RouteDefinition> matching(List<RouteDefinition> routes, @Nullable List<String> pathsToMatch,
            @Nullable List<String> pathsToExclude) {
        AntPathMatcher matcher = new AntPathMatcher();
        List<RouteDefinition> kept = new ArrayList<>();
        for (RouteDefinition route : routes) {
            String path = template(route);
            boolean matched = CollectionUtils.isEmpty(pathsToMatch)
                    || pathsToMatch.stream().anyMatch(pattern -> matcher.match(pattern, path));
            boolean excluded = !CollectionUtils.isEmpty(pathsToExclude)
                    && pathsToExclude.stream().anyMatch(pattern -> matcher.match(pattern, path));
            if (matched && !excluded) {
                kept.add(route);
            }
        }
        return List.copyOf(kept);
    }

    /**
     * Returns the routes in the order they were declared. The routes bean holds them most specific template first, so
     * a route added later can come before an existing one; documenting them in declaration order keeps the first
     * declared route of an operation its plain operationId when another route reuses the operation.
     *
     * @param routes the resolved routes, in any order
     * @param declared the routes as declared: each {@code RestBindings} bean's routes, in bean order
     * @return the routes ordered by their first declaration, matched by method and served path; a route that wasn't
     *         declared comes last, in its order
     */
    public static List<RouteDefinition> inDeclarationOrder(List<RouteDefinition> routes,
            List<RouteDefinition> declared) {
        Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < declared.size(); i++) {
            positions.putIfAbsent(key(declared.get(i)), i);
        }
        List<RouteDefinition> ordered = new ArrayList<>(routes);
        // A stable sort: routes that weren't declared keep their order.
        ordered.sort(Comparator.comparingInt(route -> positions.getOrDefault(key(route), Integer.MAX_VALUE)));
        return List.copyOf(ordered);
    }

    /** A route's identity across its declared and resolved forms: the method and the served path. */
    private static String key(RouteDefinition route) {
        return route.method().name() + " " + route.path();
    }

    /** The route's template as it is documented: its variables without catch-all markers or patterns. */
    private static String template(RouteDefinition route) {
        return VARIABLE.matcher(route.path()).replaceAll("{$1}");
    }

    /**
     * Documents the routes. An operation's first route gets its name as operationId, and its later routes the name
     * with a suffix, so pass them in declaration order ({@link #inDeclarationOrder}).
     *
     * @param routes the resolved routes, in declaration order
     * @return the paths, the components and the types documented as {@code {}}
     */
    public Fragment document(List<RouteDefinition> routes) {
        Map<String, PathItem> paths = new LinkedHashMap<>();
        // The templates by their shape (variables blanked): the first route's template names the path.
        Map<String, String> templates = new HashMap<>();
        // Every operation name is an operationId already, so a generated -N never repeats one.
        Set<String> operationIds = new HashSet<>();
        for (RouteDefinition route : routes) {
            operationIds.add(route.operationName());
        }
        Set<String> seen = new HashSet<>();
        for (RouteDefinition route : routes) {
            String template = template(route);
            String path = templates.computeIfAbsent(VARIABLE.matcher(route.path()).replaceAll("{}"), shape -> template);
            Operation operation = operation(route, renames(template, path));
            operation.setOperationId(operationId(route.operationName(), seen, operationIds));
            paths
                    .computeIfAbsent(path, key -> new PathItem())
                    .operation(PathItem.HttpMethod.valueOf(route.method().name()), operation);
            String described = describe(route);
            for (String type : schemas.undescribed()) {
                undescribed.putIfAbsent(type, described);
            }
        }
        Map<String, Schema<?>> components = new TreeMap<>(schemas.components());
        // The generator keeps these names free: an application type with one of them has its qualified name.
        components.put(PROBLEM, problem());
        components.put(VIOLATION, violation());
        Header correlation = new Header().description("The request's correlation ID.").schema(typed(STRING, null));
        return new Fragment(Collections.unmodifiableMap(paths), Collections.unmodifiableMap(components),
                Map.of(correlationHeader, correlation), Collections.unmodifiableMap(new TreeMap<>(undescribed)));
    }

    /** {@code METHOD path (operation)}, as startup messages name a route. */
    private static String describe(RouteDefinition route) {
        return route.method().name() + " " + route.path() + " (" + route.operationName() + ")";
    }

    /**
     * The operation name on its first route; on a later route, the name with the lowest suffix {@code -2},
     * {@code -3} … that no operation name or earlier operationId uses.
     */
    private static String operationId(String name, Set<String> seen, Set<String> taken) {
        if (seen.add(name)) {
            return name;
        }
        int suffix = 2;
        while (taken.contains(name + "-" + suffix)) {
            suffix++;
        }
        String id = name + "-" + suffix;
        taken.add(id);
        return id;
    }

    /**
     * The route's path variables renamed to the variables of the path it is documented at, by position: OpenAPI
     * sees {@code /books/{id}} and {@code /books/{bookId}} as one path, so a later route takes the first one's names.
     */
    private static Map<String, String> renames(String template, String path) {
        Map<String, String> renames = new HashMap<>();
        if (template.equals(path)) {
            return renames;
        }
        Matcher own = VARIABLE.matcher(template);
        Matcher documented = VARIABLE.matcher(path);
        while (own.find() && documented.find()) {
            renames.put(own.group(1), documented.group(1));
        }
        return renames;
    }

    private Operation operation(RouteDefinition route, Map<String, String> renames) {
        Operation operation = new Operation();
        OperationNames.groupOf(operationClass(route.operation())).ifPresent(operation::addTagsItem);
        operation.setParameters(parameters(route, renames));
        RequestBody body = requestBody(route);
        if (body != null) {
            operation.setRequestBody(body);
        }
        operation.setResponses(responses(route, body != null));
        return operation;
    }

    private List<Parameter> parameters(RouteDefinition route, Map<String, String> renames) {
        List<Parameter> parameters = new ArrayList<>();
        for (InputSource source : route.sources()) {
            switch (source) {
                case InputSource.Path path -> parameters
                        .add(parameter("path", renames.getOrDefault(path.variable(), path.variable()), true,
                                schemas.parameter(path.component())));
                case InputSource.Query query -> parameters
                        .add(parameter("query", query.parameter(), required(query.component()),
                                schemas.parameter(query.component())));
                case InputSource.QueryRecord record -> {
                    // A @Nullable record is null when none of its parameters is sent, so then none is required.
                    boolean recordRequired = required(record.component());
                    for (ResolvedComponent component : record.components()) {
                        parameters
                                .add(parameter("query", component.name(), recordRequired && required(component),
                                        schemas.parameter(component)));
                    }
                }
                case InputSource.Cookie cookie -> parameters
                        .add(parameter("cookie", cookie.name(), required(cookie.component()),
                                schemas.parameter(cookie.component())));
                case InputSource.Header header -> parameters.add(header(header));
                // The paging parameters follow the settings (below).
                case InputSource.Page page -> {
                }
                // The request body.
                case InputSource.Body body -> {
                }
                case InputSource.BodyComponent body -> {
                }
                // Not sent by the client.
                case InputSource.Context context -> {
                }
                case InputSource.Custom custom -> {
                }
            }
        }
        if (notModified(route) && route.conditional().ifNoneMatch() == null) {
            // The edge answers 304 when If-None-Match matches, whether or not the route binds it.
            addIfAbsent(parameters, parameter("header", HttpHeaders.IF_NONE_MATCH, false, typed(STRING, null))
                    .description(ENTITY_TAG));
        }
        PagingRules rules = route.paging();
        if (rules != null) {
            pagingParameters(rules.sortKeys()).forEach(parameter -> addIfAbsent(parameters, parameter));
        }
        addIfAbsent(parameters, parameter("header", correlationHeader, false, typed(STRING, null))
                .description("The request's correlation ID; one is generated when absent."));
        Schema<?> timeout = typed(INTEGER, "int64");
        timeout.setMinimum(BigDecimal.ONE);
        addIfAbsent(parameters, parameter("header", deadlineHeader, false, timeout)
                .description("The caller's timeout in milliseconds."));
        return parameters;
    }

    /**
     * A header parameter. {@code If-Match} and {@code If-None-Match} are entity tags, documented here without the
     * schema generator: their component is a {@code VersionCondition} on a write, which has no JSON form. Each is
     * required when its component isn't {@code @Nullable}, as the edge requires it.
     */
    private Parameter header(InputSource.Header header) {
        String name = header.name();
        if (HttpHeaders.IF_MATCH.equalsIgnoreCase(name) || HttpHeaders.IF_NONE_MATCH.equalsIgnoreCase(name)) {
            return parameter("header", name, required(header.component()), typed(STRING, null))
                    .description(ENTITY_TAG);
        }
        return parameter("header", name, required(header.component()), schemas.parameter(header.component()));
    }

    private List<Parameter> pagingParameters(@Nullable SortKeys sortKeys) {
        List<Parameter> parameters = new ArrayList<>();
        parameters.add(parameter("query", paging.cursor(), false, typed(STRING, null)));
        Schema<?> offset = typed(INTEGER, "int64");
        offset.setMinimum(BigDecimal.ZERO);
        offset.setMaximum(BigDecimal.valueOf(paging.maxOffset()));
        parameters.add(parameter("query", paging.offset(), false, offset));
        Schema<?> size = typed(INTEGER, null);
        size.setMinimum(BigDecimal.ONE);
        size.setMaximum(BigDecimal.valueOf(paging.maxSize()));
        size.setDefault(paging.defaultSize());
        parameters.add(parameter("query", paging.size(), false, size));
        if (sortKeys != null) {
            List<String> keys = new ArrayList<>();
            for (String key : sortKeys.wireToProperty().keySet()) {
                keys.add(key);
                keys.add("-" + key);
            }
            JsonSchema key = (JsonSchema) typed(STRING, null);
            key._enum(new ArrayList<>(keys));
            Parameter sort = parameter("query", paging.sort(), false, typed("array", null).items(key));
            sort.setStyle(Parameter.StyleEnum.FORM);
            sort.setExplode(false);
            parameters.add(sort);
        }
        return parameters;
    }

    /** Adds the parameter unless the route already has one of that name in the same place. */
    private static void addIfAbsent(List<Parameter> parameters, Parameter parameter) {
        boolean present = parameters
                .stream()
                .anyMatch(existing -> existing.getIn().equals(parameter.getIn())
                        && existing.getName().equalsIgnoreCase(parameter.getName()));
        if (!present) {
            parameters.add(parameter);
        }
    }

    private static Parameter parameter(String in, String name, boolean required, Schema<?> schema) {
        return new Parameter().in(in).name(name).required(required).schema(schema);
    }

    /**
     * Whether a component must be sent: a value that isn't nullable, unless it is an {@code Optional}. A collection or
     * map, which the binder binds empty when it isn't sent, is required only when its constraints need items
     * ({@code @NotEmpty}, {@code @Size(min = 1)}), as an absent one is then rejected; its schema says how many.
     */
    private boolean required(ResolvedComponent component) {
        return nonNull(component)
                && (!RequestBinder.bindsEmptyWhenUnsent(component.type()) || schemas.needsItems(component));
    }

    /** Whether a component's value can't be {@code null}: it isn't nullable, and it isn't an {@code Optional}. */
    private static boolean nonNull(ResolvedComponent component) {
        return !component.nullable() && component.type() != Optional.class;
    }

    private @Nullable RequestBody requestBody(RouteDefinition route) {
        Set<String> components = new LinkedHashSet<>();
        boolean required = false;
        Schema<?> schema = null;
        for (InputSource source : route.sources()) {
            if (source instanceof InputSource.BodyComponent body) {
                schema = schemas.input(ResolvableType.forType(body.component().genericType()));
                // A body left out counts as {}, which a whole-body list isn't, so only null reads empty: required.
                required = nonNull(body.component());
            } else if (source instanceof InputSource.Body body) {
                components.add(body.component().name());
                required |= required(body.component());
            }
        }
        if (schema == null && components.isEmpty()) {
            return null;
        }
        if (schema == null) {
            Class<?> input = route.inputType();
            schema = components.size() == input.getRecordComponents().length
                    ? schemas.input(ResolvableType.forClass(input))
                    : schemas.inputObject(input, components::contains);
        }
        return new RequestBody().required(required).content(content(JSON, schema));
    }

    private ApiResponses responses(RouteDefinition route, boolean requestBody) {
        ApiResponses responses = new ApiResponses();
        success(route, responses);
        if (notModified(route)) {
            ApiResponse notModified = response("Not Modified");
            cachingHeaders(notModified, true);
            responses.addApiResponse("304", notModified);
        }
        problems(route, requestBody).forEach((status, codes) -> {
            String key = String.valueOf(status);
            if (!responses.containsKey(key)) {
                HttpStatus known = HttpStatus.resolve(status);
                String codeList = String.join(", ", codes);
                responses
                        .addApiResponse(key,
                                problem(known != null ? known.getReasonPhrase() + ": " + codeList : codeList));
            }
        });
        responses.addApiResponse("default", problem("Any other error."));
        return responses;
    }

    private void success(RouteDefinition route, ApiResponses responses) {
        ResponseRules rules = route.response();
        ResolvableType value = valueType(route);
        boolean empty = isVoid(value);
        String status = successStatus(route);
        HttpStatus known = "2XX".equals(status) ? null : HttpStatus.resolve(Integer.parseInt(status));
        ApiResponse success = response(known != null ? known.getReasonPhrase() : "Success");
        // HEAD gets the GET response's headers without the body.
        if (!empty && !"204".equals(status) && !HttpMethod.HEAD.equals(route.method())) {
            success.setContent(content(JSON, body(route, value)));
        }
        if (rules.accepted() != null) {
            success.addHeaderObject(HttpHeaders.LOCATION, header("The status resource.", typed(STRING, null)));
            success
                    .addHeaderObject(HttpHeaders.LINK, header("The status resource, rel=\"status\".", typed(STRING,
                            null)));
        } else if (rules.created() != null) {
            success.addHeaderObject(HttpHeaders.LOCATION, header("The created resource.", typed(STRING, null)));
        }
        for (OutputHeader header : rules.outputHeaders()) {
            success.addHeaderObject(header.name(), new Header().schema(headerSchema(header.component())));
        }
        if (route.conditional().etag() != null) {
            boolean read = isRead(route.method()) && ("200".equals(status) || "2XX".equals(status));
            boolean created = HttpMethod.POST.equals(route.method())
                    && ("201".equals(status) || "2XX".equals(status));
            if (read || created) {
                cachingHeaders(success, read);
            }
        }
        if (route.pagedOutput() && route.paging() != null && isRead(route.method())) {
            success.addHeaderObject(HttpHeaders.LINK, header("The next page, rel=\"next\".", typed(STRING, null)));
        }
        responses.addApiResponse(status, success);
        Integer emptyAs = rules.emptyAs();
        if (emptyAs != null && !responses.containsKey(String.valueOf(emptyAs))) {
            HttpStatus emptyStatus = HttpStatus.resolve(emptyAs);
            responses
                    .addApiResponse(String.valueOf(emptyAs),
                            response(emptyStatus != null ? emptyStatus.getReasonPhrase() : "Empty"));
        }
        ResolvedComponent bodyComponent = rules.bodyComponent();
        if (bodyComponent != null && bodyComponent.nullable() && !responses.containsKey("204")) {
            // A null body component is answered 204 without content.
            responses.addApiResponse("204", response(HttpStatus.NO_CONTENT.getReasonPhrase()));
        }
    }

    /** The output's value: the operation's output type, unwrapped from {@code Optional}. */
    private static ResolvableType valueType(RouteDefinition route) {
        ResolvableType output = outputType(route.operation());
        return route.outputType() == Optional.class ? output.getGeneric(0) : output;
    }

    private static boolean isVoid(ResolvableType value) {
        return ClassUtils.resolvePrimitiveIfNecessary(value.resolve(Object.class)) == Void.class;
    }

    /** The success status: of a {@code Void} output, or as {@link #status} decides it. */
    private static String successStatus(RouteDefinition route) {
        return isVoid(valueType(route)) ? emptyStatus(route.response()) : status(route);
    }

    /**
     * Whether the route may answer 304: an {@code .etag} read whose success can be 200, the only status on which the
     * edge evaluates {@code If-None-Match}.
     */
    private static boolean notModified(RouteDefinition route) {
        String status = successStatus(route);
        return route.conditional().etag() != null && isRead(route.method())
                && ("200".equals(status) || "2XX".equals(status));
    }

    /**
     * The schema of an output header, whose values the {@code ConversionService} writes as it reads parameters: an
     * enum as its constant names (not its JSON names), and each element of a collection as one value.
     */
    private Schema<?> headerSchema(ResolvedComponent component) {
        return schemas.parameter(component);
    }

    /** The status of a route whose output is {@code Void}: its fixed status, or 204. */
    private static String emptyStatus(ResponseRules rules) {
        Integer fixed = rules.status();
        return fixed != null ? String.valueOf(fixed) : "204";
    }

    /** The success status as {@code ResponseWriter} decides it; {@code 2XX} when a function computes it. */
    private static String status(RouteDefinition route) {
        ResponseRules rules = route.response();
        Integer fixed = rules.status();
        if (fixed != null) {
            return String.valueOf(fixed);
        }
        if (rules.statusFunction() != null) {
            return "2XX";
        }
        if (rules.accepted() != null) {
            return "202";
        }
        if (rules.created() != null) {
            return "201";
        }
        return HttpMethod.DELETE.equals(route.method()) ? "204" : "200";
    }

    /**
     * The success body: the page component for a {@code PageResult}, the {@code .body(...)} component, the output
     * without the components written only to headers, or the output.
     */
    private Schema<?> body(RouteDefinition route, ResolvableType value) {
        if (route.pagedOutput()) {
            return schemas.page(value.getGeneric(0));
        }
        ResponseRules rules = route.response();
        ResolvedComponent component = rules.bodyComponent();
        if (component != null) {
            return schemas.output(ResolvableType.forType(component.genericType()));
        }
        Set<String> headerOnly = new HashSet<>();
        for (OutputHeader header : rules.outputHeaders()) {
            if (!header.alsoInBody()) {
                headerOnly.add(header.component().name());
            }
        }
        Class<?> raw = value.resolve(Object.class);
        if (!headerOnly.isEmpty() && raw.isRecord()) {
            return schemas.outputObject(raw, name -> !headerOnly.contains(name));
        }
        return schemas.output(value);
    }

    /**
     * The statuses of the errors the edge sends for the route, each with its codes in the order they are listed.
     */
    private Map<Integer, List<String>> problems(RouteDefinition route, boolean requestBody) {
        List<ErrorDetails> errors = new ArrayList<>();
        if (route.inputType() != Void.class) {
            errors.add(SynapseErrors.VALIDATION_FAILED);
            errors.add(SynapseErrors.MALFORMED_REQUEST);
        }
        ResponseRules rules = route.response();
        boolean emptyIsNotFound = route.outputType() == Optional.class && rules.emptyAs() == null;
        if (emptyIsNotFound || !route.pathVariables().isEmpty()) {
            ErrorDetails notFound = rules.notFound();
            errors.add(notFound != null ? notFound : SynapseErrors.NOT_FOUND);
        }
        if (requestBody) {
            errors.add(RestErrors.PAYLOAD_TOO_LARGE);
            errors.add(RestErrors.UNSUPPORTED_MEDIA_TYPE);
        }
        ConditionalRules conditional = route.conditional();
        if (!isRead(route.method())) {
            if (conditional.ifMatch() != null || conditional.ifNoneMatch() != null) {
                errors.add(SynapseErrors.PRECONDITION_FAILED);
            }
            // When the input requires If-Match, every request that reaches the operation has it, and a STALE_VERSION
            // is then answered 412 PRECONDITION_FAILED.
            if (conditional.ifMatch() != null && !conditional.ifMatchRequired()) {
                errors.add(SynapseErrors.STALE_VERSION);
            }
            if (conditional.ifMatchRequired() || conditional.ifNoneMatchRequired()) {
                errors.add(SynapseErrors.PRECONDITION_REQUIRED);
            }
        }
        Map<Integer, List<String>> problems = new TreeMap<>();
        for (ErrorDetails error : errors) {
            List<String> codes = problems
                    .computeIfAbsent(statuses.statusFor(error, route.operationName()), status -> new ArrayList<>());
            if (!codes.contains(error.code())) {
                codes.add(error.code());
            }
        }
        return problems;
    }

    /** {@code ETag}, and on a read {@code Cache-Control} and {@code Vary}. */
    private static void cachingHeaders(ApiResponse response, boolean read) {
        response.addHeaderObject(HttpHeaders.ETAG, header("The representation's entity tag.", typed(STRING, null)));
        if (read) {
            response
                    .addHeaderObject(HttpHeaders.CACHE_CONTROL,
                            header("no-cache: revalidate with If-None-Match.", typed(STRING, null)));
            response.addHeaderObject(HttpHeaders.VARY, header("Accept.", typed(STRING, null)));
        }
    }

    private ApiResponse problem(String description) {
        Schema<?> ref = new JsonSchema();
        ref.set$ref(SCHEMAS + PROBLEM);
        ApiResponse response = response(description);
        response.setContent(content(PROBLEM_JSON, ref));
        return response;
    }

    /** A response with the correlation header, which every response carries. */
    private ApiResponse response(String description) {
        Header correlation = new Header();
        correlation.set$ref(HEADERS + correlationHeader);
        return new ApiResponse().description(description).addHeaderObject(correlationHeader, correlation);
    }

    private static Header header(String description, Schema<?> schema) {
        return new Header().description(description).schema(schema);
    }

    private static Content content(String mediaType, Schema<?> schema) {
        return new Content().addMediaType(mediaType, new MediaType().schema(schema));
    }

    /** The problem body ({@code ProblemBody}), whose member names are fixed. */
    private static Schema<?> problem() {
        Schema<?> problem = typed("object", null);
        problem.addProperty("type", typed(STRING, "uri"));
        problem.addProperty("title", typed(STRING, null));
        problem.addProperty("status", typed(INTEGER, "int32"));
        problem.addProperty("detail", typed(STRING, null));
        problem.addProperty("code", typed(STRING, null));
        problem.addProperty("correlationId", typed(STRING, null));
        Schema<?> violation = new JsonSchema();
        violation.set$ref(SCHEMAS + VIOLATION);
        problem.addProperty("violations", typed("array", null).items(violation));
        problem.required(List.of("type", "title", "status", "code", "correlationId"));
        return problem;
    }

    private static Schema<?> violation() {
        Schema<?> violation = typed("object", null);
        violation.addProperty("field", typed(STRING, null));
        violation.addProperty("message", typed(STRING, null));
        violation.required(List.of("field", "message"));
        return violation;
    }

    private static Schema<?> typed(String type, @Nullable String format) {
        Schema<?> schema = new JsonSchema();
        schema.addType(type);
        if (format != null) {
            schema.setFormat(format);
        }
        return schema;
    }

    private static boolean isRead(HttpMethod method) {
        return HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method);
    }

    private static Class<?> operationClass(ServiceOperation<?, ?> operation) {
        return ClassUtils.getUserClass(AopProxyUtils.ultimateTargetClass(operation));
    }

    /** The operation's output type {@code O}, with its generics ({@code Optional<Book>}, {@code PageResult<Book>}). */
    private static ResolvableType outputType(ServiceOperation<?, ?> operation) {
        return ResolvableType.forClass(operationClass(operation)).as(ServiceOperation.class).getGeneric(1);
    }

    /**
     * What {@link #document} produces.
     *
     * @param paths the path items by OpenAPI path template, in the order of the routes documented
     * @param schemas the component schemas: the routes' types, {@code SynapseProblem} and {@code SynapseViolation}
     * @param headers the component headers: the correlation header
     * @param undescribed the types documented as {@code {}}, sorted, each with the first route that used it, as
     *        {@code METHOD path (operation)}
     */
    public record Fragment(Map<String, PathItem> paths, Map<String, Schema<?>> schemas, Map<String, Header> headers,
            Map<String, String> undescribed) {
    }
}
