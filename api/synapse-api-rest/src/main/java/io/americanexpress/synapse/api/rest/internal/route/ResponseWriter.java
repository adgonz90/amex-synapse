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
package io.americanexpress.synapse.api.rest.internal.route;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import org.jspecify.annotations.Nullable;
import org.springframework.core.convert.ConversionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.introspect.BeanPropertyDefinition;
import tools.jackson.databind.introspect.ClassIntrospector;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.americanexpress.synapse.api.rest.internal.binding.ResponseRules;
import io.americanexpress.synapse.api.rest.internal.binding.ResponseRules.OutputHeader;
import io.americanexpress.synapse.api.rest.internal.binding.RouteDefinition;
import io.americanexpress.synapse.core.error.ErrorDetails;
import io.americanexpress.synapse.core.error.SynapseErrors;
import io.americanexpress.synapse.core.error.SynapseException;
import io.americanexpress.synapse.core.internal.binding.ResolvedComponent;

/**
 * Maps an operation's output to the response status, headers and JSON body, following the route's response rules.
 *
 * <ul>
 * <li>Status: {@code .status(int)} or {@code .status(fn)} when declared; otherwise 202 with {@code .accepted(fn)},
 * 201 with {@code .created(fn)}, 204 on {@code DELETE}, and 200. A {@code null} output (a {@code void} operation) is
 * 204, or the declared {@code .status(int)}.</li>
 * <li>An empty {@code Optional} throws the route's {@code .notFound} error ({@code NOT_FOUND} by default), or answers
 * the {@code .emptyAs} status with no body.</li>
 * <li>{@code .created(fn)} sets {@code Location}; {@code .accepted(fn)} sets {@code Location} and
 * {@code Link: <url>; rel="status"}.</li>
 * <li>Output headers are written from their components, formatted by the conversion service, and the components are
 * removed from the body unless {@code .alsoInBody()}. A {@code null} or empty {@code Optional} component writes no
 * header; a {@code List} writes one value per element. {@code .body(component)} makes that component the whole body,
 * and a {@code null} body component is 204 with no body.</li>
 * <li>The body is serialized with the application's {@link JsonMapper} as {@code application/json}. A 204 or 304
 * response has no body, and a {@code HEAD} request gets the headers of the {@code GET} response without the body.</li>
 * </ul>
 */
public final class ResponseWriter {

    private final JsonMapper mapper;

    private final ConversionService conversionService;

    /** For each output type, the JSON property name of each component the mapper writes. */
    private final Map<Class<?>, Map<String, String>> jsonNames = new ConcurrentHashMap<>();

    /**
     * Creates the writer.
     *
     * @param mapper the application's mapper, which writes the body
     * @param conversionService formats output header values (in an application, Spring MVC's conversion service
     *        bean)
     */
    public ResponseWriter(JsonMapper mapper, ConversionService conversionService) {
        this.conversionService = conversionService;
        this.mapper = mapper;
    }

    /**
     * Writes the operation's output.
     *
     * @param route the resolved route
     * @param result the operation's output, exactly as {@code execute} returned it
     * @param request the request
     * @return the response
     * @throws SynapseException for an empty {@code Optional} without {@code .emptyAs}
     * @throws IllegalStateException when a header value computed from the output has a control character
     */
    public ServerResponse write(RouteDefinition route, @Nullable Object result, ServerRequest request) {
        ResponseRules rules = route.response();
        Object value = result;
        if (result instanceof Optional<?> || route.outputType() == Optional.class) {
            // An operation that returns null for an Optional output means empty.
            Optional<?> present = result instanceof Optional<?> optional ? optional : Optional.empty();
            if (present.isEmpty()) {
                return empty(rules);
            }
            value = present.get();
        }
        if (value == null) {
            Integer status = rules.status();
            return ServerResponse.status(status != null ? status : 204).build();
        }
        Object output = value;
        HttpHeaders headers = headers(route, output, result);
        ResolvedComponent bodyComponent = rules.bodyComponent();
        Object bodyValue = bodyComponent != null ? componentValue(bodyComponent, output) : body(rules, output);
        // A null body component means there is nothing to send.
        int status = bodyValue == null ? 204 : status(route, output, result);
        ServerResponse.BodyBuilder response = ServerResponse.status(status);
        response.headers(target -> target.addAll(headers));
        if (status == 204 || status == 304) {
            return response.build();
        }
        byte[] body = mapper.writeValueAsBytes(bodyValue);
        // HEAD gets the GET response's headers, Content-Length included, without the body.
        boolean head = HttpMethod.HEAD.equals(request.method());
        return response
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(body.length)
                .build((servletRequest, servletResponse) -> {
                    if (!head) {
                        servletResponse.getOutputStream().write(body);
                    }
                    return null;
                });
    }

