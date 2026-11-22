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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.function.ServerRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.PropertyName;
import tools.jackson.databind.deser.SettableBeanProperty;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.americanexpress.synapse.api.rest.RestErrors;
import io.americanexpress.synapse.api.rest.internal.conditional.Preconditions;
import io.americanexpress.synapse.api.rest.internal.edge.RequestTooLargeException;
import io.americanexpress.synapse.api.rest.internal.paging.PageQueryReader;
import io.americanexpress.synapse.api.rest.internal.paging.PagingSettings;
import io.americanexpress.synapse.api.rest.internal.paging.SortKeys;
import io.americanexpress.synapse.core.SynapseConfigurationException;
import io.americanexpress.synapse.core.conditional.VersionCondition;
import io.americanexpress.synapse.core.context.RequestContext;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.error.Violation;
import io.americanexpress.synapse.core.internal.binding.ResolvedComponent;
import io.americanexpress.synapse.core.internal.conditional.EntityTags;
import io.americanexpress.synapse.core.internal.conditional.VersionTokens;
import io.americanexpress.synapse.core.paging.PageQuery;

/**
 * Turns a request into the operation's input record, reading only the sources of a resolved {@link RouteDefinition}.
 *
 * <ul>
 * <li>Path variables, query parameters, headers and cookies are converted with the {@link ConversionService}. Query
 * parameters come from the query string only, never from a form body. A component that is not a collection or an
 * array takes one value: a repeated query parameter or header line is a violation. A collection or array component
 * takes every value of a query parameter, each one item, and splits a header's lines on commas.</li>
 * <li>{@code If-Match} and {@code If-None-Match} are parsed as entity tags. A {@code VersionCondition} component binds
 * {@code AnyVersion} for {@code *}, {@code Version(v)} for one tag (its opaque value converted to the version type) and
 * {@code null} without the header; a plain component (on a read) binds the converted value, and {@code null} for
 * {@code *}.</li>
 * <li>The body is read only on POST and PUT when the route has a body source, and only as JSON
 * ({@code application/json} or {@code *+json}); a missing or empty body counts as {@code {}}.</li>
 * <li>A component filled from a body property is read with the creator property Jackson itself would use for it, so
 * Jackson's rules apply to it (naming, {@code @JsonProperty}, {@code @JsonAlias}, {@code @JsonFormat},
 * {@code @JsonDeserialize} on the component), and a component Jackson never reads (ignored or {@code READ_ONLY}) is
 * never taken from the body. Unknown properties are ignored. A record with its own deserializer is read whole by
 * Jackson instead, and the other sources then replace their components.</li>
 * <li>A component bound elsewhere whose JSON property is in the body with a different value is a conflict.</li>
 * <li>A {@code PageQuery} component, or a {@code PageQuery} input, is read by {@link PageQueryReader} from the paging
 * parameters of the query string, on every method; a {@code PageQuery} input reads nothing else, and a body property
 * named like a {@code PageQuery} component is ignored.</li>
 * </ul>
 *
 * Every component problem is collected, in component order, into one {@link SynapseErrors#VALIDATION_FAILED}. The
 * record is built once, with its canonical constructor, and only when there is none; a constructor that rejects the
 * values is also {@code VALIDATION_FAILED}. A violation never repeats the rejected value or a conversion, parser or
 * constructor message.
 */
public final class RequestBinder {

    /** The violation of a repeated value for a single-valued component or paging parameter. */
    public static final String SINGLE_VALUE = "must have a single value";

    private static final Logger LOG = LoggerFactory.getLogger("io.americanexpress.synapse.api.rest");

    private static final String INVALID = "is not a valid value";

    private static final String MISSING = "must not be null";

    private static final String NOT_A_TAG = "must be an entity tag";

    private static final String NOT_A_SINGLE_TAG = "must be a single entity tag";

    private static final String CONFLICT = "conflicts with the value from the ";

    private static final TypeDescriptor STRING = TypeDescriptor.valueOf(String.class);

    private static final TypeDescriptor STRINGS = TypeDescriptor.collection(List.class, STRING);

    /** Stands for a component whose value could not be read; its violation is already recorded. */
    private static final Object FAILED = new Object();

    /** The application's mapper with unknown properties ignored at every level of the body. */
    private final JsonMapper bodyMapper;

    private final ConversionService conversionService;

    private final PageQueryReader pageReader;

    private final Map<Class<?>, BodyModel> models = new ConcurrentHashMap<>();

    private final Map<Class<?>, Constructor<?>> constructors = new ConcurrentHashMap<>();

    /**
     * Creates the binder.
     *
     * @param mapper reads the request body
     * @param conversionService converts path, query, header and cookie values (in an application, Spring MVC's
     *        conversion service bean)
     * @param paging the paging parameter names and caps, for {@code PageQuery} inputs
     */
    public RequestBinder(JsonMapper mapper, ConversionService conversionService, PagingSettings paging) {
        this.bodyMapper = mapper.rebuild().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        this.conversionService = conversionService;
        this.pageReader = new PageQueryReader(paging);
    }

    /**
     * Checks at startup that the route's input can be bound: the conversion service can convert a string (a list of
     * strings for a collection or array component) to every component bound from a path variable, a query parameter
     * (including the components of a record bound from the query string), a header or a cookie; Jackson can describe
     * the input type; and every non-nullable component filled from the body is one Jackson reads from JSON.
     *
     * @param route the resolved route
     * @throws SynapseConfigurationException when the route can never be bound
     */
    public void checkBindable(RouteDefinition route) {
        for (InputSource source : route.sources()) {
            switch (source) {
                case InputSource.Path path -> checkConvertible(route, path.component(), target(path.component()));
                case InputSource.Query query -> checkConvertible(route, query.component(), target(query.component()));
                case InputSource.QueryRecord query ->
                    query.components().forEach(c -> checkConvertible(route, c, target(c)));
                case InputSource.Header header -> {
                    // A VersionCondition's version is read by core's VersionTokens; BindingValidator checks its type.
                    if (!BindingValidator.isVersionCondition(header.component())) {
                        checkConvertible(route, header.component(), target(header.component()));
                    }
                }
                case InputSource.Cookie cookie ->
                    checkConvertible(route, cookie.component(), target(cookie.component()));
                case InputSource.Body body -> {
                    // Read by Jackson; checked below.
                }
                case InputSource.BodyComponent body -> {
                    // Read by Jackson.
                }
                case InputSource.Context context -> {
                    // Application code supplies the value.
                }
                case InputSource.Custom custom -> {
                    // Application code supplies the value.
                }
                case InputSource.Page page -> {
                    // Read from the paging parameters.
                }
            }
        }
        checkBodyBindable(route);
    }

    /**
     * Fails when the conversion service can't convert request text to the component. A plain component bound from
     * {@code If-Match} or {@code If-None-Match} (on a read) is checked the same way: the entity tag's value is converted
     * to the component type. A {@code VersionCondition}'s version is not: core's {@code VersionTokens} reads it.
     */
    private void checkConvertible(RouteDefinition route, ResolvedComponent component, TypeDescriptor target) {
        TypeDescriptor source = target.isCollection() || target.isArray() ? STRINGS : STRING;
        if (!conversionService.canConvert(source, target)) {
            throw failure(route,
                    "the component " + component.recordType().getSimpleName() + "." + component.name() + " (type "
                            + component.genericType().getTypeName() + ") can't be converted from a string",
                    "Give " + target.getType().getSimpleName() + " a constructor or a static valueOf, of or from "
                            + "method taking a String, register a Converter from String to it, or bind the component "
                            + "from the body, .context or .from.",
                    null);
        }
    }

    /** Returns the type request text is converted to for a component: its own type. */
    private static TypeDescriptor target(ResolvedComponent component) {
        return new TypeDescriptor(ResolvableType.forType(component.genericType()), null, null);
    }

    private void checkBodyBindable(RouteDefinition route) {
        Class<?> inputType = route.inputType();
        if (!inputType.isRecord() || route.sources().stream().noneMatch(InputSource.Body.class::isInstance)) {
            return;
        }
        BodyModel model;
        try {
            model = model(inputType);
        } catch (JacksonException e) {
            throw failure(route, "Jackson cannot read the input type " + inputType.getName() + " from JSON",
                    "Fix the Jackson mapping of " + inputType.getSimpleName()
                            + " (for example two components with the same JSON name).",
                    e);
        }
        Map<String, SettableBeanProperty> properties = model.properties();
        if (properties == null) {
            // The record's own deserializer decides what it reads.
            return;
        }
        for (InputSource source : route.sources()) {
            ResolvedComponent component = source.component();
            if (source instanceof InputSource.Body && !component.nullable()
                    && !properties.containsKey(component.name())) {
                throw failure(route,
                        "component " + component.recordType().getSimpleName() + "." + component.name()
                                + " is bound from the body but Jackson ignores it",
                        "Bind it on the route from another source (.path, .query, .header, .cookie, .context or "
                                + ".from), make it @Nullable, or remove the @JsonIgnore, @JsonIgnoreProperties or "
                                + "READ_ONLY access that hides it from JSON.",
                        null);
            }
        }
    }