    private static ServerResponse empty(ResponseRules rules) {
        Integer emptyAs = rules.emptyAs();
        if (emptyAs != null) {
            return ServerResponse.status(emptyAs).build();
        }
        ErrorDetails notFound = rules.notFound();
        throw new SynapseException(notFound != null ? notFound : SynapseErrors.NOT_FOUND);
    }

    private static int status(RouteDefinition route, Object output, @Nullable Object result) {
        ResponseRules rules = route.response();
        Integer fixed = rules.status();
        if (fixed != null) {
            return fixed;
        }
        ToIntFunction<Object> computed = rules.statusFunction();
        if (computed != null) {
            return computed.applyAsInt(result != null ? result : output);
        }
        if (rules.accepted() != null) {
            return 202;
        }
        if (rules.created() != null) {
            return 201;
        }
        return HttpMethod.DELETE.equals(route.method()) ? 204 : 200;
    }

    /** The response headers: the locations, then the output headers in declaration order. */
    private HttpHeaders headers(RouteDefinition route, Object output, @Nullable Object result) {
        ResponseRules rules = route.response();
        Object raw = result != null ? result : output;
        HttpHeaders headers = new HttpHeaders();
        Function<Object, String> accepted = rules.accepted();
        Function<Object, String> created = rules.created();
        if (accepted != null) {
            String location = checked(HttpHeaders.LOCATION, accepted.apply(raw));
            headers.add(HttpHeaders.LOCATION, location);
            headers.add(HttpHeaders.LINK, "<" + location + ">; rel=\"status\"");
        } else if (created != null) {
            headers.add(HttpHeaders.LOCATION, checked(HttpHeaders.LOCATION, created.apply(raw)));
        }
        for (OutputHeader header : rules.outputHeaders()) {
            Object value = componentValue(header.component(), output);
            if (value instanceof Optional<?> optional) {
                value = optional.orElse(null);
            }
            if (value instanceof Iterable<?> values) {
                for (Object element : values) {
                    addHeader(headers, header.name(), element);
                }
            } else {
                addHeader(headers, header.name(), value);
            }
        }
        return headers;
    }

    /** Adds one header value, formatted by the conversion service; a null value adds nothing. */
    private void addHeader(HttpHeaders headers, String name, @Nullable Object value) {
        if (value == null) {
            return;
        }
        String text = conversionService.convert(value, String.class);
        if (text != null) {
            headers.add(name, checked(name, text));
        }
    }

    /** Refuses a header value with a control character, which could split the response. */
    private static String checked(String name, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7f) {
                throw new IllegalStateException("The " + name + " header value computed from the operation's output "
                        + "has a control character");
            }
        }
        return value;
    }

    /** The whole output, without the components written to headers only. */
    private Object body(ResponseRules rules, Object output) {
        List<String> removed = new ArrayList<>();
        for (OutputHeader header : rules.outputHeaders()) {
            if (!header.alsoInBody()) {
                removed.add(header.component().name());
            }
        }
        if (removed.isEmpty()) {
            return output;
        }
        JsonNode tree = mapper.valueToTree(output);
        if (tree instanceof ObjectNode object) {
            Map<String, String> names = jsonNames.computeIfAbsent(output.getClass(), this::serializedNames);
            for (String component : removed) {
                String name = names.get(component);
                if (name != null) {
                    object.remove(name);
                }
            }
        }
        return tree;
    }

    /** Maps each component the mapper writes to its JSON property name (naming strategy and annotations applied). */
    private Map<String, String> serializedNames(Class<?> type) {
        SerializationConfig config = mapper.serializationConfig();
        JavaType javaType = mapper.constructType(type);
        ClassIntrospector introspector = config.classIntrospectorInstance();
        BeanDescription description = introspector
                .introspectForSerialization(javaType, introspector.introspectClassAnnotations(javaType));
        Map<String, String> names = new HashMap<>();
        for (BeanPropertyDefinition property : description.findProperties()) {
            names.put(property.getInternalName(), property.getName());
        }
        return Map.copyOf(names);
    }

    private static @Nullable Object componentValue(ResolvedComponent component, Object record) {
        Method accessor = component.component().getAccessor();
        ReflectionUtils.makeAccessible(accessor);
        return ReflectionUtils.invokeMethod(accessor, record);
    }
}