    private static SynapseConfigurationException failure(RouteDefinition route, String problem, String action,
            @Nullable Throwable cause) {
        SynapseConfigurationException failure = new SynapseConfigurationException("REST route "
                + route.method().name() + " " + route.path() + " (" + route.operationName() + "): " + problem, action);
        if (cause != null) {
            failure.initCause(cause);
        }
        return failure;
    }

    /**
     * Builds the route's input from the request.
     *
     * @param route the resolved route
     * @param request the request
     * @return the input record, or {@code null} when the input type is {@code Void}
     * @throws SynapseException {@link RestErrors#UNSUPPORTED_MEDIA_TYPE} for a body that isn't JSON,
     *         {@link RestErrors#PAYLOAD_TOO_LARGE} for a body over the request size limit,
     *         {@link SynapseErrors#MALFORMED_REQUEST} for a body or query string that can't be read,
     *         {@link SynapseErrors#VALIDATION_FAILED} with the violations of the components that can't be bound, and,
     *         after those, what {@link Preconditions#missingPrecondition} answers when a header bound to a non-null
     *         {@code VersionCondition} is absent: {@link SynapseErrors#PRECONDITION_FAILED} for an unbound or weak
     *         precondition, otherwise {@link SynapseErrors#PRECONDITION_REQUIRED}
     */
    public @Nullable Object bind(RouteDefinition route, ServerRequest request) {
        Class<?> inputType = route.inputType();
        if (!inputType.isRecord()) {
            // The startup checks admit only records and Void.
            return null;
        }
        PagingRules paging = route.paging();
        SortKeys sortKeys = paging == null ? null : paging.sortKeys();
        if (inputType == PageQuery.class) {
            // The whole input is read from the paging parameters; nothing else, not even the body, is read.
            List<Violation> violations = new ArrayList<>();
            PageQuery page = pageReader
                    .read(parseQuery(request.servletRequest().getQueryString()), sortKeys, violations);
            if (!violations.isEmpty()) {
                throw SynapseException.ofViolations(SynapseErrors.VALIDATION_FAILED, violations);
            }
            return page;
        }
        JsonNode body = readsBody(route) ? readBody(request) : null;
        ObjectNode properties = null;
        BodyModel model = BodyModel.NONE;
        if (body != null && route.sources().stream().anyMatch(InputSource.Body.class::isInstance)) {
            // The body fills components by property, so it must be an object.
            if (!(body instanceof ObjectNode object)) {
                throw new SynapseException(SynapseErrors.MALFORMED_REQUEST);
            }
            properties = object;
            model = model(inputType);
        }
        return new Binding(route, request, body, properties, model, sortKeys).bind(inputType, route.sources());
    }

    private BodyModel model(Class<?> inputType) {
        return models.computeIfAbsent(inputType, this::createModel);
    }

    private BodyModel createModel(Class<?> inputType) {
        Map<String, SettableBeanProperty> properties = JsonNames.creatorProperties(bodyMapper, inputType);
        if (properties == null || !JsonNames.readsByProperty(bodyMapper, inputType)) {
            // Jackson reads the record as a whole: its own deserializer, or a delegating creator, which has creator
            // properties of the canonical constructor too but wins over them. The whole-record path honours it.
            return new BodyModel(null, Map.of());
        }
        Map<String, List<String>> names = new HashMap<>();
        properties.forEach((component, property) -> {
            List<String> accepted = new ArrayList<>();
            accepted.add(property.getName());
            for (PropertyName alias : property.findAliases(bodyMapper.deserializationConfig())) {
                accepted.add(alias.getSimpleName());
            }
            names.put(component, List.copyOf(accepted));
        });
        return new BodyModel(properties, names);
    }

    private static boolean readsBody(RouteDefinition route) {
        boolean bodyMethod = route.method().equals(HttpMethod.POST) || route.method().equals(HttpMethod.PUT);
        return bodyMethod && route
                .sources()
                .stream()
                .anyMatch(source -> source instanceof InputSource.Body || source instanceof InputSource.BodyComponent);
    }

    private JsonNode readBody(ServerRequest request) {
        HttpServletRequest servletRequest = request.servletRequest();
        String contentType = servletRequest.getContentType();
        if (contentType == null ? hasContent(servletRequest) : !isJson(contentType)) {
            throw new SynapseException(RestErrors.UNSUPPORTED_MEDIA_TYPE);
        }
        if (contentType == null) {
            return bodyMapper.createObjectNode();
        }
        JsonNode tree;
        try {
            tree = bodyMapper.readTree(servletRequest.getInputStream());
        } catch (IOException | JacksonException e) {
            // The one place a body that can't be read lands: a stream failure (an IOException, which Jackson wraps
            // in JacksonIOException), a body over the size limit (thrown by the stream, so possibly wrapped too) or
            // JSON that doesn't parse.
            if (RequestTooLargeException.isCauseOf(e)) {
                throw new SynapseException(RestErrors.PAYLOAD_TOO_LARGE, e);
            }
            throw new SynapseException(SynapseErrors.MALFORMED_REQUEST, e);
        }
        return tree == null || tree.isMissingNode() ? bodyMapper.createObjectNode() : tree;
    }

    private static boolean hasContent(HttpServletRequest request) {
        return request.getContentLengthLong() > 0 || request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null;
    }

    private static boolean isJson(String contentType) {
        MediaType type;
        try {
            type = MediaType.parseMediaType(contentType);
        } catch (InvalidMediaTypeException e) {
            return false;
        }
        if (type.isWildcardSubtype()) {
            return false;
        }
        return (type.getType().equals("application") && type.getSubtype().equals("json"))
                || "json".equals(type.getSubtypeSuffix());
    }

    /** Parses a raw query string; names and values are decoded by {@link #decode(String)}. */
    private static MultiValueMap<String, String> parseQuery(@Nullable String query) {
        MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        if (query == null) {
            return parameters;
        }
        for (String pair : StringUtils.delimitedListToStringArray(query, "&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            parameters.add(decode(name), decode(value));
        }
        return parameters;
    }

    /**
     * Decodes one query string name or value: {@code +} is a space, as servlet containers decode parameters, and
     * percent escapes must form valid UTF-8. Anything else is {@link SynapseErrors#MALFORMED_REQUEST}.
     */
    private static String decode(String text) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(text.length());
        int literal = 0;
        int index = 0;
        while (index < text.length()) {
            if (text.charAt(index) != '%') {
                index++;
                continue;
            }
            bytes.writeBytes(text.substring(literal, index).replace('+', ' ').getBytes(StandardCharsets.UTF_8));
            int high = index + 2 < text.length() ? Character.digit(text.charAt(index + 1), 16) : -1;
            int low = high < 0 ? -1 : Character.digit(text.charAt(index + 2), 16);
            if (low < 0) {
                throw new SynapseException(SynapseErrors.MALFORMED_REQUEST);
            }
            bytes.write((high << 4) | low);
            index += 3;
            literal = index;
        }
        bytes.writeBytes(text.substring(literal).replace('+', ' ').getBytes(StandardCharsets.UTF_8));
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new SynapseException(SynapseErrors.MALFORMED_REQUEST, e);
        }
    }

    private ObjectReader reader(ResolvedComponent component) {
        return bodyMapper.readerFor(bodyMapper.constructType(component.genericType()));
    }

    /** Reads one JSON value with the creator property Jackson uses for that component. */
    private @Nullable Object deserialize(SettableBeanProperty property, JsonNode node) {
        // The parser's read context is a deserialization context of the mapper, bound to that parser. Jackson 3.1
        // builds treeAsTokens' parser so; ObjectReadContext doesn't promise it, and RequestBinderTest catches a
        // change (a ClassCastException) at upgrade time.
        try (JsonParser parser = bodyMapper.treeAsTokens(node)) {
            parser.nextToken();
            return property.deserialize(parser, (DeserializationContext) parser.objectReadContext());
        }
    }

    /**
     * Builds the input record. A constructor that rejects the values is {@link SynapseErrors#VALIDATION_FAILED}
     * without detail, unless it threw its own {@link SynapseException}, which reaches the caller as it is.
     */
    private Object construct(Class<?> recordType, @Nullable Object[] args) {
        try {
            return instantiate(recordType, args);
        } catch (Rejected rejected) {
            Throwable thrown = rejected.getCause();
            if (thrown instanceof SynapseException synapse) {
                // The record reported its own error.
                throw synapse;
            }
            LOG.debug("{} rejected the bound values", recordType.getName(), thrown);
            throw new SynapseException(SynapseErrors.VALIDATION_FAILED, Objects.requireNonNull(thrown));
        }
    }

    /** Calls the canonical constructor; what it throws comes back as {@link Rejected}, except an {@link Error}. */
    private Object instantiate(Class<?> recordType, @Nullable Object[] args) throws Rejected {
        Constructor<?> constructor = constructors.computeIfAbsent(recordType, RequestBinder::canonicalConstructor);
        try {
            return constructor.newInstance(args);
        } catch (InvocationTargetException e) {
            Throwable thrown = e.getTargetException();
            if (thrown instanceof Error error) {
                throw error;
            }
            throw new Rejected(thrown);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot create " + recordType.getName(), e);
        }
    }

    /** A record constructor rejected its values; the cause is what it threw. */
    private static final class Rejected extends Exception {

        private static final long serialVersionUID = 1L;

        Rejected(Throwable cause) {
            super(null, cause, false, false);
        }
    }

    private static Constructor<?> canonicalConstructor(Class<?> recordType) {
        Class<?>[] types = Arrays
                .stream(Objects.requireNonNull(recordType.getRecordComponents()))
                .map(RecordComponent::getType)
                .toArray(Class<?>[]::new);
        try {
            Constructor<?> constructor = recordType.getDeclaredConstructor(types);
            ReflectionUtils.makeAccessible(constructor);
            return constructor;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("No canonical constructor on " + recordType.getName(), e);
        }
    }

    private static @Nullable Object componentValue(RecordComponent component, Object record) {
        Method accessor = component.getAccessor();
        ReflectionUtils.makeAccessible(accessor);
        return ReflectionUtils.invokeMethod(accessor, record);
    }

    private static String location(InputSource source) {
        return switch (source) {
            case InputSource.Path path -> "path";
            case InputSource.Query query -> "query string";
            case InputSource.QueryRecord query -> "query string";
            case InputSource.Header header -> "header";
            case InputSource.Cookie cookie -> "cookie";
            case InputSource.Context context -> "request context";
            case InputSource.Custom custom -> "request";
            case InputSource.Page page -> "paging parameters";
            case InputSource.Body body -> "body";
            case InputSource.BodyComponent body -> "body";
        };
    }

    private static List<String> single(@Nullable String value) {
        return value == null ? List.of() : List.of(value);
    }

    /**
     * How a source's values become the items of a collection or array component. Only a list header drops empty
     * items: RFC 9110 §5.6.1.2 asks that of a list-valued field. Every other source keeps them, as a gRPC
     * {@code repeated} field does, so a constraint on the items, such as {@code List<@NotBlank String>}, gets the same
     * answer over both protocols.
     */
    private enum Items {

        /** A query value is one item, never split, so an encoded comma ({@code %2C}) stays in it. */
        QUERY,

        /** Path variable and cookie values are split on commas, each item trimmed, and empty items kept. */
        COMMA_LIST,

        /**
         * A list header's lines are split on commas, each item trimmed, and empty items dropped: HTTP defines a list
         * header's lines as equal to one line of their values joined with commas.
         */
        HEADER
    }

    /**
     * How a record is read from body properties.
     *
     * @param properties the creator property for each component Jackson reads, or {@code null} when Jackson reads
     *        the record as a whole: its own deserializer, a delegating creator or a builder
     * @param names the JSON names (primary name, then aliases) accepted for each of those components
     */
    private record BodyModel(@Nullable Map<String, SettableBeanProperty> properties, Map<String, List<String>> names) {

        static final BodyModel NONE = new BodyModel(Map.of(), Map.of());
    }

    /** The state of binding one request. */
    private final class Binding {

        private final RouteDefinition route;

        private final ServerRequest request;

        private final @Nullable JsonNode body;

        /** The body when it fills components by property; {@code null} otherwise. */
        private final @Nullable ObjectNode properties;

        private final BodyModel model;

        /** The route's sort allow-list, or {@code null} when it takes no sort parameter. */
        private final @Nullable SortKeys sortKeys;

        private final List<Violation> violations = new ArrayList<>();

        /** Whether a precondition header the input requires is absent, so the record can't be built. */
        private boolean preconditionMissing;

        private @Nullable MultiValueMap<String, String> query;

        Binding(RouteDefinition route, ServerRequest request, @Nullable JsonNode body, @Nullable ObjectNode properties,
                BodyModel model, @Nullable SortKeys sortKeys) {
            this.route = route;
            this.request = request;
            this.body = body;
            this.properties = properties;
            this.model = model;
            this.sortKeys = sortKeys;
        }

        Object bind(Class<?> inputType, List<InputSource> sources) {
            RecordComponent[] components = Objects.requireNonNull(inputType.getRecordComponents());
            Map<String, InputSource> byComponent = new HashMap<>();
            for (InputSource source : sources) {
                byComponent.put(source.component().name(), source);
            }
            ObjectNode object = properties;
            boolean readWhole = false;
            Object whole = null;
            if (object != null && model.properties() == null) {
                readWhole = true;
                whole = readWhole(inputType, object);
            }
            @Nullable
            Object[] args = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                InputSource source = byComponent.get(components[i].getName());
                if (source instanceof InputSource.Body && readWhole) {
                    // No presence checks: the record's own deserializer decides what is required.
                    args[i] = whole == null ? null : componentValue(components[i], whole);
                } else if (source instanceof InputSource.Body) {
                    args[i] = bodyValue(source.component());
                } else if (source != null) {
                    args[i] = bind(source);
                }
            }
            if (!violations.isEmpty()) {
                throw SynapseException.ofViolations(SynapseErrors.VALIDATION_FAILED, violations);
            }
            if (preconditionMissing) {
                // The input requires a precondition the request doesn't send, so its record can't be built. The
                // preconditions answer now, in their own order: an unbound or weak one is 412, then this one is 428.
                throw Preconditions.missingPrecondition(route, request);
            }
            return construct(inputType, args);
        }

        /** Reads a record that has its own deserializer; Jackson decides everything about it. */
        private @Nullable Object readWhole(Class<?> inputType, ObjectNode object) {
            try {
                return bodyMapper.readerFor(inputType).readValue(object);
            } catch (RuntimeException e) {
                // Jackson's failure or anything the record's deserializer throws; its text never reaches the caller.
                throw new SynapseException(SynapseErrors.MALFORMED_REQUEST, e);
            }
        }

        private @Nullable Object bodyValue(ResolvedComponent component) {
            JsonNode node = node(component);
            if (node == null || node.isNull()) {
                if (!component.nullable()) {
                    violations.add(new Violation(component.name(), MISSING));
                }
                return null;
            }
            Object value;
            try {
                value = deserialize(property(component), node);
            } catch (RuntimeException e) {
                // Jackson's failure or anything a component deserializer throws; its text never reaches the caller.
                LOG.debug("Body property for {} could not be read", component.name(), e);
                violations.add(new Violation(component.name(), INVALID));
                return null;
            }
            if (value == null && !component.nullable()) {
                violations.add(new Violation(component.name(), MISSING));
            }
            return value;
        }

        /** The component's JSON property in the body, under its name or one of its aliases. */
        private @Nullable JsonNode node(ResolvedComponent component) {
            ObjectNode object = properties;
            List<String> names = model.names().get(component.name());
            if (object == null || names == null) {
                return null;
            }
            for (String name : names) {
                JsonNode node = object.get(name);
                if (node != null) {
                    return node;
                }
            }
            return null;
        }

        private SettableBeanProperty property(ResolvedComponent component) {
            return Objects.requireNonNull(Objects.requireNonNull(model.properties()).get(component.name()));
        }

        private @Nullable Object bind(InputSource source) {
            ResolvedComponent component = source.component();
            Object value = value(source);
            if (value == FAILED) {
                return null;
            }
            if (value == null && !component.nullable() && source instanceof InputSource.Header
                    && BindingValidator.isVersionCondition(component)) {
                // A required precondition (a VersionCondition header is If-Match or If-None-Match, and a non-null one
                // is on a write), absent: not a 400, but PRECONDITION_REQUIRED once the 400s are decided.
                preconditionMissing = true;
                return null;
            }
            if (value == null && !component.nullable()) {
                violations.add(new Violation(component.name(), MISSING));
                return null;
            }
            // Paging never comes from the body, so a body property named like the PageQuery component is ignored.
            if (!(source instanceof InputSource.Page) && conflicts(component, value)) {
                violations.add(new Violation(component.name(), CONFLICT + location(source)));
                return null;
            }
            return value;
        }

        private @Nullable Object value(InputSource source) {
            ResolvedComponent component = source.component();
            return switch (source) {
                case InputSource.Path path -> convert(component, single(request.pathVariables().get(path.variable())));
                case InputSource.Query query -> convertQuery(component, parameter(query.parameter()));
                case InputSource.QueryRecord query -> queryRecord(query);
                case InputSource.Header header -> header(header);
                case InputSource.Cookie cookie -> convert(component, cookies(cookie.name()));
                case InputSource.Context context -> context.value().apply(RequestContext.current());
                case InputSource.Custom custom -> custom.value().apply(request);
                case InputSource.BodyComponent whole -> read(component, body);
                case InputSource.Body property -> throw new IllegalStateException("Read by property");
                case InputSource.Page page -> pageReader.read(query(), sortKeys, violations);
            };
        }

        /**
         * Reads a header. {@code If-Match} and {@code If-None-Match} are entity tag lists, and one tag binds its
         * opaque value, strong or weak (the preconditions judge weakness). A {@code VersionCondition} component gets
         * {@code VersionCondition.any()} for {@code *} and {@code VersionCondition.of(value)} for one tag; any other
         * component gets {@code null} for {@code *} and the value itself for one tag. An absent header binds
         * {@code null}, which a non-null {@code VersionCondition} turns into {@code PRECONDITION_REQUIRED}.
         */
        private @Nullable Object header(InputSource.Header source) {
            List<String> values = request.headers().header(source.name());
            String name = source.name();
            ResolvedComponent component = source.component();
            if (values.isEmpty() || !(HttpHeaders.IF_MATCH.equalsIgnoreCase(name)
                    || HttpHeaders.IF_NONE_MATCH.equalsIgnoreCase(name))) {
                return convertHeader(component, values);
            }
            EntityTags.Parsed parsed;
            try {
                parsed = EntityTags.parse(values);
            } catch (IllegalArgumentException e) {
                violations.add(new Violation(component.name(), NOT_A_TAG));
                return FAILED;
            }
            boolean condition = BindingValidator.isVersionCondition(component);
            if (!(parsed instanceof EntityTags.Parsed.Tags list)) {
                return condition ? VersionCondition.any() : null;
            }
            if (list.tags().size() > 1) {
                violations.add(new Violation(component.name(), NOT_A_SINGLE_TAG));
                return FAILED;
            }
            String opaque = list.tags().getFirst().opaque();
            if (!condition) {
                return convertHeader(component, List.of(opaque));
            }
            // The version token codec every protocol shares, never MVC's formatters: a token gRPC wrote reads the same.
            Class<?> versionType = Objects.requireNonNull(BindingValidator.versionType(component)).toClass();
            try {
                return VersionCondition.of(VersionTokens.parse(opaque, versionType));
            } catch (IllegalArgumentException e) {
                // A tag that names no version, such as "" or "abc" for a number; the message never repeats it.
                LOG.debug("The version of {} could not be read", component.name(), e);
                violations.add(new Violation(component.name(), INVALID));
                return FAILED;
            }
        }

        private MultiValueMap<String, String> query() {
            MultiValueMap<String, String> parsed = query;
            if (parsed == null) {
                parsed = parseQuery(request.servletRequest().getQueryString());
                query = parsed;
            }
            return parsed;
        }

        private List<String> parameter(String name) {
            return query().getOrDefault(name, List.of());
        }

        /** The values of every cookie of that name, in the order the request sent them. */
        private List<String> cookies(String name) {
            return request.cookies().getOrDefault(name, List.of()).stream().map(Cookie::getValue).toList();
        }

        /** Converts path variable or cookie values; a list component splits each on commas and keeps empty items. */
        private @Nullable Object convert(ResolvedComponent component, List<String> values) {
            return convert(component, values, Items.COMMA_LIST);
        }

        /** Converts the values of a query parameter, each one item of a list component, never split. */
        private @Nullable Object convertQuery(ResolvedComponent component, List<String> values) {
            return convert(component, values, Items.QUERY);
        }

        /** Converts header lines; a list component splits them on commas and drops empty items. */
        private @Nullable Object convertHeader(ResolvedComponent component, List<String> values) {
            return convert(component, values, Items.HEADER);
        }

        /**
         * Converts a source's values to the component. A component that is not a collection or an array takes one
         * value: more (a repeated query parameter, header line or cookie) is the violation
         * {@value RequestBinder#SINGLE_VALUE}, never a chosen or joined one. A collection or array component takes
         * every value, read into items as {@link Items} says: only a list header drops empty items.
         */
        private @Nullable Object convert(ResolvedComponent component, List<String> values, Items mode) {
            if (values.isEmpty()) {
                return null;
            }
            TypeDescriptor target = target(component);
            boolean many = target.isCollection() || target.isArray();
            if (values.size() > 1 && !many) {
                violations.add(new Violation(component.name(), SINGLE_VALUE));
                return FAILED;
            }
            try {
                if (!many) {
                    return conversionService.convert(values.getFirst(), STRING, target);
                }
                return conversionService.convert(items(values, mode), STRINGS, target);
            } catch (ConversionFailedException e) {
                violations.add(new Violation(component.name(), INVALID));
                return FAILED;
            }
        }

        /**
         * The items of a list component's values, as {@link Items} says. A split value has the items of Spring's
         * comma-delimited list, so a whole empty value has none, and {@code ",x"} has {@code ""} and {@code x}.
         */
        private static List<String> items(List<String> values, Items mode) {
            if (mode == Items.QUERY) {
                return values;
            }
            List<String> items = new ArrayList<>();
            for (String value : values) {
                for (String item : StringUtils.commaDelimitedListToStringArray(value)) {
                    String trimmed = item.trim();
                    if (mode == Items.COMMA_LIST || !trimmed.isEmpty()) {
                        items.add(trimmed);
                    }
                }
            }
            return items;
        }

        private @Nullable Object queryRecord(InputSource.QueryRecord source) {
            List<ResolvedComponent> components = source.components();
            if (source.component().nullable()
                    && components.stream().noneMatch(component -> query().containsKey(component.name()))) {
                return null;
            }
            @Nullable
            Object[] args = new Object[components.size()];
            boolean failed = false;
            for (int i = 0; i < components.size(); i++) {
                ResolvedComponent component = components.get(i);
                Object value = convertQuery(component, parameter(component.name()));
                if (value == FAILED) {
                    failed = true;
                } else if (value == null && !component.nullable()) {
                    violations.add(new Violation(component.name(), MISSING));
                    failed = true;
                } else {
                    args[i] = value;
                }
            }
            if (failed) {
                return FAILED;
            }
            try {
                return instantiate(source.component().type(), args);
            } catch (Rejected rejected) {
                // Reported like any other value that can't be bound, so the other components' violations still are.
                LOG.debug("{} rejected the query values", source.component().type().getName(), rejected.getCause());
                violations.add(new Violation(source.component().name(), INVALID));
                return FAILED;
            }
        }

        private @Nullable Object read(ResolvedComponent component, @Nullable JsonNode node) {
            if (node == null || node.isNull()) {
                return null;
            }
            try {
                return reader(component).readValue(node);
            } catch (RuntimeException e) {
                // Jackson's failure or anything a deserializer throws; its text never reaches the caller.
                LOG.debug("Body for {} could not be read", component.name(), e);
                violations.add(new Violation(component.name(), INVALID));
                return FAILED;
            }
        }

        /**
         * Tells whether the body carries this component, bound elsewhere, with a different value. Only components
         * Jackson reads from JSON can conflict; the body value is read the way Jackson reads that component. A
         * {@code VersionCondition} component is compared by its version: the body property (such as the
         * {@code "version"} of a resource read back with {@code GET}) is read as the version type and compared with
         * the value of {@code Version(v)}; {@code AnyVersion} and {@code null} carry no value, so only a {@code null}
         * body property matches them, as for a plain type bound from {@code *} or no header.
         */
        private boolean conflicts(ResolvedComponent component, @Nullable Object value) {
            JsonNode node = node(component);
            if (node == null) {
                return false;
            }
            boolean condition = BindingValidator.isVersionCondition(component);
            Object bound = condition ? versionValue(value) : value;
            Object bodyValue;
            try {
                if (node.isNull()) {
                    bodyValue = null;
                } else if (condition) {
                    bodyValue = bodyMapper
                            .readerFor(bodyMapper
                                    .constructType(Objects
                                            .requireNonNull(BindingValidator.versionType(component))
                                            .getType()))
                            .readValue(node);
                } else {
                    bodyValue = deserialize(property(component), node);
                }
            } catch (RuntimeException e) {
                // A body value that can't be read can't match the bound one.
                LOG.debug("Body property for {} could not be read", component.name(), e);
                return true;
            }
            return !Objects.equals(bound, bodyValue);
        }

        /** The version a bound {@code VersionCondition} names: the value of {@code Version(v)}, otherwise null. */
        private static @Nullable Object versionValue(@Nullable Object condition) {
            return condition instanceof VersionCondition.Version<?> version ? version.value() : null;
        }
    }
}
